package com.battleship.db;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code matches}, joined with both usernames for display. */
public record MatchRecord(UUID id,
                          long player1Id,
                          String player1Name,
                          long player2Id,
                          String player2Name,
                          Long winnerId,
                          String status,
                          int totalShots,
                          Instant startedAt,
                          Instant endedAt) {

    public boolean isFinished() { return endedAt != null; }
}
