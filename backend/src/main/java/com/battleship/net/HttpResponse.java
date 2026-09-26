package com.battleship.net;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** A minimal HTTP/1.1 response writer. Always sends Connection: close. */
public final class HttpResponse {

    private final int status;
    private final String contentType;
    private final byte[] body;
    private final Map<String, String> headers = new LinkedHashMap<>();

    private HttpResponse(int status, String contentType, byte[] body) {
        this.status      = status;
        this.contentType = contentType;
        this.body        = body;
    }

    public static HttpResponse json(int status, String json) {
        return new HttpResponse(status, "application/json; charset=utf-8",
                json.getBytes(StandardCharsets.UTF_8));
    }

    public static HttpResponse text(int status, String text) {
        return new HttpResponse(status, "text/plain; charset=utf-8",
                text.getBytes(StandardCharsets.UTF_8));
    }

    public static HttpResponse noContent(int status) {
        return new HttpResponse(status, null, new byte[0]);
    }

    public HttpResponse withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public void writeTo(OutputStream out) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (contentType != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
        }
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n");
        headers.forEach((k, v) -> head.append(k).append(": ").append(v).append("\r\n"));
        head.append("\r\n");

        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    public int status() { return status; }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 413 -> "Payload Too Large";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default  -> "Status " + status;
        };
    }
}
