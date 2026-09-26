package com.battleship.auth;

/** The identity carried by a verified JWT. */
public record AuthenticatedUser(long userId, String username) {
}
