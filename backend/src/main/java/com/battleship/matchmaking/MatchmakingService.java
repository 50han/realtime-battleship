package com.battleship.matchmaking;

import com.battleship.config.AppConfig;
import com.battleship.db.User;
import com.battleship.db.UserRepository;
import com.battleship.game.GameSession;
import com.battleship.net.ConnectionRegistry;
import com.battleship.protocol.Json;
import com.battleship.protocol.ServerMessage;
import com.battleship.redis.LuaScripts;
import com.battleship.redis.RedisClient;
import com.battleship.redis.RedisKeys;
import com.battleship.util.Log;
import com.battleship.util.Threads;
import com.fasterxml.jackson.databind.node.ObjectNode;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The Redis-backed matchmaking queue.
 *
 * <h2>Why this cannot deadlock</h2>
 * Pairing two waiting players touches several keys at once: the queue, both
 * tickets, both active-match pointers and the ownership hash. Doing that from
 * Java would mean either holding a distributed lock across all of them, or a
 * WATCH/MULTI loop that retries under contention — the first risks deadlock if
 * two callers ever take keys in different orders, the second livelocks when the
 * queue is hot.
 *
 * Instead the whole operation is one Lua script. Redis executes a script
 * atomically on its single command thread, so the multi-key update is one
 * indivisible step: there is no intermediate state for another caller to
 * observe, no lock to acquire, and therefore no lock ordering to get wrong.
 * Several server instances can run the pairing loop concurrently and at most
 * one of them will be handed any given pair.
 *
 * <h2>Cross-instance delivery</h2>
 * The instance whose script call won the pair owns the authoritative
 * {@link GameSession}. It publishes the pairing on a Redis Pub/Sub channel;
 * every instance forwards MATCH_FOUND to whichever of the two players is
 * connected to it, including the URL of the owning instance so a remote client
 * knows to reconnect there.
 */
public final class MatchmakingService implements AutoCloseable {

    private static final Log log = Log.of(MatchmakingService.class);

    private static final int  TICKET_TTL_SECONDS       = 600;
    private static final int  ACTIVE_MATCH_TTL_SECONDS = 3600;
    /** Fallback poll interval; enqueues also wake the dispatcher immediately. */
    private static final long DISPATCH_POLL_MILLIS     = 500;

    private final AppConfig config;
    private final RedisClient redis;
    private final UserRepository users;
    private final ConnectionRegistry connections;
    private final MatchService matchService;

    /** Wake-ups for the dispatcher; the payload itself is irrelevant. */
    private final LinkedBlockingQueue<Object> wakeups = new LinkedBlockingQueue<>();

    private final ExecutorService dispatcher =
            Executors.newSingleThreadExecutor(Threads.named("matchmaker", true));
    private final ExecutorService subscriberPool =
            Executors.newSingleThreadExecutor(Threads.named("mm-subscriber", true));

    private volatile boolean running = true;
    private volatile Jedis subscriberConnection;
    private volatile JedisPubSub subscription;

    public MatchmakingService(AppConfig config,
                              RedisClient redis,
                              UserRepository users,
                              ConnectionRegistry connections,
                              MatchService matchService) {
        this.config       = config;
        this.redis        = redis;
        this.users        = users;
        this.connections  = connections;
        this.matchService = matchService;
    }

    public void start() {
        dispatcher.execute(this::dispatchLoop);
        subscriberPool.execute(this::subscribeLoop);
        log.info("Matchmaking started on instance " + config.instanceId());
    }

    // ================================================================
    // Queue operations (called from connection threads)
    // ================================================================

    /** Adds a player to the queue. Never blocks on anything but Redis. */
    public EnqueueResult enqueue(long userId) {
        long now = System.currentTimeMillis();
        Object raw;
        try {
            raw = redis.eval(LuaScripts.ENQUEUE,
                    List.of(RedisKeys.QUEUE, RedisKeys.ticket(userId), RedisKeys.userMatch(userId)),
                    List.of(Long.toString(userId), Long.toString(now),
                            config.instanceId(), Integer.toString(TICKET_TTL_SECONDS)));
        } catch (RuntimeException e) {
            log.error("Enqueue failed for user " + userId, e);
            return new EnqueueResult(EnqueueResult.Status.UNAVAILABLE, 0, now);
        }

        List<?> reply = (List<?>) raw;
        String status = asString(reply.get(0));
        int queueSize = Integer.parseInt(asString(reply.get(1)));

        EnqueueResult.Status parsed = switch (status) {
            case "QUEUED"         -> EnqueueResult.Status.QUEUED;
            case "ALREADY_QUEUED" -> EnqueueResult.Status.ALREADY_QUEUED;
            case "IN_MATCH"       -> EnqueueResult.Status.IN_MATCH;
            default               -> EnqueueResult.Status.UNAVAILABLE;
        };

        if (parsed == EnqueueResult.Status.QUEUED) {
            wakeups.offer(Boolean.TRUE);   // try pairing right away
        }
        return new EnqueueResult(parsed, queueSize, now);
    }

    /** Removes a player from the queue. Safe to call when they are not queued. */
    public boolean dequeue(long userId) {
        try {
            Object raw = redis.eval(LuaScripts.DEQUEUE,
                    List.of(RedisKeys.QUEUE, RedisKeys.ticket(userId)),
                    List.of(Long.toString(userId)));
            return raw instanceof Long removed && removed > 0;
        } catch (RuntimeException e) {
            log.warn("Dequeue failed for user " + userId + ": " + e.getMessage());
            return false;
        }
    }

    public int queueSize() {
        try {
            return redis.with(jedis -> jedis.zcard(RedisKeys.QUEUE)).intValue();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ================================================================
    // Dispatcher: drains the queue two players at a time
    // ================================================================

    private void dispatchLoop() {
        log.info("Matchmaking dispatcher running");
        while (running) {
            try {
                // Wait for a signal, but poll anyway so a pair left behind by
                // another instance's crash still gets picked up.
                wakeups.poll(DISPATCH_POLL_MILLIS, TimeUnit.MILLISECONDS);
                wakeups.clear();
                while (running && pairOnce()) {
                    // Keep going while the queue still yields pairs.
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.error("Matchmaking dispatch iteration failed", e);
                sleepQuietly(1000);
            }
        }
        log.info("Matchmaking dispatcher stopped");
    }

    /** @return true if a pair was formed, false if fewer than two were waiting */
    private boolean pairOnce() {
        UUID matchId = UUID.randomUUID();
        Object raw;
        try {
            raw = redis.eval(LuaScripts.PAIR,
                    List.of(RedisKeys.QUEUE),
                    List.of(matchId.toString(),
                            config.instanceId(),
                            Integer.toString(ACTIVE_MATCH_TTL_SECONDS),
                            config.advertisedWsUrl()));
        } catch (RuntimeException e) {
            log.error("Pairing script failed", e);
            sleepQuietly(500);
            return false;
        }
        if (raw == null) return false;

        List<?> reply = (List<?>) raw;
        long userA = Long.parseLong(asString(reply.get(0)));
        long userB = Long.parseLong(asString(reply.get(2)));

        Optional<User> a = users.findById(userA);
        Optional<User> b = users.findById(userB);
        if (a.isEmpty() || b.isEmpty()) {
            log.warn("Paired a user that no longer exists (" + userA + ", " + userB
                    + "); discarding match " + matchId);
            return true;   // the queue moved forward, so keep draining
        }

        GameSession.Player player1 = new GameSession.Player(userA, a.get().username(), 1);
        GameSession.Player player2 = new GameSession.Player(userB, b.get().username(), 2);

        GameSession session = matchService.createMatch(matchId, player1, player2);
        // MATCH_FOUND first, then GAME_START: the client treats MATCH_FOUND as
        // the start of a new match and resets its boards on it, so it must not
        // arrive after the board it is meant to precede.
        publishMatch(matchId, player1, player2);
        session.start();
        return true;
    }

    // ================================================================
    // Pub/Sub: tell both players, wherever they are connected
    // ================================================================

    private void publishMatch(UUID matchId, GameSession.Player p1, GameSession.Player p2) {
        ObjectNode event = Json.object();
        event.put("type", "MATCH_FOUND");
        event.put("matchId", matchId.toString());
        event.put("ownerInstance", config.instanceId());
        event.put("ownerUrl", config.advertisedWsUrl());
        event.set("p1", playerNode(p1));
        event.set("p2", playerNode(p2));
        String payload = Json.write(event);

        // Deliver locally first: publish is a network hop, and a player on this
        // instance should not wait on it.
        deliverMatchFound(matchId.toString(), config.instanceId(),
                config.advertisedWsUrl(), p1, p2);
        try {
            redis.run(jedis -> jedis.publish(RedisKeys.EVENTS_CHANNEL, payload));
        } catch (RuntimeException e) {
            log.warn("Could not publish match " + matchId + ": " + e.getMessage());
        }
    }

    private static ObjectNode playerNode(GameSession.Player player) {
        ObjectNode node = Json.object();
        node.put("userId", player.userId());
        node.put("username", player.username());
        node.put("slot", player.slot());
        return node;
    }

    private void subscribeLoop() {
        while (running) {
            try (Jedis jedis = redis.dedicatedConnection()) {
                subscriberConnection = jedis;
                subscription = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleEvent(message);
                    }
                };
                log.info("Subscribed to " + RedisKeys.EVENTS_CHANNEL);
                jedis.subscribe(subscription, RedisKeys.EVENTS_CHANNEL);   // blocks
            } catch (RuntimeException e) {
                if (running) {
                    log.warn("Pub/Sub subscription dropped: " + e.getMessage()
                            + "; retrying in 2s");
                    sleepQuietly(2000);
                }
            }
        }
    }

    private void handleEvent(String message) {
        ObjectNode event = Json.parseObject(message);
        if (event == null || !"MATCH_FOUND".equals(event.path("type").asText())) return;

        String ownerInstance = event.path("ownerInstance").asText();
        if (config.instanceId().equals(ownerInstance)) {
            return;   // already delivered locally by publishMatch
        }

        String matchId = event.path("matchId").asText();
        String ownerUrl = event.path("ownerUrl").asText(config.advertisedWsUrl());
        GameSession.Player p1 = readPlayer(event, "p1");
        GameSession.Player p2 = readPlayer(event, "p2");
        if (p1 == null || p2 == null) return;

        deliverMatchFound(matchId, ownerInstance, ownerUrl, p1, p2);
    }

    private static GameSession.Player readPlayer(ObjectNode event, String field) {
        if (!(event.get(field) instanceof ObjectNode node)) return null;
        return new GameSession.Player(node.path("userId").asLong(),
                node.path("username").asText(), node.path("slot").asInt());
    }

    /**
     * Sends MATCH_FOUND to whichever of the two players is connected here.
     *
     * When this instance does not own the match, the client is told to
     * reconnect to the owner's URL — sticky routing without needing a
     * session-aware load balancer.
     */
    private void deliverMatchFound(String matchId, String ownerInstance, String ownerUrl,
                                   GameSession.Player p1, GameSession.Player p2) {
        boolean weOwnIt = config.instanceId().equals(ownerInstance);
        notifyOne(matchId, p1, p2, ownerUrl, !weOwnIt);
        notifyOne(matchId, p2, p1, ownerUrl, !weOwnIt);
    }

    private void notifyOne(String matchId, GameSession.Player self, GameSession.Player opponent,
                           String ownerUrl, boolean reconnectRequired) {
        connections.find(self.userId()).ifPresent(connection ->
                connection.send(ServerMessage.matchFound(matchId, self.slot(),
                        opponent.userId(), opponent.username(),
                        ownerUrl, reconnectRequired)));
    }

    // ================================================================

    private static String asString(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return String.valueOf(value);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            JedisPubSub sub = subscription;
            if (sub != null && sub.isSubscribed()) sub.unsubscribe();
        } catch (RuntimeException ignored) {
            // Already gone; the dedicated connection is closed below.
        }
        Jedis connection = subscriberConnection;
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException ignored) {
                // Best effort during shutdown.
            }
        }
        dispatcher.shutdownNow();
        subscriberPool.shutdownNow();
    }

    /** Outcome of an enqueue attempt. */
    public record EnqueueResult(Status status, int queueSize, long queuedAtMillis) {
        public enum Status { QUEUED, ALREADY_QUEUED, IN_MATCH, UNAVAILABLE }
    }
}
