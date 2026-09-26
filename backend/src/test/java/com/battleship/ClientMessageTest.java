package com.battleship;

import com.battleship.protocol.ClientMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClientMessageTest {

    @Test
    void parsesAFireMessage() {
        ClientMessage m = ClientMessage.parse("{\"type\":\"FIRE\",\"row\":3,\"col\":7}");
        assertEquals(ClientMessage.FIRE, m.type());
        assertEquals(3, m.row());
        assertEquals(7, m.col());
    }

    @Test
    void acceptsNumericFieldsSentAsStrings() {
        ClientMessage m = ClientMessage.parse("{\"type\":\"FIRE\",\"row\":\"3\",\"col\":\"7\"}");
        assertEquals(3, m.row());
        assertEquals(7, m.col());
    }

    @Test
    void survivesChatTextThatWouldBreakStringConcatenation() {
        String raw = "{\"type\":\"CHAT\",\"text\":\"he said \\\"nice shot\\\"\\nthen left\"}";
        ClientMessage m = ClientMessage.parse(raw);
        assertEquals(ClientMessage.CHAT, m.type());
        assertEquals("he said \"nice shot\"\nthen left", m.text());
    }

    @Test
    void clampsOverlongChat() {
        String longText = "x".repeat(5000);
        ClientMessage m = ClientMessage.parse("{\"type\":\"CHAT\",\"text\":\"" + longText + "\"}");
        assertEquals(300, m.text().length());
    }

    @Test
    void mapsUnparseableInputToUnknownInsteadOfThrowing() {
        assertEquals(ClientMessage.UNKNOWN, ClientMessage.parse("not json").type());
        assertEquals(ClientMessage.UNKNOWN, ClientMessage.parse("[1,2,3]").type());
        assertEquals(ClientMessage.UNKNOWN, ClientMessage.parse("{\"row\":1}").type());
        assertEquals(ClientMessage.UNKNOWN, ClientMessage.parse("").type());
    }

    @Test
    void defaultsMissingCoordinatesToMinusOne() {
        ClientMessage m = ClientMessage.parse("{\"type\":\"FIRE\"}");
        assertEquals(-1, m.row());
        assertEquals(-1, m.col());
        assertFalse(m.hasText());
    }
}
