package com.battleship.net;

import com.battleship.auth.AuthenticatedUser;
import com.battleship.auth.JwtService;
import com.battleship.config.AppConfig;
import com.battleship.game.GameSession;
import com.battleship.matchmaking.MatchService;
import com.battleship.matchmaking.MatchmakingService;
import com.battleship.protocol.ClientMessage;
import com.battleship.protocol.ErrorCodes;
import com.battleship.protocol.ServerMessage;
import com.battleship.redis.PresenceService;
import com.battleship.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Optional;

/**
 * Serves one WebSocket connection for its whole lifetime, on the thread that
 * accepted it.
 *
 * Thread-per-connection is the right shape here: a game connection is
 * long-lived and mostly idle, the read is genuinely blocking, and it keeps the
 * message ordering for a single client trivially correct. All writes go through
 * that client's {@link WriterThread}, so this thread never blocks on output.
 */
public final class WebSocketEndpoint {

    private static final Log log = Log.of(WebSocketEndpoint.class);

    /**
     * Read timeout. Longer than the heartbeat interval, so a live client always
     * refreshes it; a client that has silently vanished trips it and is cleaned up.
     */
    private static final int SOCKET_READ_TIMEOUT_MILLIS = 90_000;

    private final AppConfig config;
    private final JwtService jwt;
    private final ConnectionRegistry connections;
    private final MatchService matchService;
    private final MatchmakingService matchmaking;
    private final PresenceService presence;

    public WebSocketEndpoint(AppConfig config,
                             JwtService jwt,
                             ConnectionRegistry connections,
                             MatchService matchService,
                             MatchmakingService matchmaking,
                             PresenceService presence) {
        this.config       = config;
        this.jwt          = jwt;
        this.connections  = connections;
        this.matchService = matchService;
        this.matchmaking  = matchmaking;
        this.presence     = presence;
    }

    /**
     * Authenticates, upgrades, and then runs the read loop until the client
     * goes away. Returns only when the connection is finished.
     */
    public void serve(Socket socket, HttpRequest request, InputStream in, OutputStream out)
            throws IOException {

        // Authenticate before upgrading: a rejected client gets a plain HTTP
        // 401 it can actually read, rather than an opaque socket close.
        // The token arrives as a query parameter because the browser
        // WebSocket API cannot set an Authorization header.
        Optional<AuthenticatedUser> identity = request.query("token").flatMap(jwt::verify);
        if (identity.isEmpty()) {
            HttpResponse.json(401, "{\"error\":\"Missing or invalid token\"}").writeTo(out);
            return;
        }
        AuthenticatedUser user = identity.get();

        WebSocketUtil.acceptHandshake(request, out);
        socket.setSoTimeout(SOCKET_READ_TIMEOUT_MILLIS);

        WriterThread writer = new WriterThread(out, socket, user.username() + "#" + user.userId());
        writer.start();

        WebSocketConnection connection = new WebSocketConnection(socket, writer, user);
        Thread.currentThread().setName("conn-" + user.username() + "#" + connection.id());

        connections.register(connection);
        presence.markOnline(user.userId());
        log.info("Connected " + connection + " (" + connections.size() + " live)");

        try {
            MatchService.Reattachment reattachment = matchService.reattach(connection);
            connection.send(ServerMessage.hello(user.userId(), user.username(),
                    config.instanceId(), reattachment.kind() != MatchService.Reattachment.Kind.NONE));

            if (reattachment.kind() == MatchService.Reattachment.Kind.ELSEWHERE) {
                // The match is owned by another instance; point the client at it.
                connection.send(ServerMessage.matchFound(reattachment.matchId(), 0,
                        0, "", reattachment.serverUrl(), true));
            }

            readLoop(connection, in);
        } finally {
            teardown(connection);
        }
    }

    // ================================================================

    private void readLoop(WebSocketConnection connection, InputStream in) {
        while (true) {
            String raw;
            try {
                raw = WebSocketUtil.readMessage(in, connection.writer()::enqueue);
            } catch (SocketTimeoutException e) {
                log.info(connection + " timed out with no traffic; closing");
                return;
            } catch (IOException e) {
                log.info(connection + " read ended: " + e.getMessage());
                return;
            }
            if (raw == null) return;   // clean close from the peer

            try {
                handle(connection, ClientMessage.parse(raw));
            } catch (RuntimeException e) {
                // One bad message must not kill a connection that is otherwise fine.
                log.error("Error handling message from " + connection, e);
                connection.send(ServerMessage.error(ErrorCodes.INTERNAL_ERROR,
                        "The server could not process that message"));
            }
        }
    }

    private void handle(WebSocketConnection connection, ClientMessage message) {
        long userId = connection.userId();

        switch (message.type()) {
            case ClientMessage.PING -> connection.send(ServerMessage.pong());

            case ClientMessage.QUEUE_JOIN -> {
                if (!presence.allow("queue", userId, 10, 60)) {
                    connection.send(ServerMessage.error(ErrorCodes.RATE_LIMITED,
                            "Slow down — too many queue requests"));
                    return;
                }
                handleQueueJoin(connection);
            }

            case ClientMessage.QUEUE_LEAVE -> {
                boolean removed = matchmaking.dequeue(userId);
                connection.send(removed
                        ? ServerMessage.queueLeft("LEFT")
                        : ServerMessage.error(ErrorCodes.NOT_QUEUED,
                                "You are not in the matchmaking queue"));
            }

            case ClientMessage.FIRE -> {
                if (!presence.allow("fire", userId, 30, 10)) {
                    connection.send(ServerMessage.error(ErrorCodes.RATE_LIMITED,
                            "Too many shots too quickly"));
                    return;
                }
                Optional<GameSession> session = matchService.byUser(userId);
                if (session.isEmpty()) {
                    connection.send(ServerMessage.error(ErrorCodes.NOT_IN_MATCH,
                            "You are not in a match"));
                    return;
                }
                String error = session.get().handleFire(userId, message.row(), message.col());
                if (error != null) {
                    connection.send(ServerMessage.error(error, describe(error)));
                }
            }

            case ClientMessage.CHAT -> {
                if (!message.hasText()) {
                    connection.send(ServerMessage.error(ErrorCodes.EMPTY_MESSAGE,
                            "Message was empty"));
                    return;
                }
                if (!presence.allow("chat", userId, 10, 5)) {
                    connection.send(ServerMessage.error(ErrorCodes.RATE_LIMITED,
                            "Too many messages — wait a moment"));
                    return;
                }
                matchService.byUser(userId).ifPresentOrElse(
                        session -> session.handleChat(userId, message.text()),
                        () -> connection.send(ServerMessage.error(ErrorCodes.NOT_IN_MATCH,
                                "You are not in a match")));
            }

            case ClientMessage.FORFEIT -> matchService.byUser(userId)
                    .ifPresent(session -> session.handleForfeit(userId));

            default -> connection.send(ServerMessage.error(ErrorCodes.UNKNOWN_COMMAND,
                    "Unknown command: " + message.type()));
        }
    }

    private void handleQueueJoin(WebSocketConnection connection) {
        long userId = connection.userId();

        if (matchService.byUser(userId).filter(s -> !s.isFinished()).isPresent()) {
            connection.send(ServerMessage.error(ErrorCodes.ALREADY_IN_MATCH,
                    "Finish or forfeit your current match first"));
            return;
        }

        MatchmakingService.EnqueueResult result = matchmaking.enqueue(userId);
        switch (result.status()) {
            case QUEUED -> {
                connection.send(ServerMessage.queueJoined(result.queueSize(),
                        result.queuedAtMillis()));
                connection.send(ServerMessage.queueStatus(result.queueSize(),
                        presence.onlineCount()));
            }
            case ALREADY_QUEUED -> connection.send(ServerMessage.error(
                    ErrorCodes.ALREADY_QUEUED, "You are already in the queue"));
            case IN_MATCH -> connection.send(ServerMessage.error(
                    ErrorCodes.ALREADY_IN_MATCH, "You already have a match in progress"));
            case UNAVAILABLE -> connection.send(ServerMessage.error(
                    ErrorCodes.MATCHMAKING_DOWN,
                    "Matchmaking is temporarily unavailable"));
        }
    }

    private void teardown(WebSocketConnection connection) {
        long userId = connection.userId();
        connections.unregister(connection);

        // If this socket was displaced by a newer one for the same account, that
        // newer socket is still live: the user is neither offline nor out of the
        // queue, and saying otherwise would corrupt both.
        boolean stillConnected = connections.find(userId).isPresent();
        if (!stillConnected) {
            presence.markOffline(userId);
            matchmaking.dequeue(userId);
        }

        // Hand the session the exact socket that died, so a connection that has
        // already been replaced by a newer tab does not evict its replacement.
        matchService.byUser(userId).ifPresent(session -> session.detach(connection));
        connection.close(1001, "connection closed");
        log.info("Disconnected " + connection + " (" + connections.size() + " live)");
    }

    private static String describe(String errorCode) {
        return switch (errorCode) {
            case ErrorCodes.NOT_YOUR_TURN    -> "It is not your turn";
            case ErrorCodes.OUT_OF_BOUNDS    -> "That cell is off the board";
            case ErrorCodes.ALREADY_TARGETED -> "You have already fired at that cell";
            case ErrorCodes.GAME_NOT_ACTIVE  -> "This match is no longer active";
            case ErrorCodes.NOT_IN_MATCH     -> "You are not in a match";
            default                          -> errorCode;
        };
    }
}
