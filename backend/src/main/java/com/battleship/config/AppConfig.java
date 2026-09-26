package com.battleship.config;

import java.util.UUID;

/**
 * Immutable, environment-driven configuration.
 *
 * Every value has a development default so the server boots with nothing but
 * `docker compose up`. In production every value is expected to come from the
 * environment — in particular {@link #jwtSecret()}, which the server refuses
 * to accept as the default when {@code APP_ENV=production}.
 */
public final class AppConfig {

    private static final String DEV_JWT_SECRET =
            "dev-only-insecure-secret-change-me-0123456789abcdef";

    private final String  env;
    private final int     httpPort;
    private final String  jdbcUrl;
    private final String  dbUser;
    private final String  dbPassword;
    private final int     dbPoolSize;
    private final String  redisHost;
    private final int     redisPort;
    private final String  redisPassword;
    private final String  jwtSecret;
    private final String  jwtIssuer;
    private final long    jwtTtlSeconds;
    private final String  instanceId;
    private final String  advertisedWsUrl;
    private final String  corsOrigin;
    private final int     maxConnections;
    private final long    turnTimeoutSeconds;
    private final long    reconnectGraceSeconds;

    private AppConfig() {
        this.env             = env("APP_ENV", "development");
        this.httpPort        = intEnv("PORT", 8080);
        this.jdbcUrl         = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/battleship");
        this.dbUser          = env("DATABASE_USER", "battleship");
        this.dbPassword      = env("DATABASE_PASSWORD", "battleship");
        this.dbPoolSize      = intEnv("DATABASE_POOL_SIZE", 12);
        this.redisHost       = env("REDIS_HOST", "localhost");
        this.redisPort       = intEnv("REDIS_PORT", 6379);
        this.redisPassword   = env("REDIS_PASSWORD", "");
        this.jwtSecret       = env("JWT_SECRET", DEV_JWT_SECRET);
        this.jwtIssuer       = env("JWT_ISSUER", "battleship-server");
        this.jwtTtlSeconds   = longEnv("JWT_TTL_SECONDS", 24 * 60 * 60L);
        this.instanceId      = env("INSTANCE_ID", "srv-" + UUID.randomUUID().toString().substring(0, 8));
        this.advertisedWsUrl = env("ADVERTISED_WS_URL", "ws://localhost:" + httpPort + "/ws");
        this.corsOrigin      = env("CORS_ORIGIN", "*");
        this.maxConnections  = intEnv("MAX_CONNECTIONS", 512);
        this.turnTimeoutSeconds    = longEnv("TURN_TIMEOUT_SECONDS", 45);
        this.reconnectGraceSeconds = longEnv("RECONNECT_GRACE_SECONDS", 60);

        if (isProduction() && DEV_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to a strong random value when APP_ENV=production");
        }
        if (jwtSecret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters");
        }
    }

    public static AppConfig fromEnvironment() {
        return new AppConfig();
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            v = System.getProperty(key);
        }
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    private static int intEnv(String key, int fallback) {
        return Integer.parseInt(env(key, Integer.toString(fallback)));
    }

    private static long longEnv(String key, long fallback) {
        return Long.parseLong(env(key, Long.toString(fallback)));
    }

    public boolean isProduction()        { return "production".equalsIgnoreCase(env); }
    public String  env()                 { return env; }
    public int     httpPort()            { return httpPort; }
    public String  jdbcUrl()             { return jdbcUrl; }
    public String  dbUser()              { return dbUser; }
    public String  dbPassword()          { return dbPassword; }
    public int     dbPoolSize()          { return dbPoolSize; }
    public String  redisHost()           { return redisHost; }
    public int     redisPort()           { return redisPort; }
    public String  redisPassword()       { return redisPassword; }
    public String  jwtSecret()           { return jwtSecret; }
    public String  jwtIssuer()           { return jwtIssuer; }
    public long    jwtTtlSeconds()       { return jwtTtlSeconds; }
    public String  instanceId()          { return instanceId; }
    public String  advertisedWsUrl()     { return advertisedWsUrl; }
    public String  corsOrigin()          { return corsOrigin; }
    public int     maxConnections()      { return maxConnections; }
    public long    turnTimeoutSeconds()  { return turnTimeoutSeconds; }
    public long    reconnectGraceSeconds() { return reconnectGraceSeconds; }
}
