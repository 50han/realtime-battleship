package com.battleship.net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * RFC 6455 handshake and text-frame codec, built on java.net / java.io only.
 *
 * Extended from the Lab 5 utility in three ways:
 *   1. The handshake takes an already-parsed {@link HttpRequest}, so the same
 *      accept loop can serve REST and WebSocket traffic on one port.
 *   2. Ping frames get a real pong. The reply is handed to a callback (the
 *      client's {@link WriterThread}) instead of being written inline, keeping
 *      the one-writer-per-socket invariant.
 *   3. Fragmented messages are reassembled across continuation frames.
 */
public final class WebSocketUtil {

    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT         = 0x1;
    private static final int OP_BINARY       = 0x2;
    private static final int OP_CLOSE        = 0x8;
    private static final int OP_PING         = 0x9;
    private static final int OP_PONG         = 0xA;

    /** Hard cap on a single reassembled message, to bound memory per client. */
    private static final int MAX_MESSAGE_BYTES = 256 * 1024;

    private WebSocketUtil() {}

    /**
     * Completes the opening handshake for an upgrade request that has already
     * been parsed off the socket.
     *
     * @throws IOException if the request is not a valid upgrade
     */
    public static void acceptHandshake(HttpRequest request, OutputStream out) throws IOException {
        String key = request.header("sec-websocket-key")
                .orElseThrow(() -> new IOException("Missing Sec-WebSocket-Key header"));
        String version = request.header("sec-websocket-version").orElse("13");
        if (!"13".equals(version.trim())) {
            HttpResponse.text(400, "Unsupported Sec-WebSocket-Version: " + version)
                    .withHeader("Sec-WebSocket-Version", "13")
                    .writeTo(out);
            throw new IOException("Unsupported WebSocket version " + version);
        }

        String response =
                "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + computeAcceptToken(key) + "\r\n" +
                "\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /**
     * Reads one complete application message.
     *
     * @param in        the socket's InputStream
     * @param controlOut receives pong replies to inbound pings; may be null
     * @return the text payload, or null when the peer closed the connection
     */
    public static String readMessage(InputStream in, Consumer<OutboundFrame> controlOut)
            throws IOException {

        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageOpcode = -1;

        while (true) {
            int b0 = in.read();
            if (b0 == -1) return null;
            int b1 = in.read();
            if (b1 == -1) return null;

            boolean fin    = (b0 & 0x80) != 0;
            int     opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long payloadLen = b1 & 0x7F;

            if (payloadLen == 126) {
                payloadLen = ((long) readByte(in) << 8) | readByte(in);
            } else if (payloadLen == 127) {
                payloadLen = 0;
                for (int i = 0; i < 8; i++) {
                    payloadLen = (payloadLen << 8) | readByte(in);
                }
            }
            if (payloadLen < 0 || payloadLen > MAX_MESSAGE_BYTES) {
                throw new IOException("Frame payload too large: " + payloadLen);
            }

            // RFC 6455 §5.1: every client-to-server frame must be masked.
            if (!masked) {
                throw new IOException("Received unmasked client frame");
            }
            byte[] maskKey = new byte[4];
            readFully(in, maskKey, 4);

            byte[] payload = new byte[(int) payloadLen];
            readFully(in, payload, payload.length);
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= maskKey[i & 3];
            }

            switch (opcode) {
                case OP_CLOSE -> {
                    return null;
                }
                case OP_PING -> {
                    if (controlOut != null) controlOut.accept(OutboundFrame.pong(payload));
                    continue;   // control frames never interrupt a fragmented message
                }
                case OP_PONG -> {
                    continue;   // heartbeat reply; nothing to do
                }
                case OP_TEXT, OP_BINARY -> {
                    if (messageOpcode != -1) {
                        throw new IOException("Interleaved data frame during fragmentation");
                    }
                    messageOpcode = opcode;
                    message.write(payload);
                }
                case OP_CONTINUATION -> {
                    if (messageOpcode == -1) {
                        throw new IOException("Continuation frame with nothing to continue");
                    }
                    message.write(payload);
                }
                default -> throw new IOException("Unsupported opcode: " + opcode);
            }

            if (message.size() > MAX_MESSAGE_BYTES) {
                throw new IOException("Reassembled message too large");
            }
            if (fin) {
                if (messageOpcode == OP_BINARY) {
                    // This protocol is text-only; drop and keep reading.
                    message.reset();
                    messageOpcode = -1;
                    continue;
                }
                return message.toString(StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * Writes one frame. Server-to-client frames are never masked (RFC 6455 §5.1).
     *
     * Not thread-safe by design — only {@link WriterThread} may call it for a
     * given stream.
     */
    public static void writeFrame(OutputStream out, OutboundFrame frame) throws IOException {
        byte[] payload = frame.payload();
        int len = payload.length;

        ByteArrayOutputStream buf = new ByteArrayOutputStream(len + 10);
        buf.write(0x80 | (frame.opcode() & 0x0F));       // FIN = 1

        if (len <= 125) {
            buf.write(len);
        } else if (len <= 65535) {
            buf.write(126);
            buf.write((len >> 8) & 0xFF);
            buf.write(len & 0xFF);
        } else {
            buf.write(127);
            for (int i = 7; i >= 0; i--) buf.write((int) ((long) len >> (8 * i)) & 0xFF);
        }
        buf.write(payload, 0, len);

        out.write(buf.toByteArray());
        out.flush();
    }

    // ------------------------------------------------------------------

    private static String computeAcceptToken(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((clientKey.trim() + WS_MAGIC).getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) throw new EOFException("Stream closed mid-frame");
        return b & 0xFF;
    }

    private static void readFully(InputStream in, byte[] buf, int n) throws IOException {
        int offset = 0;
        while (offset < n) {
            int read = in.read(buf, offset, n - offset);
            if (read == -1) throw new EOFException("Stream closed mid-frame");
            offset += read;
        }
    }
}
