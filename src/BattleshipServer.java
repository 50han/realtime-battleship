/*
 * BattleshipServer.java
 *
 * Entry point.  Accepts exactly two client connections, wires up all
 * objects, starts all threads, and waits for the game to finish.
 *
 * Usage:
 *   java BattleshipServer          (default port 8080)
 *   java BattleshipServer 9090     (custom port)
 *
 * Important — do NOT pre-queue ASSIGN messages here.
 * ASSIGN is sent from inside ClientHandler, immediately after the WebSocket
 * handshake completes.  Sending it earlier risks writing a WebSocket frame
 * before the HTTP 101 upgrade response is finished, which corrupts the
 * connection and causes the browser to disconnect immediately.
 */

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;

public class BattleshipServer {

    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) throws IOException {
        // TODO: parse port from args[0] if present, fall back to DEFAULT_PORT
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        // TODO: create a ServerSocket on the chosen port (try-with-resources)
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            // TODO: print a startup message
            System.out.println("Battleship Server listening on port " + port);
            // TODO: call serverSocket.accept() to get player 1's Socket
            Socket s1 = serverSocket.accept();
            System.out.println("Player 1 connected.");
            // TODO: call serverSocket.accept() again to get player 2's Socket
            Socket s2 = serverSocket.accept();
            System.out.println("Player 2 connected.");
            // TODO: create a LinkedBlockingQueue<String> for each player's outbox
            LinkedBlockingQueue<String> outbox1 = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<String> outbox2 = new LinkedBlockingQueue<>();
            // TODO: create a WriterThread for each player
            WriterThread w1 = new WriterThread(outbox1, s1.getOutputStream(), s1);
            WriterThread w2 = new WriterThread(outbox2, s2.getOutputStream(), s2);
            // TODO: create the shared GameServer
            GameServer gameServer = new GameServer(s1, s2, w1, w2);
            // TODO: create a ClientHandler for each player
            ClientHandler h1 = new ClientHandler(s1, 1, gameServer);
            ClientHandler h2 = new ClientHandler(s2, 2, gameServer);
            // TODO: start both WriterThreads first (so they are ready to drain
            //       outboxes as soon as ClientHandlers start enqueuing post-handshake)
            w1.start();
            w2.start();
            h1.start();
            h2.start();

            // TODO: join both ClientHandler threads so main() waits for the game to end
            try {
                h1.join();
                h2.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // TODO: print a shutdown message
                System.out.println("Server shutting down.");
        }
    }
}
