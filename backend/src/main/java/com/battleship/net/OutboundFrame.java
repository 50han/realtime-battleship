package com.battleship.net;

import java.nio.charset.StandardCharsets;

/**
 * A frame queued for delivery to one client.
 *
 * Control frames (pong, close) travel through the same queue as text frames so
 * that a single {@link WriterThread} remains the only thing ever writing to the
 * socket — interleaving a pong from a reader thread with a game update from a
 * game thread would corrupt the frame stream.
 */
public record OutboundFrame(int opcode, byte[] payload, boolean shutdownAfter) {

    public static final int OP_TEXT  = 0x1;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING  = 0x9;
    public static final int OP_PONG  = 0xA;

    public static OutboundFrame text(String json) {
        return new OutboundFrame(OP_TEXT, json.getBytes(StandardCharsets.UTF_8), false);
    }

    public static OutboundFrame pong(byte[] payload) {
        return new OutboundFrame(OP_PONG, payload, false);
    }

    public static OutboundFrame ping() {
        return new OutboundFrame(OP_PING, new byte[0], false);
    }

    /** A close frame; the writer thread exits after flushing it. */
    public static OutboundFrame close(int code, String reason) {
        byte[] reasonBytes = reason == null
                ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + Math.min(reasonBytes.length, 123)];
        payload[0] = (byte) ((code >> 8) & 0xFF);
        payload[1] = (byte) (code & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, payload.length - 2);
        return new OutboundFrame(OP_CLOSE, payload, true);
    }
}
