package com.battleship.db;

import java.time.Instant;

/** A persisted account. {@code passwordHash} is never serialized to clients. */
public record User(long id,
                   String username,
                   String passwordHash,
                   int wins,
                   int losses,
                   Instant createdAt) {

    public int gamesPlayed() { return wins + losses; }
}
