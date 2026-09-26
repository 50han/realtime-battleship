package com.battleship.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/**
 * A parsed client-to-server message.
 *
 * Parsing never throws: an unreadable payload becomes type {@code UNKNOWN} so
 * a malformed frame costs one ERROR reply instead of killing the connection
 * thread.
 */
public final class ClientMessage {

    public static final String QUEUE_JOIN  = "QUEUE_JOIN";
    public static final String QUEUE_LEAVE = "QUEUE_LEAVE";
    public static final String FIRE        = "FIRE";
    public static final String CHAT        = "CHAT";
    public static final String FORFEIT     = "FORFEIT";
    public static final String PING        = "PING";
    public static final String UNKNOWN     = "UNKNOWN";

    private static final int MAX_CHAT_LENGTH = 300;

    private final String type;
    private final int row;
    private final int col;
    private final String text;

    private ClientMessage(String type, int row, int col, String text) {
        this.type = type;
        this.row  = row;
        this.col  = col;
        this.text = text;
    }

    public static ClientMessage parse(String raw) {
        ObjectNode node = Json.parseObject(raw);
        if (node == null) return new ClientMessage(UNKNOWN, -1, -1, null);

        String type = text(node, "type");
        if (type == null) return new ClientMessage(UNKNOWN, -1, -1, null);
        type = type.trim().toUpperCase(Locale.ROOT);

        int row = intOrDefault(node, "row", -1);
        int col = intOrDefault(node, "col", -1);

        String chat = text(node, "text");
        if (chat != null) {
            chat = chat.strip();
            if (chat.length() > MAX_CHAT_LENGTH) {
                chat = chat.substring(0, MAX_CHAT_LENGTH);
            }
        }
        return new ClientMessage(type, row, col, chat);
    }

    private static String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static int intOrDefault(ObjectNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return fallback;
        if (value.isNumber()) return value.asInt(fallback);
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String type() { return type; }
    public int    row()  { return row; }
    public int    col()  { return col; }
    public String text() { return text; }

    public boolean hasText() { return text != null && !text.isEmpty(); }

    @Override
    public String toString() { return "ClientMessage[" + type + "]"; }
}
