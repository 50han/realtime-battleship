/*
 * GameServer.java
 *
 * Holds all authoritative game state and processes messages from both
 * ClientHandler threads.
 *
 * Thread-safety requirement:
 * handleMessage() is called from two different threads simultaneously.
 * You must ensure reads and writes to shared state (boards, fleets, turn,
 * phase, readyCount) are properly synchronized.
 *
 * I/O rule:
 * Never hold a lock while calling WebSocketUtil.writeFrame or any blocking
 * I/O.  Enqueue outbound messages via WriterThread.send() instead — it
 * returns immediately.
 *
 * Chat rule:
 * CHAT messages must reach clients without being delayed by ongoing
 * game-logic processing.  Handle CHAT before acquiring any lock.
 *
 * ASSIGN timing:
 * sendAssign() is called from ClientHandler immediately after the WebSocket
 * handshake completes.  It must NOT be called before the handshake, because
 * WriterThread and ClientHandler share the same OutputStream — writing a
 * WebSocket frame before the HTTP 101 response is sent corrupts the upgrade
 * and causes the browser to close the connection.
 */

import java.net.Socket;

public class GameServer {

    private static final int PHASE_WAITING = 0;
    private static final int PHASE_PLAYING = 1;
    private static final int PHASE_DONE    = 2;

    private final Board[]        boards  = new Board[2];
    private final Fleet[]        fleets  = new Fleet[2];
    private final WriterThread[] writers = new WriterThread[2];
    private final String[]       names   = {"Player 1", "Player 2"};
    private final Socket[]       sockets;

    private int phase      = PHASE_WAITING;
    private int turn       = 1;
    private int readyCount = 0;

    public GameServer(Socket s1, Socket s2, WriterThread w1, WriterThread w2) {
        sockets    = new Socket[]{s1, s2};
        writers[0] = w1;
        writers[1] = w2;
    }

    // -----------------------------------------------------------------------
    // Called by ClientHandler immediately after handshake() returns
    // -----------------------------------------------------------------------

    /**
     * Sends ASSIGN (and WAITING for player 1) to the given player.
     * Safe to call without the game-logic lock — only touches per-player
     * outboxes, which are already thread-safe.
     *
     * Must only be called after WebSocketUtil.handshake() has returned for
     * this player's socket.
     */
    public void sendAssign(int playerNum) {
        // TODO: send ASSIGN to this player via their WriterThread
        writers[playerNum - 1].send(Message.assignJson(playerNum));
        
        // TODO: if playerNum == 1, also send WAITING
        if (playerNum == 1) {
            writers[0].send(Message.waitingJson());
        }
    }

    // -----------------------------------------------------------------------
    // Called by ClientHandler threads
    // -----------------------------------------------------------------------

    /**
     * Processes one message from the given player.
     *
     * Handle CHAT outside any lock — forward it to both clients immediately.
     * For all other types, synchronize on 'this' and dispatch on msg.type:
     * "READY" → handleReady
     * "FIRE"  → handleFire
     * anything else → send ERROR back to the sender
     */
    public void handleMessage(int playerNum, Message msg) {
        // TODO: handle CHAT outside any lock
        if ("CHAT".equals(msg.type)) {
            String senderName = names[playerNum - 1];
            broadcast(Message.chatJson(senderName, msg.text));
            return;
        }

        // TODO: for all other types, synchronize and dispatch
        synchronized (this) {
            if ("READY".equals(msg.type)) {
                handleReady(playerNum, msg);
            } else if ("FIRE".equals(msg.type)) {
                handleFire(playerNum, msg);
            } else {
                writers[playerNum - 1].send(Message.errorJson("Unknown command"));
            }
        }
    }

    /**
     * Called when a client disconnects or throws an IOException.
     * Notifies the other player and initiates shutdown.
     * Must be idempotent — safe to call twice if both sockets close at once.
     */
    public void handleDisconnect(int playerNum) {
        // TODO: guard against double-call (check/set phase under lock)
        synchronized (this) {
            if (phase == PHASE_DONE) return;
            phase = PHASE_DONE;
        }
        
        // TODO: send OPPONENT_DISCONNECTED to the other player
        int otherPlayer = (playerNum == 1) ? 2 : 1;
        writers[otherPlayer - 1].send(Message.opponentDisconnectedJson());
        
        // TODO: call shutdown()
        shutdown();
    }

    // -----------------------------------------------------------------------
    // Private message handlers — call only while holding synchronized(this)
    // -----------------------------------------------------------------------

    private void handleReady(int playerNum, Message msg) {
        // TODO: store display name if msg.name is non-blank
        if (msg.name != null && !msg.name.trim().isEmpty()) {
            names[playerNum - 1] = msg.name.trim();
        }
        
        // TODO: increment readyCount; return early if readyCount < 2
        readyCount++;
        if (readyCount < 2) return;

        // TODO: when both ready: set phase/turn, generate placements for both
        //       players, construct Board and Fleet for each, send GAME_START
        //       to each player with their own board layout and the starting turn
        phase = PHASE_PLAYING;
        turn = 1;

        ShipPlacementGenerator.ShipPlacements p1 = ShipPlacementGenerator.getInstance().generatePlacements(null, false);
        ShipPlacementGenerator.ShipPlacements p2 = ShipPlacementGenerator.getInstance().generatePlacements(null, false);

        boards[0] = new Board(p1);
        boards[1] = new Board(p2);
        fleets[0] = new Fleet(p1);
        fleets[1] = new Fleet(p2);

        writers[0].send(Message.gameStartJson(boards[0].shipLayoutToJson(), turn));
        writers[1].send(Message.gameStartJson(boards[1].shipLayoutToJson(), turn));
    }

    private void handleFire(int playerNum, Message msg) {
        // TODO: reject if phase != PHASE_PLAYING (send ERROR)
        if (phase != PHASE_PLAYING) {
            writers[playerNum - 1].send(Message.errorJson("Game is not in playing phase."));
            return;
        }
        
        // TODO: reject if playerNum != turn (send ERROR — do not advance turn)
        if (playerNum != turn) {
            writers[playerNum - 1].send(Message.errorJson("It is not your turn."));
            return;
        }
        
        // TODO: validate row and col are in [0, BOARD_SIZE)
        if (msg.row < 0 || msg.row >= GameConfiguration.BOARD_SIZE || msg.col < 0 || msg.col >= GameConfiguration.BOARD_SIZE) {
            writers[playerNum - 1].send(Message.errorJson("Coordinates out of bounds."));
            return;
        }

        int defenderIndex = (playerNum == 1) ? 1 : 0;

        // TODO: reject if the target cell is already targeted (send ERROR)
        if (boards[defenderIndex].isAlreadyTargeted(msg.row, msg.col)) {
            writers[playerNum - 1].send(Message.errorJson("Cell is already targeted."));
            return;
        }

        // TODO: fire the shot on the defender's board
        // TODO: if hit, register the hit on the defender's fleet
        Fleet.ShotResult result = fleets[defenderIndex].fireShot(msg.row, msg.col);
        boards[defenderIndex].updateBoard(msg.row, msg.col, result.isHit);

        String sunkShipName = null;
        if (result.historyMessage != null && result.historyMessage.contains("sunk")) {
            String searchStr = "sunk the ";
            int start = result.consoleMessage.indexOf(searchStr);
            if (start != -1) {
                sunkShipName = result.consoleMessage.substring(start + searchStr.length(), result.consoleMessage.length() - 1);
            }
        }

        // TODO: broadcast SHOT_RESULT to both players
        broadcast(Message.shotResultJson(playerNum, msg.row, msg.col, result.isHit, sunkShipName));

        // TODO: if all defender ships sunk: set phase = PHASE_DONE,
        //       broadcast GAME_OVER with the defender's final board, call shutdown()
        if (fleets[defenderIndex].getShipsAfloat() == 0) {
            phase = PHASE_DONE;
            broadcast(Message.gameOverJson(playerNum, boards[defenderIndex].fullStateToJson()));
            shutdown();
        } else {
            // TODO: advance turn and broadcast TURN_CHANGE
            turn = (turn == 1) ? 2 : 1;
            broadcast(Message.turnChangeJson(turn));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Enqueues json in both clients' outboxes. */
    private void broadcast(String json) {
        writers[0].send(json);
        writers[1].send(json);
    }

    /** Signals both WriterThreads to flush and exit. */
    private void shutdown() {
        writers[0].shutdown();
        writers[1].shutdown();
        try { sockets[0].close(); } catch (Exception ignored) {}
        try { sockets[1].close(); } catch (Exception ignored) {}
    }
}