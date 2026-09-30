package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.CompensationRecord;
import com.dasannn.socialblueprint.domain.CompensationState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for managing {@link CompensationRecord} rows per SB-057.
 * Records charge intent before money moves, transitions through state machine,
 * and enables idempotent reconciliation across crashes and restarts.
 */
public final class CompensationRepository {

    private final StorageEngine engine;

    public CompensationRepository(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "StorageEngine must not be null");
    }

    public StorageEngine engine() {
        return engine;
    }

    long saveCompensationInternal(Connection conn, UUID playerUuid, double amount, String reason, CompensationState state, Instant createdAt) throws SQLException {
        String sql = """
            INSERT INTO pending_compensation (player_uuid, amount, reason, state, created_at)
            VALUES (?, ?, ?, ?, ?);
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, playerUuid.toString());
            ps.setDouble(2, amount);
            ps.setString(3, reason);
            ps.setString(4, state.name());
            ps.setString(5, StorageTimestamps.format(createdAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new SQLException("Failed to obtain generated ID for pending_compensation");
        }
    }

    long saveCompensationInternal(Connection conn, UUID playerUuid, double amount, String reason, Instant createdAt) throws SQLException {
        return saveCompensationInternal(conn, playerUuid, amount, reason, CompensationState.CHARGED, createdAt);
    }

    public CompletableFuture<Long> saveCompensationAsync(UUID playerUuid, double amount, String reason, CompensationState state, Instant createdAt) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        return engine.executeAsync(conn -> saveCompensationInternal(conn, playerUuid, amount, reason, state, createdAt));
    }

    public CompletableFuture<Long> saveCompensationAsync(UUID playerUuid, double amount, String reason, Instant createdAt) {
        return saveCompensationAsync(playerUuid, amount, reason, CompensationState.CHARGED, createdAt);
    }

    public CompletableFuture<Long> saveIntentAsync(UUID playerUuid, double amount, String reason, Instant createdAt) {
        return saveCompensationAsync(playerUuid, amount, reason, CompensationState.INTENDED, createdAt);
    }

    public boolean markChargedInternal(Connection conn, long id) throws SQLException {
        String sql = "UPDATE pending_compensation SET state = ? WHERE id = ? AND state = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, CompensationState.CHARGED.name());
            ps.setLong(2, id);
            ps.setString(3, CompensationState.INTENDED.name());
            return ps.executeUpdate() > 0;
        }
    }

    public CompletableFuture<Boolean> markChargedAsync(long id) {
        return engine.executeAsync(conn -> markChargedInternal(conn, id));
    }

    public boolean markChargedWithAmountInternal(Connection conn, long id, double actualAmount) throws SQLException {
        String sql = "UPDATE pending_compensation SET state = ?, amount = ? WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, CompensationState.CHARGED.name());
            ps.setDouble(2, actualAmount);
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public CompletableFuture<Boolean> markChargedWithAmountAsync(long id, double actualAmount) {
        return engine.executeAsync(conn -> markChargedWithAmountInternal(conn, id, actualAmount));
    }

    public boolean markEventWrittenInternal(Connection conn, long id) throws SQLException {
        String sql = "UPDATE pending_compensation SET state = ? WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, CompensationState.EVENT_WRITTEN.name());
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    public CompletableFuture<Boolean> markEventWrittenAsync(long id) {
        return engine.executeAsync(conn -> markEventWrittenInternal(conn, id));
    }

    public boolean claimForRefundInternal(Connection conn, long id) throws SQLException {
        String sql = "UPDATE pending_compensation SET state = ? WHERE id = ? AND state = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, CompensationState.REFUNDING.name());
            ps.setLong(2, id);
            ps.setString(3, CompensationState.CHARGED.name());
            return ps.executeUpdate() > 0;
        }
    }

    public CompletableFuture<Boolean> claimForRefundAsync(long id) {
        return engine.executeAsync(conn -> claimForRefundInternal(conn, id));
    }

    public boolean revertToChargedInternal(Connection conn, long id) throws SQLException {
        String sql = "UPDATE pending_compensation SET state = ? WHERE id = ? AND state = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, CompensationState.CHARGED.name());
            ps.setLong(2, id);
            ps.setString(3, CompensationState.REFUNDING.name());
            return ps.executeUpdate() > 0;
        }
    }

    public CompletableFuture<Boolean> revertToChargedAsync(long id) {
        return engine.executeAsync(conn -> revertToChargedInternal(conn, id));
    }

    public boolean markRefundedInternal(Connection conn, long id) throws SQLException {
        String sql = "UPDATE pending_compensation SET state = ? WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, CompensationState.REFUNDED.name());
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    public CompletableFuture<Boolean> markRefundedAsync(long id) {
        return engine.executeAsync(conn -> markRefundedInternal(conn, id));
    }

    public boolean markUncertainInternal(Connection conn, long id) throws SQLException {
        String sql = "UPDATE pending_compensation SET state = ? WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, CompensationState.UNCERTAIN.name());
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    public CompletableFuture<Boolean> markUncertainAsync(long id) {
        return engine.executeAsync(conn -> markUncertainInternal(conn, id));
    }

    public void deleteCompensationInternal(Connection conn, long id) throws SQLException {
        String sql = "DELETE FROM pending_compensation WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public CompletableFuture<Void> deleteCompensationAsync(long id) {
        return engine.executeAsync(conn -> {
            deleteCompensationInternal(conn, id);
            return null;
        });
    }

    public CompletableFuture<List<CompensationRecord>> findAllAsync() {
        return engine.executeAsync(conn -> {
            String sql = "SELECT id, player_uuid, amount, reason, state, created_at FROM pending_compensation ORDER BY id ASC;";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                List<CompensationRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRecord(rs));
                }
                return records;
            }
        });
    }

    public CompletableFuture<List<CompensationRecord>> findByPlayerAsync(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        return engine.executeAsync(conn -> {
            String sql = "SELECT id, player_uuid, amount, reason, state, created_at FROM pending_compensation WHERE player_uuid = ? ORDER BY id ASC;";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    List<CompensationRecord> records = new ArrayList<>();
                    while (rs.next()) {
                        records.add(mapRecord(rs));
                    }
                    return records;
                }
            }
        });
    }

    public CompletableFuture<Optional<CompensationRecord>> findByIdAsync(long id) {
        return engine.executeAsync(conn -> {
            String sql = "SELECT id, player_uuid, amount, reason, state, created_at FROM pending_compensation WHERE id = ?;";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRecord(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    private static CompensationRecord mapRecord(ResultSet rs) throws SQLException {
        String stateStr = rs.getString("state");
        CompensationState state;
        try {
            state = stateStr != null ? CompensationState.valueOf(stateStr) : CompensationState.CHARGED;
        } catch (IllegalArgumentException e) {
            state = CompensationState.CHARGED;
        }
        return new CompensationRecord(
                rs.getLong("id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getDouble("amount"),
                rs.getString("reason"),
                state,
                StorageTimestamps.parse(rs.getString("created_at"))
        );
    }
}
