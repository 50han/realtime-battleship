package com.battleship.net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed HTTP/1.1 request.
 *
 * Headers are read one byte at a time on purpose. A {@link java.io.BufferedReader}
 * would happily pull bytes past the blank line into its own buffer — and on a
 * WebSocket upgrade those bytes are the first frames of the connection, which
 * would then be lost. Reading exactly up to the CRLF CRLF leaves the stream
 * positioned for {@link WebSocketUtil#readFrame}.
 */
public final class HttpRequest {

    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_BODY_BYTES   = 64 * 1024;

    private final String method;
    private final String path;
    private final String rawQuery;
    private final Map<String, String> headers;      // lower-cased keys
    private final Map<String, String> queryParams;
    private final byte[] body;

    private HttpRequest(String method, String path, String rawQuery,
                        Map<String, String> headers,
                        Map<String, String> queryParams, byte[] body) {
        this.method      = method;
        this.path        = path;
        this.rawQuery    = rawQuery;
        this.headers     = headers;
        this.queryParams = queryParams;
        this.body        = body;
    }

    public static HttpRequest parse(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            throw new EOFException("Empty request line");
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            throw new IOException("Malformed request line: " + requestLine);
        }
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];

        String path = target;
        String rawQuery = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path     = target.substring(0, q);
            rawQuery = target.substring(q + 1);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        int headerBytes = requestLine.length();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            headerBytes += line.length();
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new IOException("Request headers too large");
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
            }
        }

        byte[] body = new byte[0];
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            int length;
            try {
                length = Integer.parseInt(contentLength.trim());
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Content-Length: " + contentLength);
            }
            if (length < 0 || length > MAX_BODY_BYTES) {
                throw new IOException("Request body too large: " + length);
            }
            body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n == -1) throw new EOFException("Stream closed mid-body");
                read += n;
            }
        }

        return new HttpRequest(method, path, rawQuery, headers,
                parseQuery(rawQuery), body);
    }

    /** Reads a single CRLF- or LF-terminated line without buffering ahead. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') len--;   // strip trailing CR
                return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
            }
            buf.write(b);
            if (buf.size() > MAX_HEADER_BYTES) throw new IOException("Header line too long");
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.ISO_8859_1);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return params;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String val = eq < 0 ? ""   : pair.substring(eq + 1);
            params.put(decode(key), decode(val));
        }
        return params;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;   // leave malformed percent-escapes alone
        }
    }

    public String method()   { return method; }
    public String path()     { return path; }
    public String rawQuery() { return rawQuery; }
    public byte[] body()     { return body; }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }

    public Optional<String> query(String name) {
        return Optional.ofNullable(queryParams.get(name));
    }

    /** True when this request is a WebSocket upgrade attempt. */
    public boolean isWebSocketUpgrade() {
        return header("upgrade")
                .map(v -> v.toLowerCase(Locale.ROOT).contains("websocket"))
                .orElse(false);
    }

    @Override
    public String toString() {
        return method + " " + path + (rawQuery.isEmpty() ? "" : "?" + rawQuery);
    }
}
