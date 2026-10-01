package com.dasannn.socialblueprint.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Repository for managing persisted rater reveals per T-124:
 * "Revealing one name charges a configurable amount through Vault and is remembered per viewer, persisted."
 * All operations execute on the single-threaded storage executor.
 */
public final class RaterRevealRepository {

    private static final Logger LOGGER = Logger.getLogger(RaterRevealRepository.class.getName());

    private final StorageEngine engine;

    public RaterRevealRepository(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "StorageEngine must not be null");
    }

    public StorageEngine engine() {
        return engine;
    }

    /**
     * Persists a reveal record and transitions the associated compensation row to EVENT_WRITTEN
     * within the same database transaction, then asynchronously triggers compensation deletion.
     * Returns true if a new reveal row was inserted, or false if already present (conflict do nothing).
     */
    public CompletableFuture<Boolean> commitRevealAsync(
            UUID viewerUuid,
            long eventId,
            UUID raterUuid,
            double cost,
            Instant createdAt,
            Long compensationId,
            CompensationRepository compensationRepository
    ) {
        Objects.requireNonNull(viewerUuid, "viewerUuid must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        return engine.executeAsync(conn -> {
            boolean initialAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                boolean inserted = saveRevealInternal(conn, viewerUuid, eventId, raterUuid, cost, createdAt);
                if (inserted && compensationId != null && compensationRepository != null) {
                    compensationRepository.markEventWrittenInternal(conn, compensationId);
                }
                conn.commit();
                if (inserted && compensationId != null && compensationRepository != null) {
                    compensationRepository.deleteCompensationAsync(compensationId)
                            .exceptionally(error -> {
                                LOGGER.log(Level.WARNING, "Failed to delete compensation row " + compensationId + " after reveal commit", error);
                                return null;
                            });
                }
                return inserted;
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(initialAutoCommit);
            }
        });
    }

    /**
     * Persists a reveal record for a viewer and rating event.
     * Returns true if a new row was inserted, or false if already present (conflict do nothing).
     */
    public boolean saveReveal(UUID viewerUuid, long eventId, UUID raterUuid, double cost, Instant createdAt) {
        Objects.requireNonNull(viewerUuid, "viewerUuid must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        return engine.execute(conn -> saveRevealInternal(conn, viewerUuid, eventId, raterUuid, cost, createdAt));
    }

    public CompletableFuture<Boolean> saveRevealAsync(UUID viewerUuid, long eventId, UUID raterUuid, double cost, Instant createdAt) {
        Objects.requireNonNull(viewerUuid, "viewerUuid must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        return engine.executeAsync(conn -> saveRevealInternal(conn, viewerUuid, eventId, raterUuid, cost, createdAt));
    }

    private boolean saveRevealInternal(Connection conn, UUID viewerUuid, long eventId, UUID raterUuid, double cost, Instant createdAt) throws SQLException {
        String sql = """
            INSERT INTO rater_reveal (viewer_uuid, event_id, rater_uuid, cost, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(viewer_uuid, event_id) DO NOTHING;
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, viewerUuid.toString());
            ps.setLong(2, eventId);
            if (raterUuid != null) {
                ps.setString(3, raterUuid.toString());
            } else {
                ps.setNull(3, Types.VARCHAR);
            }
            ps.setDouble(4, cost);
            ps.setString(5, StorageTimestamps.format(createdAt));
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Returns the set of all event IDs revealed to a specific viewer.
     */
    public Set<Long> findRevealedEventsByViewer(UUID viewerUuid) {
        Objects.requireNonNull(viewerUuid, "viewerUuid must not be null");
        return engine.execute(conn -> findRevealedEventsByViewerInternal(conn, viewerUuid));
    }

    public CompletableFuture<Set<Long>> findRevealedEventsByViewerAsync(UUID viewerUuid) {
        Objects.requireNonNull(viewerUuid, "viewerUuid must not be null");
        return engine.executeAsync(conn -> findRevealedEventsByViewerInternal(conn, viewerUuid));
    }

    private Set<Long> findRevealedEventsByViewerInternal(Connection conn, UUID viewerUuid) throws SQLException {
        String sql = "SELECT event_id FROM rater_reveal WHERE viewer_uuid = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, viewerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                Set<Long> set = new HashSet<>();
                while (rs.next()) {
                    set.add(rs.getLong("event_id"));
                }
                return set;
            }
        }
    }

    /**
     * Checks whether a specific rating event has been revealed to a specific viewer.
     */
    public boolean isRevealed(UUID viewerUuid, long eventId) {
        Objects.requireNonNull(viewerUuid, "viewerUuid must not be null");
        return engine.execute(conn -> isRevealedInternal(conn, viewerUuid, eventId));
    }

    public CompletableFuture<Boolean> isRevealedAsync(UUID viewerUuid, long eventId) {
        Objects.requireNonNull(viewerUuid, "viewerUuid must not be null");
        return engine.executeAsync(conn -> isRevealedInternal(conn, viewerUuid, eventId));
    }

    private boolean isRevealedInternal(Connection conn, UUID viewerUuid, long eventId) throws SQLException {
        String sql = "SELECT 1 FROM rater_reveal WHERE viewer_uuid = ? AND event_id = ? LIMIT 1;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, viewerUuid.toString());
            ps.setLong(2, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
