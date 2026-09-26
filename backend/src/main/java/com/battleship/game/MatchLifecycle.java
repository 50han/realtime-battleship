package com.battleship.game;

import com.battleship.db.MoveRow;

/**
 * Everything a {@link GameSession} needs from the outside world.
 *
 * Deliberately narrow, and every method must return promptly without blocking
 * on I/O: sessions call these from inside game handling, and the rule in this
 * codebase is that no lock is ever held across a database or Redis round trip.
 * Implementations hand the work to a background executor.
 */
public interface MatchLifecycle {

    /** A shot was resolved; persist it asynchronously. */
    void onMove(MoveRow move);

    /** The match ended. Persist the result and release matchmaking state. */
    void onMatchFinished(GameSession session,
                         Long winnerUserId,
                         Long loserUserId,
                         String status,
                         int totalShots);

    /** State advanced; refresh the Redis snapshot used for reconnects. */
    void onStateChanged(GameSession session);

    /** No-op implementation, useful in tests. */
    MatchLifecycle NOOP = new MatchLifecycle() {
        @Override public void onMove(MoveRow move) {}
        @Override public void onMatchFinished(GameSession session, Long winnerUserId,
                                             Long loserUserId, String status, int totalShots) {}
        @Override public void onStateChanged(GameSession session) {}
    };
}
