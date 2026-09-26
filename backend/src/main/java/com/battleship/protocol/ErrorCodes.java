package com.battleship.protocol;

/** Stable error codes so the client can react without string-matching prose. */
public final class ErrorCodes {

    public static final String UNKNOWN_COMMAND   = "UNKNOWN_COMMAND";
    public static final String NOT_IN_MATCH      = "NOT_IN_MATCH";
    public static final String NOT_YOUR_TURN     = "NOT_YOUR_TURN";
    public static final String OUT_OF_BOUNDS     = "OUT_OF_BOUNDS";
    public static final String ALREADY_TARGETED  = "ALREADY_TARGETED";
    public static final String GAME_NOT_ACTIVE   = "GAME_NOT_ACTIVE";
    public static final String ALREADY_QUEUED    = "ALREADY_QUEUED";
    public static final String ALREADY_IN_MATCH  = "ALREADY_IN_MATCH";
    public static final String NOT_QUEUED        = "NOT_QUEUED";
    public static final String RATE_LIMITED      = "RATE_LIMITED";
    public static final String MATCHMAKING_DOWN  = "MATCHMAKING_DOWN";
    public static final String EMPTY_MESSAGE     = "EMPTY_MESSAGE";

    /** The same account connected from somewhere else; this socket is closing. */
    public static final String SESSION_REPLACED  = "SESSION_REPLACED";
    /** This instance is shutting down. */
    public static final String SERVER_SHUTDOWN   = "SERVER_SHUTDOWN";
    /** An unhandled server-side failure; the connection stays open. */
    public static final String INTERNAL_ERROR    = "INTERNAL_ERROR";

    private ErrorCodes() {}
}
