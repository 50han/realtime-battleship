package com.battleship.db;

/** One row of the {@code leaderboard} view. */
public record LeaderboardEntry(long userId,
                               String username,
                               int wins,
                               int losses,
                               int gamesPlayed,
                               double winRate) {
}
