package com.battleship.api;

import com.battleship.auth.AuthException;
import com.battleship.config.AppConfig;
import com.battleship.net.HttpRequest;
import com.battleship.net.HttpResponse;
import com.battleship.protocol.Json;
import com.battleship.util.Log;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * A small path router for the REST surface.
 *
 * Routes are matched in registration order. Cross-origin headers and error
 * mapping are applied centrally so no individual handler has to remember them.
 */
public final class Router {

    private static final Log log = Log.of(Router.class);

    public interface Handler {
        HttpResponse handle(HttpRequest request) throws Exception;
    }

    private record Route(String method, BiPredicate<String, String> matches, Handler handler) {}

    private final List<Route> routes = new ArrayList<>();
    private final AppConfig config;

    public Router(AppConfig config) {
        this.config = config;
    }

    public Router get(String path, Handler handler) { return exact("GET", path, handler); }
    public Router post(String path, Handler handler) { return exact("POST", path, handler); }

    /** Matches {@code prefix} followed by a single path segment. */
    public Router getPrefixed(String prefix, Handler handler) {
        routes.add(new Route("GET",
                (method, path) -> path.startsWith(prefix) && path.length() > prefix.length(),
                handler));
        return this;
    }

    private Router exact(String method, String path, Handler handler) {
        routes.add(new Route(method, (m, p) -> p.equals(path), handler));
        return this;
    }

    public HttpResponse handle(HttpRequest request) {
        // CORS preflight: the React dev server is on a different origin.
        if ("OPTIONS".equals(request.method())) {
            return withCors(HttpResponse.noContent(204)
                    .withHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    .withHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
                    .withHeader("Access-Control-Max-Age", "600"));
        }

        boolean pathExists = false;
        for (Route route : routes) {
            if (!route.matches().test(request.method(), request.path())) continue;
            pathExists = true;
            if (!route.method().equals(request.method())) continue;
            return withCors(invoke(route.handler(), request));
        }
        return withCors(pathExists
                ? error(405, "METHOD_NOT_ALLOWED", "That method is not allowed here")
                : error(404, "NOT_FOUND", "No such endpoint: " + request.path()));
    }

    private HttpResponse invoke(Handler handler, HttpRequest request) {
        try {
            return handler.handle(request);
        } catch (AuthException e) {
            return error(e.statusCode(), codeFor(e.statusCode()), e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(400, "BAD_REQUEST", e.getMessage());
        } catch (Exception e) {
            log.error("Unhandled error serving " + request, e);
            return error(500, "INTERNAL_ERROR", "Something went wrong on the server");
        }
    }

    private HttpResponse withCors(HttpResponse response) {
        return response
                .withHeader("Access-Control-Allow-Origin", config.corsOrigin())
                .withHeader("Vary", "Origin");
    }

    public static HttpResponse error(int status, String code, String message) {
        ObjectNode node = Json.object();
        node.put("error", code);
        node.put("message", message == null ? "" : message);
        return HttpResponse.json(status, Json.write(node));
    }

    private static String codeFor(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 409 -> "CONFLICT";
            case 429 -> "RATE_LIMITED";
            default  -> "ERROR";
        };
    }
}
