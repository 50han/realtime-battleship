package com.battleship.redis;

import com.battleship.config.AppConfig;
import com.battleship.util.Log;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisDataException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Thin wrapper over a Jedis connection pool.
 *
 * A {@link Jedis} instance is a single socket and is not thread-safe, so every
 * call borrows one from the pool for the duration of one command and returns
 * it. Scripts are cached by SHA and invoked with EVALSHA, falling back to a
 * full EVAL if Redis has been restarted and forgotten them.
 */
public final class RedisClient implements AutoCloseable {

    private static final Log log = Log.of(RedisClient.class);

    private final JedisPool pool;
    private final String host;
    private final int port;
    private final String password;
    private final Map<String, String> scriptShas = new ConcurrentHashMap<>();

    public RedisClient(AppConfig config) {
        this.host     = config.redisHost();
        this.port     = config.redisPort();
        this.password = config.redisPassword();

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(32);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setMaxWait(Duration.ofSeconds(5));

        this.pool = password.isBlank()
                ? new JedisPool(poolConfig, host, port, 5000)
                : new JedisPool(poolConfig, host, port, 5000, password);
        log.info("Redis pool created for " + host + ":" + port);
    }

    /** Runs a command with a pooled connection. */
    public <T> T with(Function<Jedis, T> action) {
        try (Jedis jedis = pool.getResource()) {
            return action.apply(jedis);
        }
    }

    public void run(java.util.function.Consumer<Jedis> action) {
        try (Jedis jedis = pool.getResource()) {
            action.accept(jedis);
        }
    }

    /**
     * A dedicated, non-pooled connection. Pub/Sub subscription blocks its
     * socket for the lifetime of the subscription, so it must never hold a
     * pooled resource hostage.
     */
    public Jedis dedicatedConnection() {
        Jedis jedis = new Jedis(host, port, 0);
        if (!password.isBlank()) jedis.auth(password);
        return jedis;
    }

    /**
     * Evaluates a cached Lua script.
     *
     * @param script the script source; its SHA is cached after the first call
     */
    public Object eval(String script, List<String> keys, List<String> args) {
        return with(jedis -> {
            String sha = scriptShas.computeIfAbsent(script, jedis::scriptLoad);
            try {
                return jedis.evalsha(sha, keys, args);
            } catch (JedisDataException e) {
                if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                    // Redis restarted and dropped its script cache.
                    scriptShas.remove(script);
                    return jedis.eval(script, keys, args);
                }
                throw e;
            }
        });
    }

    public void awaitReady(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        RuntimeException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                String pong = with(Jedis::ping);
                if ("PONG".equalsIgnoreCase(pong)) return;
            } catch (RuntimeException e) {
                last = e;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for Redis", ie);
            }
        }
        throw new IllegalStateException("Redis not reachable within " + timeoutMillis + "ms", last);
    }

    @Override
    public void close() {
        pool.close();
    }
}
