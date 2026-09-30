package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.duel.DuelParticipant;
import com.dasannn.socialblueprint.domain.duel.DuelRecord;
import com.dasannn.socialblueprint.domain.duel.DuelState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for {@link DuelRecord}s per ARCHITECTURE.md §4, §5 and T-018.
 * Interacts with SQLite tables duel and duel_participant.
 */
public final class DuelRepository {

    private final StorageEngine engine;

    public DuelRepository(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "StorageEngine must not be null");
    }

    public DuelRecord save(DuelRecord duel) {
        Objects.requireNonNull(duel, "DuelRecord must not be null");
        return engine.execute(conn -> saveInternal(conn, duel));
    }

    public CompletableFuture<DuelRecord> saveAsync(DuelRecord duel) {
        Objects.requireNonNull(duel, "DuelRecord must not be null");
        return engine.executeAsync(conn -> saveInternal(conn, duel));
    }

    private DuelRecord saveInternal(Connection conn, DuelRecord duel) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            String insertDuel = """
                INSERT INTO duel (id, state, created_at, ended_at)
                VALUES (?, ?, ?, ?);
            """;
            try (PreparedStatement ps = conn.prepareStatement(insertDuel)) {
                ps.setString(1, duel.id());
                ps.setString(2, duel.state().dbValue());
                ps.setString(3, StorageTimestamps.format(duel.createdAt()));
                if (duel.endedAt() != null) {
                    ps.setString(4, StorageTimestamps.format(duel.endedAt()));
                } else {
                    ps.setNull(4, Types.VARCHAR);
                }
                ps.executeUpdate();
            }

            String insertParticipant = """
                INSERT INTO duel_participant (duel_id, uuid, side)
                VALUES (?, ?, ?);
            """;
            try (PreparedStatement ps = conn.prepareStatement(insertParticipant)) {
                for (DuelParticipant p : duel.participants()) {
                    ps.setString(1, duel.id());
                    ps.setString(2, p.playerId().toString());
                    ps.setString(3, p.side());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();
            return duel;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    public void updateState(String duelId, DuelState state, Instant endedAt) {
        Objects.requireNonNull(duelId, "duelId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        engine.execute(conn -> {
            updateStateInternal(conn, duelId, state, endedAt);
            return null;
        });
    }

    public CompletableFuture<Void> updateStateAsync(String duelId, DuelState state, Instant endedAt) {
        Objects.requireNonNull(duelId, "duelId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        return engine.executeAsync(conn -> {
            updateStateInternal(conn, duelId, state, endedAt);
            return null;
        });
    }

    private void updateStateInternal(Connection conn, String duelId, DuelState state, Instant endedAt) throws SQLException {
        String sql = """
            UPDATE duel
            SET state = ?, ended_at = ?
            WHERE id = ?;
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.dbValue());
            if (endedAt != null) {
                ps.setString(2, StorageTimestamps.format(endedAt));
            } else {
                ps.setNull(2, Types.VARCHAR);
            }
            ps.setString(3, duelId);
            ps.executeUpdate();
        }
    }

    public Optional<DuelRecord> findById(String duelId) {
        Objects.requireNonNull(duelId, "duelId must not be null");
        return engine.execute(conn -> {
            String duelSql = "SELECT id, state, created_at, ended_at FROM duel WHERE id = ?;";
            DuelState state;
            Instant createdAt;
            Instant endedAt;

            try (PreparedStatement ps = conn.prepareStatement(duelSql)) {
                ps.setString(1, duelId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    state = DuelState.fromDbValue(rs.getString("state"));
                    createdAt = StorageTimestamps.parse(rs.getString("created_at"));
                    String endedAtRaw = rs.getString("ended_at");
                    endedAt = (endedAtRaw != null && !endedAtRaw.isBlank()) ? StorageTimestamps.parse(endedAtRaw) : null;
                }
            }

            List<DuelParticipant> participants = new ArrayList<>();
            String partSql = "SELECT uuid, side FROM duel_participant WHERE duel_id = ?;";
            try (PreparedStatement ps = conn.prepareStatement(partSql)) {
                ps.setString(1, duelId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PlayerId pid = PlayerId.fromString(rs.getString("uuid"));
                        String side = rs.getString("side");
                        participants.add(new DuelParticipant(pid, side));
                    }
                }
            }

            return Optional.of(new DuelRecord(duelId, state, createdAt, endedAt, participants));
        });
    }

    public List<DuelRecord> findActiveDuels() {
        return engine.execute(conn -> {
            List<String> activeIds = new ArrayList<>();
            String sql = "SELECT id FROM duel WHERE state = 'active' OR state = 'ACTIVE';";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    activeIds.add(rs.getString("id"));
                }
            }

            List<DuelRecord> records = new ArrayList<>();
            for (String id : activeIds) {
                findByIdInternal(conn, id).ifPresent(records::add);
            }
            return records;
        });
    }

    private Optional<DuelRecord> findByIdInternal(Connection conn, String duelId) throws SQLException {
        String duelSql = "SELECT id, state, created_at, ended_at FROM duel WHERE id = ?;";
        DuelState state;
        Instant createdAt;
        Instant endedAt;

        try (PreparedStatement ps = conn.prepareStatement(duelSql)) {
            ps.setString(1, duelId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                state = DuelState.fromDbValue(rs.getString("state"));
                createdAt = StorageTimestamps.parse(rs.getString("created_at"));
                String endedAtRaw = rs.getString("ended_at");
                endedAt = (endedAtRaw != null && !endedAtRaw.isBlank()) ? StorageTimestamps.parse(endedAtRaw) : null;
            }
        }

        List<DuelParticipant> participants = new ArrayList<>();
        String partSql = "SELECT uuid, side FROM duel_participant WHERE duel_id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(partSql)) {
            ps.setString(1, duelId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlayerId pid = PlayerId.fromString(rs.getString("uuid"));
                    String side = rs.getString("side");
                    participants.add(new DuelParticipant(pid, side));
                }
            }
        }

        return Optional.of(new DuelRecord(duelId, state, createdAt, endedAt, participants));
    }

    /**
     * Cleans up stale active duels on server startup per DoD 4 asynchronously.
     * Prevents duel state leaking across server restarts leaving a player permanently in a duel.
     *
     * @param now the timestamp to mark as ended_at
     * @return CompletableFuture of updated duels count
     */
    public CompletableFuture<Integer> cleanupStaleDuelsOnStartupAsync(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return engine.executeAsync(conn -> {
            String sql = """
                UPDATE duel
                SET state = 'ended', ended_at = ?
                WHERE state = 'active' OR state = 'ACTIVE';
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, StorageTimestamps.format(now));
                return ps.executeUpdate();
            }
        });
    }

    /**
     * Cleans up stale active duels on server startup per DoD 4.
     * Prevents duel state leaking across server restarts leaving a player permanently in a duel.
     *
     * @param now the timestamp to mark as ended_at
     * @return count of updated duels
     */
    public int cleanupStaleDuelsOnStartup(Instant now) {
        return cleanupStaleDuelsOnStartupAsync(now).join();
    }
}
