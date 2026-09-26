package com.battleship.matchmaking;

import com.battleship.db.MatchRepository;
import com.battleship.db.MoveRow;
import com.battleship.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Buffers resolved shots and batch-inserts them off the game threads.
 *
 * A shot must reach both clients in milliseconds; an INSERT round trip does
 * not belong on that path. Moves are queued here instead and flushed by one
 * dedicated thread, either when the batch fills or when the linger window
 * expires — so gameplay latency is independent of database latency, and the
 * move log still lands in order.
 */
public final class MovePersistenceWriter extends Thread implements AutoCloseable {

    private static final Log log = Log.of(MovePersistenceWriter.class);

    private static final int  MAX_BATCH      = 128;
    private static final long LINGER_MILLIS  = 250;
    private static final int  QUEUE_CAPACITY = 20_000;

    private static final MoveRow POISON_PILL =
            new MoveRow(null, -1, -1, -1, -1, false, null);

    private final LinkedBlockingQueue<MoveRow> queue =
            new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final MatchRepository matches;
    private volatile boolean running = true;

    public MovePersistenceWriter(MatchRepository matches) {
        super("move-writer");
        this.matches = matches;
        setDaemon(true);
    }

    /** Non-blocking. Drops the move (with a warning) if the queue is saturated. */
    public void submit(MoveRow move) {
        if (!queue.offer(move)) {
            log.warn("Move queue full; dropping move " + move.moveNo()
                    + " of match " + move.matchId());
        }
    }

    @Override
    public void run() {
        List<MoveRow> batch = new ArrayList<>(MAX_BATCH);
        while (running || !queue.isEmpty()) {
            try {
                MoveRow first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                if (first == POISON_PILL) break;

                batch.add(first);
                // Give slightly more work a chance to arrive, then flush.
                long deadline = System.currentTimeMillis() + LINGER_MILLIS;
                while (batch.size() < MAX_BATCH) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    MoveRow next = queue.poll(remaining, TimeUnit.MILLISECONDS);
                    if (next == null) break;
                    if (next == POISON_PILL) { running = false; break; }
                    batch.add(next);
                }
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        flush(batch);
        log.info("Move writer stopped");
    }

    private void flush(List<MoveRow> batch) {
        if (batch.isEmpty()) return;
        try {
            matches.insertMoves(List.copyOf(batch));
        } catch (RuntimeException e) {
            // The move log is an audit trail, not authoritative game state:
            // losing a batch must never take the match down.
            log.error("Failed to persist " + batch.size() + " moves", e);
        } finally {
            batch.clear();
        }
    }

    @Override
    public void close() {
        running = false;
        queue.offer(POISON_PILL);
        try {
            join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
