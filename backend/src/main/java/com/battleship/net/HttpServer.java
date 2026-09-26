package com.battleship.net;

import com.battleship.api.Router;
import com.battleship.config.AppConfig;
import com.battleship.util.Log;
import com.battleship.util.Threads;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The single accept loop. REST and WebSocket traffic share one port.
 *
 * <h2>Thread model</h2>
 * The accept thread does nothing but hand sockets to a pool; all parsing and
 * serving happens on pool threads. The pool is bounded and backed by a
 * {@link SynchronousQueue}, so it never silently accumulates a queue of
 * half-served clients: once {@code MAX_CONNECTIONS} threads are busy, new
 * arrivals are rejected with a 503 immediately rather than being accepted and
 * left hanging. That trade — shed load loudly instead of degrading quietly — is
 * the reason for a bounded pool rather than a cached one.
 *
 * A WebSocket connection keeps its pool thread for its whole lifetime, since
 * the read is blocking and long-lived; a REST request returns its thread in
 * milliseconds.
 */
public final class HttpServer implements AutoCloseable {

    private static final Log log = Log.of(HttpServer.class);

    /** Time a client has to send its request line and headers. */
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 15_000;

    private final AppConfig config;
    private final Router router;
    private final WebSocketEndpoint webSocket;
    private final ThreadPoolExecutor pool;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;

    public HttpServer(AppConfig config, Router router, WebSocketEndpoint webSocket) {
        this.config    = config;
        this.router    = router;
        this.webSocket = webSocket;
        this.pool = new ThreadPoolExecutor(
                8, config.maxConnections(),
                60, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                Threads.named("conn", true));
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(config.httpPort());
        serverSocket.setReuseAddress(true);
        running = true;

        acceptThread = new Thread(this::acceptLoop, "accept");
        acceptThread.start();
        log.info("Listening on port " + config.httpPort()
                + " (REST + WebSocket, instance " + config.instanceId() + ")");
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running) log.error("accept() failed", e);
                continue;
            }
            try {
                socket.setTcpNoDelay(true);   // game messages are small and latency-sensitive
                pool.execute(() -> serve(socket));
            } catch (RejectedExecutionException e) {
                log.warn("Connection pool saturated; rejecting " + socket.getRemoteSocketAddress());
                rejectOverloaded(socket);
            } catch (IOException e) {
                closeQuietly(socket);
            }
        }
        log.info("Accept loop stopped");
    }

    /** Runs on a pool thread: parse one request, then either upgrade or reply. */
    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            InputStream in = socket.getInputStream();
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 8192);

            HttpRequest request = HttpRequest.parse(in);

            if (request.isWebSocketUpgrade()) {
                // Blocks for the life of the connection; the finally block below
                // closes the socket once it returns.
                webSocket.serve(socket, request, in, out);
                return;
            }

            router.handle(request).writeTo(out);
        } catch (IOException e) {
            // A client that hangs up mid-request is routine, not an error.
        } catch (RuntimeException e) {
            log.error("Unexpected failure serving " + socket.getRemoteSocketAddress(), e);
        } finally {
            closeQuietly(socket);
        }
    }

    private static void rejectOverloaded(Socket socket) {
        try (Socket toClose = socket) {
            HttpResponse.json(503, "{\"error\":\"SERVER_BUSY\","
                            + "\"message\":\"Too many connections; try again shortly\"}")
                    .withHeader("Retry-After", "5")
                    .writeTo(toClose.getOutputStream());
        } catch (IOException ignored) {
            // The client is gone; nothing to report.
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (!socket.isClosed()) socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
            // Shutting down anyway.
        }
        if (acceptThread != null) acceptThread.interrupt();
        pool.shutdownNow();
        log.info("HTTP server stopped");
    }
}
