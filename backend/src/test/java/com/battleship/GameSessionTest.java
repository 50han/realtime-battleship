package com.battleship;

import com.battleship.db.MoveRow;
import com.battleship.game.GameConfiguration;
import com.battleship.game.GameSession;
import com.battleship.game.MatchLifecycle;
import com.battleship.game.PlayerBoard;
import com.battleship.game.ShipLayout;
import com.battleship.protocol.ErrorCodes;
import com.battleship.protocol.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSessionTest {

    private static final long USER_A = 1001L;
    private static final long USER_B = 1002L;

    private ScheduledExecutorService scheduler;
    private RecordingLifecycle lifecycle;

    /** Captures lifecycle callbacks so tests can assert on side effects. */
    private static final class RecordingLifecycle implements MatchLifecycle {
        final ConcurrentLinkedQueue<MoveRow> moves = new ConcurrentLinkedQueue<>();
        final AtomicInteger stateChanges = new AtomicInteger();
        final AtomicReference<String> finishStatus = new AtomicReference<>();
        final AtomicReference<Long> winner = new AtomicReference<>();
        final AtomicInteger finishCount = new AtomicInteger();

        @Override public void onMove(MoveRow move) { moves.add(move); }
        @Override public void onStateChanged(GameSession session) { stateChanges.incrementAndGet(); }
        @Override public void onMatchFinished(GameSession session, Long winnerUserId,
                                              Long loserUserId, String status, int totalShots) {
            finishCount.incrementAndGet();
            finishStatus.set(status);
            winner.set(winnerUserId);
        }
    }

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
        lifecycle = new RecordingLifecycle();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    /** Both fleets in identical, known positions; rows 5-8 plus (0,0)-(0,1). */
    private static PlayerBoard fixedBoard() {
        int[][] cells = {
                {50, 51, 52, 53, 54},
                {60, 61, 62, 63},
                {70, 71, 72},
                {80, 81, 82},
                {0, 1}
        };
        return new PlayerBoard(new ShipLayout(cells));
    }

    /**
     * Builds a started session. start() is part of the real lifecycle — it is
     * what arms the first turn timer — so the tests go through it too.
     */
    private GameSession session(long turnTimeoutSeconds) {
        GameSession game = new GameSession(UUID.randomUUID(),
                new GameSession.Player(USER_A, "alice", 1),
                new GameSession.Player(USER_B, "bob", 2),
                fixedBoard(), fixedBoard(),
                scheduler, lifecycle, turnTimeoutSeconds, 60);
        game.start();
        return game;
    }

    private static int turnOf(GameSession session) {
        ObjectNode snapshot = Json.parseObject(session.snapshotJson());
        return snapshot.get("turn").asInt();
    }

    private static int moveCount(GameSession session) {
        return Json.parseObject(session.snapshotJson()).get("moveNo").asInt();
    }

    // ================================================================

    @Test
    void player1MovesFirstAndTurnsAlternate() {
        GameSession game = session(300);
        assertEquals(1, turnOf(game));

        assertNull(game.handleFire(USER_A, 9, 9));
        assertEquals(2, turnOf(game));

        assertNull(game.handleFire(USER_B, 9, 9));
        assertEquals(1, turnOf(game));
    }

    @Test
    void rejectsFiringOutOfTurnWithoutAdvancingTheTurn() {
        GameSession game = session(300);
        assertEquals(ErrorCodes.NOT_YOUR_TURN, game.handleFire(USER_B, 0, 0));
        assertEquals(1, turnOf(game), "a rejected shot must not consume the turn");
        assertEquals(0, moveCount(game));
    }

    @Test
    void rejectsOffBoardCoordinates() {
        GameSession game = session(300);
        assertEquals(ErrorCodes.OUT_OF_BOUNDS, game.handleFire(USER_A, -1, 0));
        assertEquals(ErrorCodes.OUT_OF_BOUNDS, game.handleFire(USER_A, 0, 10));
        assertEquals(ErrorCodes.OUT_OF_BOUNDS,
                game.handleFire(USER_A, GameConfiguration.BOARD_SIZE, 0));
        assertEquals(1, turnOf(game));
    }

    @Test
    void rejectsRepeatShotsAtTheSameCell() {
        GameSession game = session(300);
        assertNull(game.handleFire(USER_A, 4, 4));
        assertNull(game.handleFire(USER_B, 4, 4));      // B's own first shot
        assertEquals(ErrorCodes.ALREADY_TARGETED, game.handleFire(USER_A, 4, 4));
        assertEquals(1, turnOf(game));
    }

    @Test
    void rejectsMessagesFromNonParticipants() {
        GameSession game = session(300);
        assertEquals(ErrorCodes.NOT_IN_MATCH, game.handleFire(9999L, 0, 0));
    }

    @Test
    void endsTheMatchWhenAFleetIsDestroyed() {
        GameSession game = session(300);
        // Player 1 works through every ship cell; player 2 wastes shots on water.
        int[] targets = {50, 51, 52, 53, 54, 60, 61, 62, 63, 70, 71, 72, 80, 81, 82, 0, 1};
        int filler = 20;
        for (int target : targets) {
            String error = game.handleFire(USER_A,
                    target / GameConfiguration.BOARD_SIZE,
                    target % GameConfiguration.BOARD_SIZE);
            assertNull(error, "shot at " + target + " was rejected");
            if (game.isFinished()) break;
            assertNull(game.handleFire(USER_B, filler / 10, filler % 10));
            filler++;
        }

        assertTrue(game.isFinished());
        assertEquals(1, lifecycle.finishCount.get(), "the match must finish exactly once");
        assertEquals("COMPLETED", lifecycle.finishStatus.get());
        assertEquals(USER_A, lifecycle.winner.get());
        assertEquals(GameConfiguration.TOTAL_SHIP_CELLS, targets.length);

        // Nothing more may be accepted afterwards.
        assertEquals(ErrorCodes.GAME_NOT_ACTIVE, game.handleFire(USER_B, 3, 3));
    }

    @Test
    void forfeitAwardsTheWinToTheOpponentExactlyOnce() {
        GameSession game = session(300);
        game.handleForfeit(USER_A);
        game.handleForfeit(USER_A);   // repeated resignations must be ignored

        assertTrue(game.isFinished());
        assertEquals(1, lifecycle.finishCount.get());
        assertEquals("FORFEITED", lifecycle.finishStatus.get());
        assertEquals(USER_B, lifecycle.winner.get());
    }

    @Test
    @Timeout(15)
    void turnTimeoutPassesPlayToTheOtherPlayer() throws Exception {
        GameSession game = session(1);   // 1-second turns
        assertEquals(1, turnOf(game));

        long deadline = System.currentTimeMillis() + 8_000;
        while (turnOf(game) == 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(2, turnOf(game), "an expired turn should pass to the opponent");
    }

    @Test
    @Timeout(20)
    void repeatedTurnTimeoutsForfeitTheMatch() throws Exception {
        GameSession game = session(1);
        long deadline = System.currentTimeMillis() + 15_000;
        while (!game.isFinished() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(game.isFinished(), "three consecutive timeouts should end the match");
        assertEquals("FORFEITED", lifecycle.finishStatus.get());
    }

    @Test
    void chatIsRecordedWithoutRequiringATurn() {
        GameSession game = session(300);
        // Chat is legal at any time, from either player, and must not disturb
        // the turn or the move counter.
        game.handleChat(USER_B, "good luck");
        game.handleChat(USER_A, "you too");
        assertEquals(1, turnOf(game));
        assertEquals(0, moveCount(game));
    }

    @Test
    void snapshotRoundTripPreservesBoardsAndTurn() {
        GameSession game = session(300);
        game.handleFire(USER_A, 5, 0);   // hit on player 2's Carrier
        game.handleFire(USER_B, 9, 9);   // miss

        String snapshot = game.snapshotJson();
        GameSession restored = GameSession.fromSnapshot(snapshot, scheduler,
                MatchLifecycle.NOOP, 300, 60);

        assertEquals(game.matchId(), restored.matchId());
        assertEquals(turnOf(game), turnOf(restored));
        assertEquals(moveCount(game), moveCount(restored));
        assertEquals(1, restored.slotOf(USER_A));
        assertEquals(2, restored.slotOf(USER_B));

        // The restored session must remember that (5,0) is already spent.
        assertEquals(ErrorCodes.ALREADY_TARGETED, restored.handleFire(USER_A, 5, 0));
    }

    @Test
    void logsEveryAcceptedShotForPersistenceInOrder() {
        GameSession game = session(300);
        game.handleFire(USER_A, 0, 0);
        game.handleFire(USER_B, 0, 0);
        game.handleFire(USER_A, 9, 9);

        List<MoveRow> moves = List.copyOf(lifecycle.moves);
        assertEquals(3, moves.size());
        for (int i = 0; i < moves.size(); i++) {
            assertEquals(i + 1, moves.get(i).moveNo());
            assertNotNull(moves.get(i).matchId());
        }
        assertEquals(USER_A, moves.get(0).shooterId());
        assertTrue(moves.get(0).hit(), "(0,0) holds the Destroyer");
        assertEquals(USER_B, moves.get(1).shooterId());
    }

    /**
     * Both players hammer the session from separate threads with no
     * coordination. This is the scenario the original single-threaded lab
     * design could not survive: the assertions below fail if turn checks,
     * duplicate-cell checks or the move counter are not properly serialized.
     */
    @Test
    @Timeout(60)
    void concurrentFireFromBothPlayersStaysConsistent() throws Exception {
        GameSession game = session(600);

        int attemptsPerPlayer = 400;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger notYourTurn = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        Runnable shooter = () -> {
            long me = Thread.currentThread().getName().endsWith("-a") ? USER_A : USER_B;
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 0; i < attemptsPerPlayer; i++) {
                int cell = i % (GameConfiguration.BOARD_SIZE * GameConfiguration.BOARD_SIZE);
                String error = game.handleFire(me,
                        cell / GameConfiguration.BOARD_SIZE,
                        cell % GameConfiguration.BOARD_SIZE);
                if (error == null)                                accepted.incrementAndGet();
                else if (ErrorCodes.NOT_YOUR_TURN.equals(error))  notYourTurn.incrementAndGet();
                else if (ErrorCodes.ALREADY_TARGETED.equals(error)) duplicates.incrementAndGet();
                else if (ErrorCodes.GAME_NOT_ACTIVE.equals(error)) { /* fleet destroyed */ }
                else                                              unexpected.incrementAndGet();
            }
        };

        Thread a = new Thread(shooter, "shooter-a");
        Thread b = new Thread(shooter, "shooter-b");
        a.start();
        b.start();
        start.countDown();
        a.join(TimeUnit.SECONDS.toMillis(45));
        b.join(TimeUnit.SECONDS.toMillis(45));

        assertTrue(!a.isAlive() && !b.isAlive(),
                "handleFire deadlocked: shooter threads did not finish");
        assertEquals(0, unexpected.get(), "no shot should fail for an unexpected reason");
        assertTrue(accepted.get() > 0, "at least some shots must land");

        // The move counter is the invariant: every accepted shot incremented it
        // exactly once, with no lost updates across the two threads.
        assertEquals(accepted.get(), lifecycle.moves.size());
        if (!game.isFinished()) {
            assertEquals(accepted.get(), moveCount(game));
        }

        // And every accepted shot has a distinct move number.
        assertEquals(lifecycle.moves.size(),
                lifecycle.moves.stream().map(MoveRow::moveNo).distinct().count());
    }
}
