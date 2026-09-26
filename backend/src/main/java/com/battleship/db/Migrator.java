package com.battleship.db;

import com.battleship.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately small forward-only migration runner.
 *
 * Each {@code V<n>__name.sql} file on the classpath is applied once, inside a
 * transaction, and recorded in {@code schema_migrations}. The whole run is
 * guarded by a PostgreSQL advisory lock so that starting several server
 * instances at once cannot apply the same migration twice.
 */
public final class Migrator {

    private static final Log log = Log.of(Migrator.class);
    private static final long ADVISORY_LOCK_KEY = 7_311_982_004L;

    /** Add new migrations here, in order. */
    private static final List<String> MIGRATIONS = List.of(
            "V1__initial_schema.sql",
            "V2__leaderboard_view.sql"
    );

    private final Database database;

    public Migrator(Database database) {
        this.database = database;
    }

    public void migrate() {
        try (Connection conn = database.connection()) {
            conn.setAutoCommit(true);
            try (Statement s = conn.createStatement()) {
                s.execute("SELECT pg_advisory_lock(" + ADVISORY_LOCK_KEY + ")");
            }
            try {
                ensureRegistry(conn);
                List<String> applied = alreadyApplied(conn);
                for (String migration : MIGRATIONS) {
                    if (applied.contains(migration)) continue;
                    apply(conn, migration);
                }
            } finally {
                try (Statement s = conn.createStatement()) {
                    s.execute("SELECT pg_advisory_unlock(" + ADVISORY_LOCK_KEY + ")");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database migration failed", e);
        }
    }

    private void ensureRegistry(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        filename   TEXT PRIMARY KEY,
                        applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
        }
    }

    private List<String> alreadyApplied(Connection conn) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT filename FROM schema_migrations")) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    private void apply(Connection conn, String filename) throws SQLException {
        String sql = read("db/migration/" + filename);
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (Statement s = conn.createStatement()) {
                s.execute(sql);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO schema_migrations (filename) VALUES (?)")) {
                ps.setString(1, filename);
                ps.executeUpdate();
            }
            conn.commit();
            log.info("Applied migration " + filename);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    private String read(String resource) {
        try (InputStream in = Migrator.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Migration not found: " + resource);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }
}
