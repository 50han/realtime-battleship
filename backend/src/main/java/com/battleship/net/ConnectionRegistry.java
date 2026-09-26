package com.battleship.net;

import com.battleship.protocol.ErrorCodes;
import com.battleship.protocol.ServerMessage;
import com.battleship.util.Log;
import com.battleship.util.Threads;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Every live authenticated socket on this instance, indexed by user id.
 *
 * A {@link ConcurrentHashMap} rather than a synchronized map: connection
 * lookups happen on every inbound message from every game, so this must not
 * become a single contended lock in front of all gameplay. Nothing here ever
 * acquires a game lock, which keeps it safely below sessions in the (empty)
 * lock hierarchy.
 */
public final class ConnectionRegistry implements AutoCloseable {

    private static final Log log = Log.of(ConnectionRegistry.class);
    private static final long HEARTBEAT_SECONDS = 25;

    private final Map<Long, WebSocketConnection> byUser = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(Threads.named("heartbeat", true));

    public ConnectionRegistry() {
        heartbeat.scheduleAtFixedRate(this::pingAll,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Registers a connection, displacing any previous socket for the same user.
     *
     * @return the displaced connection, if there was one
     */
    public Optional<WebSocketConnection> register(WebSocketConnection connection) {
        WebSocketConnection previous = byUser.put(connection.userId(), connection);
        if (previous != null && previous != connection) {
            previous.send(ServerMessage.error(ErrorCodes.SESSION_REPLACED,
                    "You connected from another window"));
            previous.close(1000, "session replaced");
            return Optional.of(previous);
        }
        return Optional.empty();
    }

    /** Removes a connection only if it is still the registered one for that user. */
    public void unregister(WebSocketConnection connection) {
        byUser.remove(connection.userId(), connection);
    }

    public Optional<WebSocketConnection> find(long userId) {
        return Optional.ofNullable(byUser.get(userId));
    }

    public boolean send(long userId, String payload) {
        WebSocketConnection connection = byUser.get(userId);
        return connection != null && connection.send(payload);
    }

    public int size() { return byUser.size(); }

    public Collection<Long> userIds() { return List.copyOf(byUser.keySet()); }

    /**
     * Sends a WebSocket ping to every client. Browsers answer automatically, so
     * a peer that has vanished without a TCP FIN eventually surfaces as a write
     * failure instead of lingering as a phantom connection.
     */
    private void pingAll() {
        for (WebSocketConnection connection : byUser.values()) {
            try {
                if (connection.isOpen()) connection.ping();
                else unregister(connection);
            } catch (RuntimeException e) {
                log.warn("Heartbeat failed for " + connection + ": " + e.getMessage());
            }
        }
    }

    public void closeAll(String reason) {
        for (WebSocketConnection connection : byUser.values()) {
            connection.send(ServerMessage.error(ErrorCodes.SERVER_SHUTDOWN, reason));
            connection.close(1001, "server shutting down");
        }
        byUser.clear();
    }

    @Override
    public void close() {
        heartbeat.shutdownNow();
    }
}
