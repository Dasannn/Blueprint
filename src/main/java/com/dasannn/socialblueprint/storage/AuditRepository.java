package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.PlayerId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for {@link AuditEvent}s per T-018, SB-058, SB-064 and ARCHITECTURE.md §4.
 */
public final class AuditRepository {

    private final StorageEngine engine;

    public AuditRepository(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "StorageEngine must not be null");
    }

    public AuditEvent save(AuditEvent event) {
        Objects.requireNonNull(event, "AuditEvent must not be null");
        return engine.execute(conn -> saveInternal(conn, event));
    }

    public CompletableFuture<AuditEvent> saveAsync(AuditEvent event) {
        Objects.requireNonNull(event, "AuditEvent must not be null");
        return engine.executeAsync(conn -> saveInternal(conn, event));
    }

    private AuditEvent saveInternal(Connection conn, AuditEvent event) throws SQLException {
        String sql = """
            INSERT INTO audit_event (actor, operation, target, before, after, created_at)
            VALUES (?, ?, ?, ?, ?, ?);
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, event.actor().toString());
            ps.setString(2, event.operation());
            ps.setString(3, event.target());
            if (event.before() != null) {
                ps.setString(4, event.before());
            } else {
                ps.setNull(4, Types.VARCHAR);
            }
            if (event.after() != null) {
                ps.setString(5, event.after());
            } else {
                ps.setNull(5, Types.VARCHAR);
            }
            ps.setString(6, StorageTimestamps.format(event.createdAt()));

            ps.executeUpdate();
            long generatedId = 0L;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    generatedId = keys.getLong(1);
                }
            }
            return new AuditEvent(
                    generatedId,
                    event.actor(),
                    event.operation(),
                    event.target(),
                    event.before(),
                    event.after(),
                    event.createdAt()
            );
        }
    }

    public List<AuditEvent> findByTarget(PlayerId targetPlayer) {
        Objects.requireNonNull(targetPlayer, "targetPlayer must not be null");
        return findByTarget(targetPlayer.toString());
    }

    public List<AuditEvent> findByTarget(String target) {
        Objects.requireNonNull(target, "Target must not be null");
        return engine.execute(conn -> {
            String sql = """
                SELECT id, actor, operation, target, before, after, created_at
                FROM audit_event
                WHERE target = ?
                ORDER BY id ASC;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, target);
                try (ResultSet rs = ps.executeQuery()) {
                    List<AuditEvent> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            }
        });
    }

    private static AuditEvent mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        PlayerId actor = PlayerId.fromString(rs.getString("actor"));
        String operation = rs.getString("operation");
        String target = rs.getString("target");
        String before = rs.getString("before");
        String after = rs.getString("after");
        Instant createdAt = StorageTimestamps.parse(rs.getString("created_at"));

        return new AuditEvent(id, actor, operation, target, before, after, createdAt);
    }
}
