package com.battleship.redis;

/** Every Redis key this server uses, in one place. */
public final class RedisKeys {

    /** ZSET of user ids waiting for a match, scored by enqueue time. */
    public static final String QUEUE = "bs:mm:queue";

    /** SET of user ids with a live WebSocket, for the lobby's online count. */
    public static final String ONLINE = "bs:presence:online";

    /** Pub/Sub channel carrying cross-instance match notifications. */
    public static final String EVENTS_CHANNEL = "bs:events";

    private RedisKeys() {}

    /** HASH: which instance a queued player is connected to. */
    public static String ticket(long userId) { return "bs:mm:ticket:" + userId; }

    /** STRING: the match a user is currently in (drives reconnect). */
    public static String userMatch(long userId) { return "bs:user:" + userId + ":match"; }

    /** STRING: JSON snapshot of a live match, used to restore after reconnect. */
    public static String matchState(String matchId) { return "bs:match:" + matchId + ":state"; }

    /** HASH: which instance owns the authoritative session for a match. */
    public static String matchOwner(String matchId) { return "bs:match:" + matchId + ":owner"; }

    /** STRING counter: sliding-window rate limit bucket. */
    public static String rateLimit(String action, long userId, long windowIndex) {
        return "bs:rl:" + action + ":" + userId + ":" + windowIndex;
    }
}
