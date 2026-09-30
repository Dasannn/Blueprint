package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.CompensationRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for managing {@link CompensationRecord} rows per SB-057.
 * Records pending compensation when money is withdrawn so refunds survive crashes
 * and are reconciled across restarts.
 */
public final class CompensationRepository {

    private final StorageEngine engine;

    public CompensationRepository(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "StorageEngine must not be null");
    }

    public StorageEngine engine() {
        return engine;
    }

    long saveCompensationInternal(Connection conn, UUID playerUuid, double amount, String reason, Instant createdAt) throws SQLException {
        String sql = """
            INSERT INTO pending_compensation (player_uuid, amount, reason, created_at)
            VALUES (?, ?, ?, ?);
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, playerUuid.toString());
            ps.setDouble(2, amount);
            ps.setString(3, reason);
            ps.setString(4, StorageTimestamps.format(createdAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new SQLException("Failed to obtain generated ID for pending_compensation");
        }
    }

    public CompletableFuture<Long> saveCompensationAsync(UUID playerUuid, double amount, String reason, Instant createdAt) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        return engine.executeAsync(conn -> saveCompensationInternal(conn, playerUuid, amount, reason, createdAt));
    }

    void deleteCompensationInternal(Connection conn, long id) throws SQLException {
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
            String sql = "SELECT id, player_uuid, amount, reason, created_at FROM pending_compensation ORDER BY id ASC;";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                List<CompensationRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(new CompensationRecord(
                            rs.getLong("id"),
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getDouble("amount"),
                            rs.getString("reason"),
                            StorageTimestamps.parse(rs.getString("created_at"))
                    ));
                }
                return records;
            }
        });
    }

    public CompletableFuture<List<CompensationRecord>> findByPlayerAsync(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        return engine.executeAsync(conn -> {
            String sql = "SELECT id, player_uuid, amount, reason, created_at FROM pending_compensation WHERE player_uuid = ? ORDER BY id ASC;";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    List<CompensationRecord> records = new ArrayList<>();
                    while (rs.next()) {
                        records.add(new CompensationRecord(
                                rs.getLong("id"),
                                UUID.fromString(rs.getString("player_uuid")),
                                rs.getDouble("amount"),
                                rs.getString("reason"),
                                StorageTimestamps.parse(rs.getString("created_at"))
                        ));
                    }
                    return records;
                }
            }
        });
    }
}
