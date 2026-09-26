package com.battleship.api;

import com.battleship.auth.AuthException;
import com.battleship.auth.AuthService;
import com.battleship.auth.AuthenticatedUser;
import com.battleship.auth.JwtService;
import com.battleship.config.AppConfig;
import com.battleship.db.LeaderboardEntry;
import com.battleship.db.MatchRecord;
import com.battleship.db.MatchRepository;
import com.battleship.db.MoveRow;
import com.battleship.db.User;
import com.battleship.db.UserRepository;
import com.battleship.matchmaking.MatchService;
import com.battleship.matchmaking.MatchmakingService;
import com.battleship.net.ConnectionRegistry;
import com.battleship.net.HttpRequest;
import com.battleship.net.HttpResponse;
import com.battleship.protocol.Json;
import com.battleship.redis.PresenceService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The HTTP surface: accounts, match history, leaderboard, health.
 *
 * Gameplay never comes through here — it is all WebSocket. These endpoints
 * exist so the client can log in, and so history and standings survive a
 * server restart, which is the whole reason PostgreSQL is in the stack.
 */
public final class RestApi {

    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final int MAX_HISTORY_LIMIT     = 100;

    private final AppConfig config;
    private final AuthService auth;
    private final JwtService jwt;
    private final UserRepository users;
    private final MatchRepository matches;
    private final MatchService matchService;
    private final MatchmakingService matchmaking;
    private final ConnectionRegistry connections;
    private final PresenceService presence;

    public RestApi(AppConfig config, AuthService auth, JwtService jwt,
                   UserRepository users, MatchRepository matches,
                   MatchService matchService, MatchmakingService matchmaking,
                   ConnectionRegistry connections, PresenceService presence) {
        this.config       = config;
        this.auth         = auth;
        this.jwt          = jwt;
        this.users        = users;
        this.matches      = matches;
        this.matchService = matchService;
        this.matchmaking  = matchmaking;
        this.connections  = connections;
        this.presence     = presence;
    }

    public Router routes() {
        return new Router(config)
                .get("/api/health",        this::health)
                .get("/api/stats",         this::stats)
                .post("/api/auth/register", this::register)
                .post("/api/auth/login",    this::login)
                .get("/api/me",            this::me)
                .get("/api/matches",       this::matchHistory)
                .getPrefixed("/api/matches/", this::matchDetail)
                .get("/api/leaderboard",   this::leaderboard);
    }

    // ================================================================
    // Handlers
    // ================================================================

    private HttpResponse health(HttpRequest request) {
        ObjectNode node = Json.object();
        node.put("status", "ok");
        node.put("instance", config.instanceId());
        node.put("env", config.env());
        node.put("time", Instant.now().toString());
        return HttpResponse.json(200, Json.write(node));
    }

    private HttpResponse stats(HttpRequest request) {
        ObjectNode node = Json.object();
        node.put("instance", config.instanceId());
        node.put("liveConnections", connections.size());
        node.put("liveMatches", matchService.liveMatchCount());
        node.put("queueSize", matchmaking.queueSize());
        node.put("onlinePlayers", presence.onlineCount());
        return HttpResponse.json(200, Json.write(node));
    }

    private HttpResponse register(HttpRequest request) {
        ObjectNode body = requireJsonBody(request);
        String username = requireText(body, "username");
        char[] password = requirePassword(body);
        AuthService.Session session = auth.register(username, password);
        return HttpResponse.json(201, Json.write(sessionNode(session)));
    }

    private HttpResponse login(HttpRequest request) {
        ObjectNode body = requireJsonBody(request);
        String username = requireText(body, "username");
        char[] password = requirePassword(body);
        AuthService.Session session = auth.login(username, password);
        return HttpResponse.json(200, Json.write(sessionNode(session)));
    }

    private HttpResponse me(HttpRequest request) {
        AuthenticatedUser identity = requireAuth(request);
        User user = users.findById(identity.userId())
                .orElseThrow(() -> AuthException.unauthorized("Account no longer exists"));

        ObjectNode node = userNode(user);
        node.put("inMatch", matchService.byUser(user.id())
                .filter(session -> !session.isFinished()).isPresent());
        return HttpResponse.json(200, Json.write(node));
    }

    private HttpResponse matchHistory(HttpRequest request) {
        AuthenticatedUser identity = requireAuth(request);
        int limit = clampedLimit(request);

        List<MatchRecord> history = matches.historyForUser(identity.userId(), limit);
        ArrayNode items = Json.array();
        for (MatchRecord record : history) {
            items.add(matchNode(record, identity.userId()));
        }
        ObjectNode node = Json.object();
        node.set("matches", items);
        node.put("count", items.size());
        return HttpResponse.json(200, Json.write(node));
    }

    private HttpResponse matchDetail(HttpRequest request) {
        AuthenticatedUser identity = requireAuth(request);
        String idText = request.path().substring("/api/matches/".length());

        UUID matchId;
        try {
            matchId = UUID.fromString(idText);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid match id: " + idText);
        }

        Optional<MatchRecord> found = matches.findById(matchId);
        if (found.isEmpty()) {
            return Router.error(404, "NOT_FOUND", "No such match");
        }
        MatchRecord record = found.get();

        // Match detail includes the full move log, so it is restricted to
        // the two participants.
        if (record.player1Id() != identity.userId() && record.player2Id() != identity.userId()) {
            return Router.error(403, "FORBIDDEN", "That match is not yours");
        }

        ObjectNode node = matchNode(record, identity.userId());
        ArrayNode moves = Json.array();
        for (MoveRow move : matches.movesForMatch(matchId)) {
            ObjectNode m = Json.object();
            m.put("moveNo", move.moveNo());
            m.put("shooterId", move.shooterId());
            m.put("row", move.row());
            m.put("col", move.col());
            m.put("hit", move.hit());
            if (move.sunkShip() == null) m.putNull("sunkShip");
            else m.put("sunkShip", move.sunkShip());
            moves.add(m);
        }
        node.set("moves", moves);
        return HttpResponse.json(200, Json.write(node));
    }

    private HttpResponse leaderboard(HttpRequest request) {
        int limit = clampedLimit(request);
        ArrayNode items = Json.array();
        int rank = 1;
        for (LeaderboardEntry entry : users.leaderboard(limit)) {
            ObjectNode node = Json.object();
            node.put("rank", rank++);
            node.put("userId", entry.userId());
            node.put("username", entry.username());
            node.put("wins", entry.wins());
            node.put("losses", entry.losses());
            node.put("gamesPlayed", entry.gamesPlayed());
            node.put("winRate", entry.winRate());
            items.add(node);
        }
        ObjectNode node = Json.object();
        node.set("leaderboard", items);
        return HttpResponse.json(200, Json.write(node));
    }

    // ================================================================
    // Shared helpers
    // ================================================================

    private AuthenticatedUser requireAuth(HttpRequest request) {
        return request.header("authorization")
                .flatMap(JwtService::bearerToken)
                .flatMap(jwt::verify)
                .orElseThrow(() -> AuthException.unauthorized("A valid bearer token is required"));
    }

    private static ObjectNode requireJsonBody(HttpRequest request) {
        ObjectNode body = Json.parseObject(request.bodyAsString());
        if (body == null) throw AuthException.badRequest("Expected a JSON object body");
        return body;
    }

    private static String requireText(ObjectNode body, String field) {
        var value = body.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw AuthException.badRequest("Field '" + field + "' is required");
        }
        return value.asText().trim();
    }

    private static char[] requirePassword(ObjectNode body) {
        var value = body.get("password");
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw AuthException.badRequest("Field 'password' is required");
        }
        return value.asText().toCharArray();
    }

    private static int clampedLimit(HttpRequest request) {
        return request.query("limit")
                .map(raw -> {
                    try {
                        return Integer.parseInt(raw.trim());
                    } catch (NumberFormatException e) {
                        return DEFAULT_HISTORY_LIMIT;
                    }
                })
                .map(limit -> Math.max(1, Math.min(MAX_HISTORY_LIMIT, limit)))
                .orElse(DEFAULT_HISTORY_LIMIT);
    }

    private ObjectNode sessionNode(AuthService.Session session) {
        ObjectNode node = Json.object();
        node.put("token", session.token().token());
        node.put("expiresAt", session.token().expiresAt().toEpochMilli());
        node.put("expiresIn", session.token().expiresInSeconds());
        node.set("user", userNode(session.user()));
        return node;
    }

    private static ObjectNode userNode(User user) {
        ObjectNode node = Json.object();
        node.put("id", user.id());
        node.put("username", user.username());
        node.put("wins", user.wins());
        node.put("losses", user.losses());
        node.put("gamesPlayed", user.gamesPlayed());
        node.put("createdAt", user.createdAt().toEpochMilli());
        return node;
    }

    /** Match history shaped from the requesting player's point of view. */
    private static ObjectNode matchNode(MatchRecord record, long viewerId) {
        boolean viewerIsPlayer1 = record.player1Id() == viewerId;
        long opponentId    = viewerIsPlayer1 ? record.player2Id()   : record.player1Id();
        String opponentName = viewerIsPlayer1 ? record.player2Name() : record.player1Name();

        String result;
        if (record.winnerId() == null)              result = "UNFINISHED";
        else if (record.winnerId() == viewerId)     result = "WIN";
        else                                        result = "LOSS";

        ObjectNode node = Json.object();
        node.put("matchId", record.id().toString());
        node.put("opponentId", opponentId);
        node.put("opponentName", opponentName);
        node.put("result", result);
        node.put("status", record.status());
        node.put("totalShots", record.totalShots());
        node.put("startedAt", record.startedAt().toEpochMilli());
        if (record.endedAt() == null) node.putNull("endedAt");
        else node.put("endedAt", record.endedAt().toEpochMilli());
        node.put("durationSeconds", record.endedAt() == null ? -1
                : record.endedAt().getEpochSecond() - record.startedAt().getEpochSecond());
        return node;
    }
}
