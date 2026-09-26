package com.battleship.auth;

/** A client-visible authentication or validation failure. */
public class AuthException extends RuntimeException {

    private final int statusCode;

    public AuthException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }

    public static AuthException badRequest(String message)  { return new AuthException(400, message); }
    public static AuthException unauthorized(String message) { return new AuthException(401, message); }
    public static AuthException conflict(String message)    { return new AuthException(409, message); }
}
