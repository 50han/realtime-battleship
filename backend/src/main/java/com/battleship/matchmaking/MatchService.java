package com.battleship.matchmaking;

import com.battleship.config.AppConfig;
import com.battleship.db.MatchRepository;
import com.battleship.db.MoveRow;
import com.battleship.db.UserRepository;
import com.battleship.game.GameSession;
import com.battleship.game.MatchLifecycle;
import com.battleship.net.ConnectionRegistry;
import com.battleship.net.WebSocketConnection;
import com.battleship.redis.LuaScripts;
import com.battleship.redis.RedisClient;
import com.battleship.redis.RedisKeys;
import com.battleship.util.Log;
import com.battleship.util.Threads;
import redis.clients.jedis.params.SetParams;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns every live {@link GameSession} on this instance and all of their
 * side effects.
 *
 * This is the {@link MatchLifecycle} implementation, so it is where the "no
 * blocking I/O while a game lock is held" rule is actually honoured: each
 * callback either hands work to {@link #persistence} or to
 * {@link MovePersistenceWriter} and returns immediately.
 */
public final class MatchService implements MatchLifecycle, AutoCloseable {

    private static final Log log = Log.of(MatchService.class);

    /** How long a match's Redis reconnect snapshot survives without updates. */
    private static final int SNAPSHOT_TTL_SECONDS = 3600;

    private final Map<UUID, GameSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, UUID> matchByUser = new ConcurrentHashMap<>();

    private final AppConfig config;
    private final MatchRepository matches;
    private final UserRepository users;
    private final RedisClient redis;
    private final ConnectionRegistry connections;
    private final MovePersistenceWriter moveWriter;

    /** Turn timers and reconnect grace timers for every match on this instance. */
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, Threads.named("game-timer", true));

    /** Result persistence and Redis snapshotting, kept off the game threads. */
    private final ExecutorService persistence =
            Executors.newFixedThreadPool(4, Threads.named("persist", true));

    public MatchService(AppConfig config,
                        MatchRepository matches,
                        UserRepository users,
                        RedisClient redis,
                        ConnectionRegistry connections) {
        this.config      = config;
        this.matches     = matches;
        this.users       = users;
        this.redis       = redis;
        this.connections = connections;
        this.moveWriter  = new MovePersistenceWriter(matches);
        this.moveWriter.start();
    }

    // ================================================================
    // Creating and finding sessions
    // ================================================================

    /**
     * Creates the authoritative session for a freshly paired match.
     *
     * Called on the matchmaking dispatcher thread. The database row is written
     * first so the move log always has a parent to reference.
     *
     * The session is registered and both sockets are bound, but play does not
     * begin until {@link GameSession#start()} is called — the caller announces
     * the pairing first, so clients always see MATCH_FOUND before GAME_START.
     */
    public GameSession createMatch(UUID matchId,
                                   GameSession.Player player1,
                                   GameSession.Player player2) {
        matches.createMatch(matchId, player1.userId(), player2.userId());

        GameSession session = GameSession.create(matchId, player1, player2,
                scheduler, this,
                config.turnTimeoutSeconds(), config.reconnectGraceSeconds());

        sessions.put(matchId, session);
        matchByUser.put(player1.userId(), matchId);
        matchByUser.put(player2.userId(), matchId);

        connections.find(player1.userId()).ifPresent(session::attach);
        connections.find(player2.userId()).ifPresent(session::attach);

        log.info("Created match " + matchId + ": " + player1.username()
                + " vs " + player2.username());
        return session;
    }

    public Optional<GameSession> byMatchId(UUID matchId) {
        return Optional.ofNullable(sessions.get(matchId));
    }

    public Optional<GameSession> byUser(long userId) {
        UUID matchId = matchByUser.get(userId);
        return matchId == null ? Optional.empty() : Optional.ofNullable(sessions.get(matchId));
    }

    public int liveMatchCount() { return sessions.size(); }

    /**
     * Reattaches a reconnecting client to its match.
     *
     * Three cases, in order: the session is live here; the session belongs to
     * another instance (the caller is told where to reconnect); or this
     * instance owns a match it has forgotten after a restart, in which case the
     * Redis snapshot is replayed into a fresh session.
     *
     * @return the outcome, so the caller can send the right message
     */
    public Reattachment reattach(WebSocketConnection connection) {
        long userId = connection.userId();

        Optional<GameSession> local = byUser(userId);
        if (local.isPresent() && !local.get().isFinished()) {
            return local.get().attach(connection)
                    ? Reattachment.attached(local.get())
                    : Reattachment.none();
        }

        String matchIdText;
        Map<String, String> owner;
        try {
            matchIdText = redis.with(jedis -> jedis.get(RedisKeys.userMatch(userId)));
            if (matchIdText == null) return Reattachment.none();
            owner = redis.with(jedis -> jedis.hgetAll(RedisKeys.matchOwner(matchIdText)));
        } catch (RuntimeException e) {
            log.warn("Could not check for an active match for user " + userId
                    + ": " + e.getMessage());
            return Reattachment.none();
        }

        String ownerInstance = owner.get("instance");
        String ownerUrl      = owner.getOrDefault("url", config.advertisedWsUrl());

        if (ownerInstance != null && !ownerInstance.equals(config.instanceId())) {
            return Reattachment.elsewhere(matchIdText, ownerUrl);
        }

        // We own it but do not have it in memory: restore from the snapshot.
        return restoreFromSnapshot(matchIdText, connection);
    }

    private Reattachment restoreFromSnapshot(String matchIdText,
                                             WebSocketConnection connection) {
        String snapshot;
        try {
            snapshot = redis.with(jedis -> jedis.get(RedisKeys.matchState(matchIdText)));
        } catch (RuntimeException e) {
            log.warn("Could not read snapshot for match " + matchIdText + ": " + e.getMessage());
            return Reattachment.none();
        }
        if (snapshot == null) {
            log.warn("Active-match pointer for " + connection
                    + " references match " + matchIdText + " with no snapshot; clearing");
            clearUserPointer(connection.userId(), matchIdText);
            return Reattachment.none();
        }

        UUID matchId = UUID.fromString(matchIdText);
        GameSession restored;
        try {
            restored = GameSession.fromSnapshot(snapshot, scheduler, this,
                    config.turnTimeoutSeconds(), config.reconnectGraceSeconds());
        } catch (RuntimeException e) {
            log.error("Corrupt snapshot for match " + matchIdText, e);
            clearUserPointer(connection.userId(), matchIdText);
            return Reattachment.none();
        }

        // computeIfAbsent, so two players reconnecting at once cannot each
        // build their own copy of the same match.
        GameSession session = sessions.computeIfAbsent(matchId, id -> {
            matchByUser.put(restored.player(1).userId(), id);
            matchByUser.put(restored.player(2).userId(), id);
            log.info("Restored match " + id + " from Redis snapshot");
            return restored;
        });

        return session.attach(connection)
                ? Reattachment.attached(session)
                : Reattachment.none();
    }

    // ================================================================
    // MatchLifecycle
    // ================================================================

    @Override
    public void onMove(MoveRow move) {
        moveWriter.submit(move);
    }

    @Override
    public void onStateChanged(GameSession session) {
        // Snapshot on the persistence pool: serializing the board and writing
        // it to Redis must not sit on a game thread.
        persistence.execute(() -> {
            try {
                String json = session.snapshotJson();
                redis.run(jedis -> jedis.set(
                        RedisKeys.matchState(session.matchIdText()),
                        json,
                        SetParams.setParams().ex(SNAPSHOT_TTL_SECONDS)));
            } catch (RuntimeException e) {
                log.warn("Could not snapshot match " + session.matchId()
                        + ": " + e.getMessage());
            }
        });
    }

    @Override
    public void onMatchFinished(GameSession session, Long winnerUserId, Long loserUserId,
                                String status, int totalShots) {
        UUID matchId = session.matchId();

        // Drop the in-memory registration straight away so a reconnect after
        // the final shot is not handed a finished session.
        sessions.remove(matchId, session);
        matchByUser.remove(session.player(1).userId(), matchId);
        matchByUser.remove(session.player(2).userId(), matchId);

        persistence.execute(() -> {
            try {
                // finishMatch is the idempotency gate: only the call that
                // actually closed the row is allowed to adjust win/loss counts,
                // so a disconnect racing the winning shot cannot double-count.
                boolean weFinishedIt = matches.finishMatch(matchId, winnerUserId,
                        status, totalShots);
                if (weFinishedIt && winnerUserId != null && loserUserId != null) {
                    users.recordResult(winnerUserId, loserUserId);
                }
            } catch (RuntimeException e) {
                log.error("Could not persist result for match " + matchId, e);
            }
            releaseMatchmakingState(matchId.toString(),
                    session.player(1).userId(), session.player(2).userId());
        });
    }

    // ================================================================
    // Redis cleanup
    // ================================================================

    private void releaseMatchmakingState(String matchIdText, long userA, long userB) {
        try {
            redis.eval(LuaScripts.RELEASE, List.of(),
                    List.of(matchIdText, Long.toString(userA), Long.toString(userB)));
        } catch (RuntimeException e) {
            log.warn("Could not release matchmaking state for " + matchIdText
                    + ": " + e.getMessage());
        }
    }

    private void clearUserPointer(long userId, String matchIdText) {
        try {
            redis.run(jedis -> {
                String current = jedis.get(RedisKeys.userMatch(userId));
                if (matchIdText.equals(current)) {
                    jedis.del(RedisKeys.userMatch(userId));
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not clear active-match pointer for user " + userId
                    + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        for (GameSession session : sessions.values()) {
            session.abortForShutdown();
        }
        scheduler.shutdownNow();
        moveWriter.close();
        persistence.shutdown();
        try {
            if (!persistence.awaitTermination(10, TimeUnit.SECONDS)) {
                persistence.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            persistence.shutdownNow();
        }
    }

    /** Result of a reconnect attempt. */
    public record Reattachment(Kind kind, GameSession session,
                               String matchId, String serverUrl) {

        public enum Kind { NONE, ATTACHED, ELSEWHERE }

        static Reattachment none() {
            return new Reattachment(Kind.NONE, null, null, null);
        }
        static Reattachment attached(GameSession session) {
            return new Reattachment(Kind.ATTACHED, session, session.matchIdText(), null);
        }
        static Reattachment elsewhere(String matchId, String serverUrl) {
            return new Reattachment(Kind.ELSEWHERE, null, matchId, serverUrl);
        }

        public boolean isAttached() { return kind == Kind.ATTACHED; }
    }
}
