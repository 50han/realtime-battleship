package com.battleship.protocol;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * Builders for every server-to-client payload.
 *
 * Keeping them in one class means the wire format is documented in exactly one
 * place ({@code Documents/PROTOCOL.md} mirrors this file).
 */
public final class ServerMessage {

    private ServerMessage() {}

    private static ObjectNode of(String type) {
        ObjectNode node = Json.object();
        node.put("type", type);
        return node;
    }

    /** Sent immediately after a successful WebSocket authentication. */
    public static String hello(long userId, String username, String instanceId,
                               boolean hasActiveMatch) {
        ObjectNode n = of("HELLO");
        n.put("userId", userId);
        n.put("username", username);
        n.put("instanceId", instanceId);
        n.put("hasActiveMatch", hasActiveMatch);
        n.put("serverTime", Instant.now().toEpochMilli());
        return Json.write(n);
    }

    public static String queueJoined(int queueSize, long waitingSinceMillis) {
        ObjectNode n = of("QUEUE_JOINED");
        n.put("queueSize", queueSize);
        n.put("waitingSince", waitingSinceMillis);
        return Json.write(n);
    }

    public static String queueLeft(String reason) {
        ObjectNode n = of("QUEUE_LEFT");
        n.put("reason", reason);
        return Json.write(n);
    }

    public static String queueStatus(int queueSize, int onlinePlayers) {
        ObjectNode n = of("QUEUE_STATUS");
        n.put("queueSize", queueSize);
        n.put("onlinePlayers", onlinePlayers);
        return Json.write(n);
    }

    /**
     * @param serverUrl the WebSocket URL owning this match. When it differs
     *                  from the client's current connection, the client must
     *                  reconnect there — this is how matchmaking across several
     *                  server instances stays correct.
     */
    public static String matchFound(String matchId, int playerSlot,
                                    long opponentId, String opponentName,
                                    String serverUrl, boolean reconnectRequired) {
        ObjectNode n = of("MATCH_FOUND");
        n.put("matchId", matchId);
        n.put("playerSlot", playerSlot);
        ObjectNode opponent = Json.object();
        opponent.put("userId", opponentId);
        opponent.put("username", opponentName);
        n.set("opponent", opponent);
        n.put("serverUrl", serverUrl);
        n.put("reconnectRequired", reconnectRequired);
        return Json.write(n);
    }

    public static String gameStart(String matchId, int playerSlot, int[][] myBoard,
                                   int turn, long turnDeadlineMillis,
                                   String myName, String opponentName) {
        ObjectNode n = of("GAME_START");
        n.put("matchId", matchId);
        n.put("playerSlot", playerSlot);
        n.set("myBoard", Json.grid(myBoard));
        n.put("turn", turn);
        n.put("turnDeadline", turnDeadlineMillis);
        n.put("myName", myName);
        n.put("opponentName", opponentName);
        return Json.write(n);
    }

    /** Full state replay after a mid-match reconnect. */
    public static String gameResume(String matchId, int playerSlot,
                                    int[][] myBoard, int[][] opponentBoard,
                                    ArrayNode myFleet, ArrayNode opponentFleet,
                                    int turn, long turnDeadlineMillis,
                                    String myName, String opponentName,
                                    boolean opponentConnected, ArrayNode chatLog) {
        ObjectNode n = of("GAME_RESUME");
        n.put("matchId", matchId);
        n.put("playerSlot", playerSlot);
        n.set("myBoard", Json.grid(myBoard));
        n.set("opponentBoard", Json.grid(opponentBoard));
        n.set("myFleet", myFleet);
        n.set("opponentFleet", opponentFleet);
        n.put("turn", turn);
        n.put("turnDeadline", turnDeadlineMillis);
        n.put("myName", myName);
        n.put("opponentName", opponentName);
        n.put("opponentConnected", opponentConnected);
        n.set("chatLog", chatLog);
        return Json.write(n);
    }

    public static String shotResult(int shooterSlot, int row, int col, boolean hit,
                                    String sunkShip, int moveNo) {
        ObjectNode n = of("SHOT_RESULT");
        n.put("shooter", shooterSlot);
        n.put("row", row);
        n.put("col", col);
        n.put("hit", hit);
        if (sunkShip == null) n.putNull("sunkShip"); else n.put("sunkShip", sunkShip);
        n.put("moveNo", moveNo);
        return Json.write(n);
    }

    public static String turnChange(int turn, long turnDeadlineMillis, String reason) {
        ObjectNode n = of("TURN_CHANGE");
        n.put("turn", turn);
        n.put("turnDeadline", turnDeadlineMillis);
        if (reason == null) n.putNull("reason"); else n.put("reason", reason);
        return Json.write(n);
    }

    public static String chat(String from, String text, long atMillis) {
        ObjectNode n = of("CHAT");
        n.put("from", from);
        n.put("text", text);
        n.put("at", atMillis);
        return Json.write(n);
    }

    /**
     * @param reason FLEET_DESTROYED | FORFEIT | OPPONENT_ABANDONED
     */
    public static String gameOver(String matchId, int winnerSlot, String reason,
                                  int[][] myFinalBoard, int[][] opponentFinalBoard,
                                  int totalShots) {
        ObjectNode n = of("GAME_OVER");
        n.put("matchId", matchId);
        n.put("winner", winnerSlot);
        n.put("reason", reason);
        n.set("myFinalBoard", Json.grid(myFinalBoard));
        n.set("opponentFinalBoard", Json.grid(opponentFinalBoard));
        n.put("totalShots", totalShots);
        return Json.write(n);
    }

    public static String opponentDisconnected(long graceSeconds) {
        ObjectNode n = of("OPPONENT_DISCONNECTED");
        n.put("graceSeconds", graceSeconds);
        return Json.write(n);
    }

    public static String opponentReconnected(String opponentName) {
        ObjectNode n = of("OPPONENT_RECONNECTED");
        n.put("opponentName", opponentName);
        return Json.write(n);
    }

    public static String error(String code, String message) {
        ObjectNode n = of("ERROR");
        n.put("code", code);
        n.put("message", message);
        return Json.write(n);
    }

    public static String pong() {
        ObjectNode n = of("PONG");
        n.put("serverTime", Instant.now().toEpochMilli());
        return Json.write(n);
    }
}
