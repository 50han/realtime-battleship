package com.battleship.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Account persistence. Every method borrows a pooled connection, runs one
 * parameterized statement, and returns it — so repository calls from different
 * game threads never contend on shared JDBC objects.
 */
public final class UserRepository {

    /** Signals that a username is already taken (unique index violation). */
    public static class UsernameTakenException extends RuntimeException {
        public UsernameTakenException(String username) {
            super("Username already taken: " + username);
        }
    }

    private static final String UNIQUE_VIOLATION = "23505";

    private final Database database;

    public UserRepository(Database database) {
        this.database = database;
    }

    public User create(String username, String passwordHash) {
        String sql = """
                INSERT INTO users (username, password_hash)
                VALUES (?, ?)
                RETURNING id, username, password_hash, wins, losses, created_at
                """;
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return map(rs);
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new UsernameTakenException(username);
            }
            throw new PersistenceException("Could not create user", e);
        }
    }

    public Optional<User> findByUsername(String username) {
        String sql = """
                SELECT id, username, password_hash, wins, losses, created_at
                FROM users WHERE lower(username) = lower(?)
                """;
        return queryOne(sql, ps -> ps.setString(1, username));
    }

    public Optional<User> findById(long id) {
        String sql = """
                SELECT id, username, password_hash, wins, losses, created_at
                FROM users WHERE id = ?
                """;
        return queryOne(sql, ps -> ps.setLong(1, id));
    }

    public void touchLastSeen(long userId) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET last_seen_at = now() WHERE id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not update last_seen_at", e);
        }
    }

    /**
     * Applies the win/loss deltas for a finished match in one transaction.
     *
     * The two {@code UPDATE}s are issued in ascending user-id order. That is
     * this table's deadlock-avoidance rule: when two matches that share a
     * player finish simultaneously, both transactions take row locks in the
     * same order, so neither can hold what the other is waiting for.
     */
    public void recordResult(long winnerId, long loserId) {
        long first  = Math.min(winnerId, loserId);
        long second = Math.max(winnerId, loserId);

        try (Connection c = database.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET wins = wins + ?, losses = losses + ? WHERE id = ?")) {
                bindDelta(ps, first, winnerId);
                ps.addBatch();
                bindDelta(ps, second, winnerId);
                ps.addBatch();
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not record match result", e);
        }
    }

    private static void bindDelta(PreparedStatement ps, long userId, long winnerId)
            throws SQLException {
        boolean won = userId == winnerId;
        ps.setInt(1, won ? 1 : 0);
        ps.setInt(2, won ? 0 : 1);
        ps.setLong(3, userId);
    }

    public List<LeaderboardEntry> leaderboard(int limit) {
        String sql = """
                SELECT id, username, wins, losses, games_played, win_rate
                FROM leaderboard LIMIT ?
                """;
        List<LeaderboardEntry> out = new ArrayList<>();
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LeaderboardEntry(
                            rs.getLong("id"),
                            rs.getString("username"),
                            rs.getInt("wins"),
                            rs.getInt("losses"),
                            rs.getInt("games_played"),
                            rs.getDouble("win_rate")));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not read leaderboard", e);
        }
        return out;
    }

    // ------------------------------------------------------------------

    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private Optional<User> queryOne(String sql, Binder binder) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("User lookup failed", e);
        }
    }

    private static User map(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new User(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getInt("wins"),
                rs.getInt("losses"),
                created == null ? Instant.EPOCH : created.toInstant());
    }
}
