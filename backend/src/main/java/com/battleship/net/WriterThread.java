package com.battleship.net;

import com.battleship.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single writer for one client socket.
 *
 * Every other thread — game threads, the matchmaking dispatcher, the reader
 * thread replying to a ping — reaches the client by calling {@link #send},
 * which only enqueues. Because exactly one thread ever touches the
 * OutputStream, frames can never interleave, and no caller ever blocks on
 * network I/O while holding a game lock.
 */
public final class WriterThread extends Thread {

    private static final Log log = Log.of(WriterThread.class);

    /** Sentinel telling this thread to close the socket and exit. */
    private static final OutboundFrame POISON_PILL =
            new OutboundFrame(OutboundFrame.OP_CLOSE, new byte[0], true);

    /** Bounded so a client that stops reading cannot exhaust server memory. */
    private static final int OUTBOX_CAPACITY = 1024;

    private final LinkedBlockingQueue<OutboundFrame> outbox =
            new LinkedBlockingQueue<>(OUTBOX_CAPACITY);
    private final OutputStream out;
    private final Socket socket;
    private final String label;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public WriterThread(OutputStream out, Socket socket, String label) {
        super("writer-" + label);
        this.out    = out;
        this.socket = socket;
        this.label  = label;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            while (true) {
                OutboundFrame frame = outbox.take();
                if (frame == POISON_PILL) break;
                try {
                    WebSocketUtil.writeFrame(out, frame);
                } catch (IOException e) {
                    // Peer vanished mid-write; the reader thread will notice too.
                    break;
                }
                if (frame.shutdownAfter()) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeSocketQuietly();
        }
    }

    /**
     * Enqueues a JSON payload. Never blocks and never throws.
     *
     * @return false if the connection is closing or the outbox is saturated
     */
    public boolean send(String json) {
        return enqueue(OutboundFrame.text(json));
    }

    public boolean enqueue(OutboundFrame frame) {
        if (closed.get()) return false;
        boolean accepted = outbox.offer(frame);
        if (!accepted) {
            // A backlogged client is a broken client: drop it rather than
            // letting its queue grow without bound.
            log.warn("Outbox full for " + label + "; closing connection");
            closed.set(true);
            outbox.clear();
            outbox.offer(POISON_PILL);
        }
        return accepted;
    }

    /** Flushes what is already queued, then closes the socket. Idempotent. */
    public void shutdown(int code, String reason) {
        if (!closed.compareAndSet(false, true)) return;
        outbox.offer(OutboundFrame.close(code, reason));
        outbox.offer(POISON_PILL);
    }

    public void shutdown() {
        shutdown(1000, "server closing");
    }

    public boolean isClosed() { return closed.get(); }

    private void closeSocketQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the connection is already gone.
        }
    }
}
