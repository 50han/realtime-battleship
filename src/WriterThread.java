/*
 * WriterThread.java
 *
 * One instance per connected client.  Its only job is to drain a
 * LinkedBlockingQueue<String> and write each entry to the client's
 * OutputStream as a WebSocket frame.
 *
 * Having a single dedicated writer thread per client guarantees that
 * WebSocketUtil.writeFrame is never called concurrently on the same
 * OutputStream, which would corrupt the frame stream.
 *
 * Any other thread that wants to send a message to this client calls
 * send(String json), which enqueues the message without blocking.
 *
 * Shutdown is signalled by placing POISON_PILL in the queue.
 */

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;

public class WriterThread extends Thread {

    /** Sentinel that tells this thread to close the socket and exit. */
    public static final String POISON_PILL = "\u0000SHUTDOWN\u0000";

    private final LinkedBlockingQueue<String> outbox;
    private final OutputStream out;
    private final Socket socket;

    /**
     * @param outbox the queue this thread drains
     * @param out    the raw OutputStream belonging to the client's Socket
     * @param socket the Socket — closed when this thread exits
     */
    public WriterThread(LinkedBlockingQueue<String> outbox,
                        OutputStream out, Socket socket) {
        this.outbox = outbox;
        this.out    = out;
        this.socket = socket;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            // TODO: loop, taking messages from outbox with outbox.take()
            while (true) {
                String msg = outbox.take();
                
                // TODO: exit the loop when the message is POISON_PILL
                //       (use reference equality: msg == POISON_PILL)
                if (msg == POISON_PILL) {
                    break;
                }
                
                // TODO: for each non-sentinel message, call WebSocketUtil.writeFrame(out, msg)
                //       catch IOException and break the loop if the client disconnected mid-write
                WebSocketUtil.writeFrame(out, msg);
            }
        } catch (IOException e) {
            // Client disconnected mid-write, break loop
        } catch (InterruptedException e) {
            // TODO: catch InterruptedException, restore the interrupt flag, and exit
            Thread.currentThread().interrupt();
        } finally {
            // TODO: in a finally block, close the socket
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    /**
     * Enqueues a JSON string for delivery to this client.
     * Safe to call from any thread at any time.
     */
    public void send(String json) {
        // TODO: add json to outbox (use offer — the queue is unbounded)
        outbox.offer(json);
    }

    /**
     * Signals this thread to stop after delivering any already-queued messages.
     * Returns immediately without waiting for the thread to exit.
     */
    public void shutdown() {
        // TODO: enqueue POISON_PILL
        outbox.offer(POISON_PILL);
    }
}