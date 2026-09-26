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
import java.util.UUID;

/** Match history persistence: the match row, its result, and its move log. */
public final class MatchRepository {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED   = "COMPLETED";
    public static final String STATUS_FORFEITED   = "FORFEITED";
    public static final String STATUS_ABANDONED   = "ABANDONED";

    private static final String SELECT_WITH_NAMES = """
            SELECT m.id, m.player1_id, p1.username AS player1_name,
                   m.player2_id, p2.username AS player2_name,
                   m.winner_id, m.status, m.total_shots, m.started_at, m.ended_at
            FROM matches m
            JOIN users p1 ON p1.id = m.player1_id
            JOIN users p2 ON p2.id = m.player2_id
            """;

    private final Database database;

    public MatchRepository(Database database) {
        this.database = database;
    }

    /** Inserts the IN_PROGRESS row that the rest of the match hangs off of. */
    public void createMatch(UUID matchId, long player1Id, long player2Id) {
        String sql = """
                INSERT INTO matches (id, player1_id, player2_id, status)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, matchId);
            ps.setLong(2, player1Id);
            ps.setLong(3, player2Id);
            ps.setString(4, STATUS_IN_PROGRESS);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not create match " + matchId, e);
        }
    }

    /**
     * Closes out a match. Idempotent: the {@code ended_at IS NULL} guard means
     * a duplicate finish (for example a disconnect racing the winning shot)
     * updates zero rows instead of corrupting the record.
     *
     * @return true if this call was the one that actually finished the match
     */
    public boolean finishMatch(UUID matchId, Long winnerId, String status, int totalShots) {
        String sql = """
                UPDATE matches
                SET winner_id = ?, status = ?, total_shots = ?, ended_at = now()
                WHERE id = ? AND ended_at IS NULL
                """;
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (winnerId == null) ps.setNull(1, java.sql.Types.BIGINT);
            else ps.setLong(1, winnerId);
            ps.setString(2, status);
            ps.setInt(3, totalShots);
            ps.setObject(4, matchId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new PersistenceException("Could not finish match " + matchId, e);
        }
    }

    /** Batch-inserts moves. Called from the persistence writer thread only. */
    public void insertMoves(List<MoveRow> moves) {
        if (moves.isEmpty()) return;
        String sql = """
                INSERT INTO match_moves
                    (match_id, move_no, shooter_id, row_idx, col_idx, hit, sunk_ship)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (match_id, move_no) DO NOTHING
                """;
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (MoveRow m : moves) {
                ps.setObject(1, m.matchId());
                ps.setInt(2, m.moveNo());
                ps.setLong(3, m.shooterId());
                ps.setShort(4, (short) m.row());
                ps.setShort(5, (short) m.col());
                ps.setBoolean(6, m.hit());
                if (m.sunkShip() == null) ps.setNull(7, java.sql.Types.VARCHAR);
                else ps.setString(7, m.sunkShip());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new PersistenceException("Could not insert " + moves.size() + " moves", e);
        }
    }

    public List<MatchRecord> historyForUser(long userId, int limit) {
        String sql = SELECT_WITH_NAMES + """
                WHERE m.player1_id = ? OR m.player2_id = ?
                ORDER BY m.started_at DESC
                LIMIT ?
                """;
        List<MatchRecord> out = new ArrayList<>();
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, userId);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not read match history", e);
        }
        return out;
    }

    public Optional<MatchRecord> findById(UUID matchId) {
        String sql = SELECT_WITH_NAMES + " WHERE m.id = ?";
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not read match " + matchId, e);
        }
    }

    public List<MoveRow> movesForMatch(UUID matchId) {
        String sql = """
                SELECT match_id, move_no, shooter_id, row_idx, col_idx, hit, sunk_ship
                FROM match_moves WHERE match_id = ? ORDER BY move_no
                """;
        List<MoveRow> out = new ArrayList<>();
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MoveRow(
                            rs.getObject("match_id", UUID.class),
                            rs.getInt("move_no"),
                            rs.getLong("shooter_id"),
                            rs.getShort("row_idx"),
                            rs.getShort("col_idx"),
                            rs.getBoolean("hit"),
                            rs.getString("sunk_ship")));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not read moves for " + matchId, e);
        }
        return out;
    }

    /**
     * Marks matches left IN_PROGRESS by a crashed instance as ABANDONED.
     * Run once at startup so history never shows a permanently-open game.
     */
    public int abandonStaleMatches() {
        String sql = """
                UPDATE matches SET status = ?, ended_at = now()
                WHERE ended_at IS NULL AND started_at < now() - interval '2 hours'
                """;
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, STATUS_ABANDONED);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not abandon stale matches", e);
        }
    }

    private static MatchRecord map(ResultSet rs) throws SQLException {
        long winner = rs.getLong("winner_id");
        // wasNull() reflects only the most recent getter, so read it now.
        Long winnerId = rs.wasNull() ? null : winner;
        Timestamp ended = rs.getTimestamp("ended_at");
        return new MatchRecord(
                rs.getObject("id", UUID.class),
                rs.getLong("player1_id"),
                rs.getString("player1_name"),
                rs.getLong("player2_id"),
                rs.getString("player2_name"),
                winnerId,
                rs.getString("status"),
                rs.getInt("total_shots"),
                toInstant(rs.getTimestamp("started_at")),
                ended == null ? null : ended.toInstant());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? Instant.EPOCH : ts.toInstant();
    }
}
