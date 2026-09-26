package com.battleship.redis;

import com.battleship.util.Log;

/**
 * Online-player presence and per-user rate limiting, both in Redis so the
 * numbers stay correct with several server instances behind a load balancer.
 *
 * Every method degrades gracefully: if Redis is briefly unavailable, presence
 * counts go stale and rate limits fail open rather than taking gameplay down.
 */
public final class PresenceService {

    private static final Log log = Log.of(PresenceService.class);

    private final RedisClient redis;

    public PresenceService(RedisClient redis) {
        this.redis = redis;
    }

    public void markOnline(long userId) {
        try {
            redis.run(jedis -> jedis.sadd(RedisKeys.ONLINE, Long.toString(userId)));
        } catch (RuntimeException e) {
            log.warn("Could not mark user " + userId + " online: " + e.getMessage());
        }
    }

    public void markOffline(long userId) {
        try {
            redis.run(jedis -> jedis.srem(RedisKeys.ONLINE, Long.toString(userId)));
        } catch (RuntimeException e) {
            log.warn("Could not mark user " + userId + " offline: " + e.getMessage());
        }
    }

    public int onlineCount() {
        try {
            return redis.with(jedis -> jedis.scard(RedisKeys.ONLINE)).intValue();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Fixed-window rate limit.
     *
     * @return true if the action is allowed. Fails open on a Redis error —
     *         losing a rate limit is far less bad than losing the game server.
     */
    public boolean allow(String action, long userId, int limit, int windowSeconds) {
        long window = System.currentTimeMillis() / (windowSeconds * 1000L);
        String key = RedisKeys.rateLimit(action, userId, window);
        try {
            Object result = redis.eval(LuaScripts.RATE_LIMIT,
                    java.util.List.of(key),
                    java.util.List.of(Integer.toString(limit), Integer.toString(windowSeconds)));
            return !(result instanceof Long allowed) || allowed == 1L;
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing " + action + ": " + e.getMessage());
            return true;
        }
    }

    /** Clears presence for this instance's users on a clean shutdown. */
    public void clearAll(Iterable<Long> userIds) {
        try {
            redis.run(jedis -> {
                for (Long id : userIds) {
                    jedis.srem(RedisKeys.ONLINE, Long.toString(id));
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not clear presence: " + e.getMessage());
        }
    }
}
