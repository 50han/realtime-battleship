package com.battleship;

import com.battleship.api.RestApi;
import com.battleship.auth.AuthService;
import com.battleship.auth.JwtService;
import com.battleship.auth.PasswordHasher;
import com.battleship.config.AppConfig;
import com.battleship.db.Database;
import com.battleship.db.MatchRepository;
import com.battleship.db.Migrator;
import com.battleship.db.UserRepository;
import com.battleship.matchmaking.MatchService;
import com.battleship.matchmaking.MatchmakingService;
import com.battleship.net.ConnectionRegistry;
import com.battleship.net.HttpServer;
import com.battleship.net.WebSocketEndpoint;
import com.battleship.redis.PresenceService;
import com.battleship.redis.RedisClient;
import com.battleship.util.Log;

import java.util.concurrent.CountDownLatch;

/**
 * Entry point. Wires everything together, in dependency order, and shuts it
 * down in reverse on SIGTERM.
 *
 * The startup sequence matters: PostgreSQL and Redis must be reachable and
 * migrated before the port opens, so a client can never connect to a server
 * that is not yet able to serve it.
 */
public final class BattleshipApplication implements AutoCloseable {

    private static final Log log = Log.of(BattleshipApplication.class);

    private static final long DEPENDENCY_WAIT_MILLIS = 60_000;

    private final AppConfig config;
    private final Database database;
    private final RedisClient redis;
    private final ConnectionRegistry connections;
    private final MatchService matchService;
    private final MatchmakingService matchmaking;
    private final HttpServer httpServer;
    private final PresenceService presence;

    public BattleshipApplication(AppConfig config) {
        this.config = config;

        // ---- persistence ------------------------------------------------
        this.database = new Database(config);
        database.awaitReady(DEPENDENCY_WAIT_MILLIS);
        new Migrator(database).migrate();

        UserRepository users    = new UserRepository(database);
        MatchRepository matches = new MatchRepository(database);
        int abandoned = matches.abandonStaleMatches();
        if (abandoned > 0) {
            log.info("Marked " + abandoned + " stale match(es) as abandoned");
        }

        // ---- redis ------------------------------------------------------
        this.redis = new RedisClient(config);
        redis.awaitReady(DEPENDENCY_WAIT_MILLIS);
        this.presence = new PresenceService(redis);

        // ---- auth -------------------------------------------------------
        JwtService jwt   = new JwtService(config);
        AuthService auth = new AuthService(users, new PasswordHasher(), jwt);

        // ---- gameplay ---------------------------------------------------
        this.connections  = new ConnectionRegistry();
        this.matchService = new MatchService(config, matches, users, redis, connections);
        this.matchmaking  = new MatchmakingService(config, redis, users,
                connections, matchService);

        WebSocketEndpoint webSocket = new WebSocketEndpoint(config, jwt, connections,
                matchService, matchmaking, presence);
        RestApi restApi = new RestApi(config, auth, jwt, users, matches,
                matchService, matchmaking, connections, presence);

        this.httpServer = new HttpServer(config, restApi.routes(), webSocket);
    }

    public void start() throws Exception {
        matchmaking.start();
        httpServer.start();
        log.info("Battleship server ready in " + config.env() + " mode");
    }

    @Override
    public void close() {
        log.info("Shutting down");
        // Reverse of startup: stop taking traffic, then drain, then release.
        httpServer.close();
        connections.closeAll("The server is restarting");
        matchmaking.close();
        presence.clearAll(connections.userIds());
        matchService.close();
        connections.close();
        redis.close();
        database.close();
        log.info("Shutdown complete");
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.fromEnvironment();

        BattleshipApplication application = new BattleshipApplication(config);
        CountDownLatch stopped = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            application.close();
            stopped.countDown();
        }, "shutdown"));

        application.start();
        stopped.await();
    }
}
