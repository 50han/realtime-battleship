package com.battleship.db;

import com.battleship.config.AppConfig;
import com.battleship.util.Log;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Owns the JDBC connection pool.
 *
 * A pool is what makes concurrent gameplay safe on the persistence side: each
 * game thread borrows a connection for the duration of one short statement and
 * returns it, so N parallel matches never serialize behind a single shared
 * {@link Connection} (which is explicitly not thread-safe).
 */
public final class Database implements AutoCloseable {

    private static final Log log = Log.of(Database.class);

    private final HikariDataSource dataSource;

    public Database(AppConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.dbUser());
        hikari.setPassword(config.dbPassword());
        hikari.setMaximumPoolSize(config.dbPoolSize());
        hikari.setMinimumIdle(2);
        hikari.setPoolName("battleship-pool");
        hikari.setConnectionTimeout(10_000);
        hikari.setInitializationFailTimeout(-1); // fail on first use, not at boot
        hikari.setAutoCommit(true);
        this.dataSource = new HikariDataSource(hikari);
        log.info("JDBC pool created (max=" + config.dbPoolSize() + ") for " + config.jdbcUrl());
    }

    public DataSource dataSource() { return dataSource; }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Blocks until the database answers a trivial query, or the deadline passes. */
    public void awaitReady(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        SQLException last = null;
        while (System.currentTimeMillis() < deadline) {
            try (Connection c = connection()) {
                c.createStatement().execute("SELECT 1");
                return;
            } catch (SQLException e) {
                last = e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for PostgreSQL", ie);
                }
            }
        }
        throw new IllegalStateException("PostgreSQL not reachable within "
                + timeoutMillis + "ms", last);
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
