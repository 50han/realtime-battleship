package com.battleship.redis;

/**
 * The matchmaking critical sections, expressed as Lua.
 *
 * Redis runs a script as one indivisible step, so each of these is a critical
 * section that no other client can interleave with. That is what keeps
 * matchmaking deadlock-free: there is never a moment where a caller holds one
 * key and waits for another, so there is no lock-ordering problem to get wrong
 * and no WATCH/MULTI retry loop to livelock. The alternative — client-side
 * locks around several keys — is exactly the shape that deadlocks under load.
 */
public final class LuaScripts {

    private LuaScripts() {}

    /**
     * Enqueue a player.
     *
     * KEYS[1] queue ZSET, KEYS[2] this player's ticket hash, KEYS[3] the
     * player's active-match key.
     * ARGV[1] userId, ARGV[2] score (epoch millis), ARGV[3] instanceId,
     * ARGV[4] ticket TTL seconds.
     *
     * Returns {status, queueSize}: status is "QUEUED", "ALREADY_QUEUED" or
     * "IN_MATCH".
     */
    public static final String ENQUEUE = """
            if redis.call('EXISTS', KEYS[3]) == 1 then
              return {'IN_MATCH', tostring(redis.call('ZCARD', KEYS[1]))}
            end
            if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
              return {'ALREADY_QUEUED', tostring(redis.call('ZCARD', KEYS[1]))}
            end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
            redis.call('HSET', KEYS[2], 'instance', ARGV[3], 'queuedAt', ARGV[2])
            redis.call('EXPIRE', KEYS[2], ARGV[4])
            return {'QUEUED', tostring(redis.call('ZCARD', KEYS[1]))}
            """;

    /**
     * Remove a player from the queue.
     *
     * KEYS[1] queue ZSET, KEYS[2] ticket hash. ARGV[1] userId.
     * Returns 1 if the player was actually removed.
     */
    public static final String DEQUEUE = """
            local removed = redis.call('ZREM', KEYS[1], ARGV[1])
            redis.call('DEL', KEYS[2])
            return removed
            """;

    /**
     * Pop the two longest-waiting players and claim them for a new match.
     *
     * KEYS[1] queue ZSET. ARGV[1] matchId, ARGV[2] owner instanceId,
     * ARGV[3] active-match TTL seconds, ARGV[4] owner WebSocket URL.
     *
     * Returns nil when fewer than two players are waiting — and in that case
     * puts the single popped player back at their original score so they keep
     * their place in line. Otherwise returns
     * {userIdA, instanceA, userIdB, instanceB}.
     */
    public static final String PAIR = """
            local popped = redis.call('ZPOPMIN', KEYS[1], 2)
            if #popped < 4 then
              if #popped == 2 then
                redis.call('ZADD', KEYS[1], popped[2], popped[1])
              end
              return nil
            end
            local userA, scoreA = popped[1], popped[2]
            local userB, scoreB = popped[3], popped[4]

            local ticketA = 'bs:mm:ticket:' .. userA
            local ticketB = 'bs:mm:ticket:' .. userB
            local instanceA = redis.call('HGET', ticketA, 'instance') or ''
            local instanceB = redis.call('HGET', ticketB, 'instance') or ''
            redis.call('DEL', ticketA, ticketB)

            redis.call('SET', 'bs:user:' .. userA .. ':match', ARGV[1], 'EX', ARGV[3])
            redis.call('SET', 'bs:user:' .. userB .. ':match', ARGV[1], 'EX', ARGV[3])
            redis.call('HSET', 'bs:match:' .. ARGV[1] .. ':owner',
                       'instance', ARGV[2], 'url', ARGV[4])
            redis.call('EXPIRE', 'bs:match:' .. ARGV[1] .. ':owner', ARGV[3])

            return {userA, instanceA, userB, instanceB}
            """;

    /**
     * Release both players when a match ends.
     *
     * ARGV[1] matchId, ARGV[2] userA, ARGV[3] userB. Only deletes the
     * active-match pointers that still refer to this match, so a player who
     * has already been paired into a new game is left alone.
     */
    public static final String RELEASE = """
            for i = 2, 3 do
              local key = 'bs:user:' .. ARGV[i] .. ':match'
              if redis.call('GET', key) == ARGV[1] then
                redis.call('DEL', key)
              end
            end
            redis.call('DEL', 'bs:match:' .. ARGV[1] .. ':owner')
            redis.call('DEL', 'bs:match:' .. ARGV[1] .. ':state')
            return 1
            """;

    /**
     * Fixed-window rate limiter.
     *
     * KEYS[1] the bucket key. ARGV[1] limit, ARGV[2] window seconds.
     * Returns 1 when the call is allowed, 0 when the limit is exceeded.
     */
    public static final String RATE_LIMIT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if count > tonumber(ARGV[1]) then
              return 0
            end
            return 1
            """;
}
