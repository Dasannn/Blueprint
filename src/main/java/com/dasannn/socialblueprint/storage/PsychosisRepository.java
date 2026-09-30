package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PsychosisEvent;

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
import java.util.concurrent.CompletableFuture;

/**
 * Repository for {@link PsychosisEvent}s per T-018 and ARCHITECTURE.md §4.
 * Completely separate from social status.
 */
public final class PsychosisRepository {

    private final StorageEngine engine;
    private final java.util.List<java.util.function.Consumer<PlayerId>> invalidationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public PsychosisRepository(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "StorageEngine must not be null");
    }

    public void addInvalidationListener(java.util.function.Consumer<PlayerId> listener) {
        if (listener != null) {
            invalidationListeners.add(listener);
        }
    }

    private void notifyInvalidation(PlayerId player) {
        for (java.util.function.Consumer<PlayerId> listener : invalidationListeners) {
            listener.accept(player);
        }
    }

    public PsychosisEvent save(PsychosisEvent event) {
        Objects.requireNonNull(event, "Event must not be null");
        PsychosisEvent persisted = engine.execute(conn -> saveInternal(conn, event));
        notifyInvalidation(persisted.killer());
        return persisted;
    }

    public CompletableFuture<PsychosisEvent> saveAsync(PsychosisEvent event) {
        Objects.requireNonNull(event, "Event must not be null");
        return engine.executeAsync(conn -> saveInternal(conn, event))
                .thenApply(persisted -> {
                    notifyInvalidation(persisted.killer());
                    return persisted;
                });
    }

    private PsychosisEvent saveInternal(Connection conn, PsychosisEvent event) throws SQLException {
        String sql = """
            INSERT INTO psychosis_event (killer_uuid, victim_uuid, context, created_at)
            VALUES (?, ?, ?, ?);
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, event.killer().toString());
            ps.setString(2, event.victim().toString());
            ps.setString(3, event.context().dbValue());
            ps.setString(4, StorageTimestamps.format(event.createdAt()));

            ps.executeUpdate();
            long generatedId = 0L;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    generatedId = keys.getLong(1);
                }
            }
            return new PsychosisEvent(
                    generatedId,
                    event.killer(),
                    event.victim(),
                    event.context(),
                    event.createdAt()
            );
        }
    }

    public List<PsychosisEvent> findKillsByKillerSince(PlayerId killer, Instant since) {
        Objects.requireNonNull(killer, "Killer must not be null");
        Objects.requireNonNull(since, "Since must not be null");
        return engine.execute(conn -> {
            String sql = """
                SELECT id, killer_uuid, victim_uuid, context, created_at
                FROM psychosis_event
                WHERE killer_uuid = ? AND created_at > ?
                ORDER BY id ASC;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, killer.toString());
                ps.setString(2, StorageTimestamps.format(since));
                try (ResultSet rs = ps.executeQuery()) {
                    List<PsychosisEvent> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            }
        });
    }

    public int countOpenKillsSince(PlayerId killer, Instant since) {
        Objects.requireNonNull(killer, "Killer must not be null");
        Objects.requireNonNull(since, "Since must not be null");
        return engine.execute(conn -> {
            String sql = """
                SELECT COUNT(*) FROM psychosis_event
                WHERE killer_uuid = ? AND context = 'open' AND created_at > ?;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, killer.toString());
                ps.setString(2, StorageTimestamps.format(since));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                    return 0;
                }
            }
        });
    }

    public Optional<PsychosisEvent> findLastOpenKillBetween(PlayerId killer, PlayerId victim) {
        Objects.requireNonNull(killer, "Killer must not be null");
        Objects.requireNonNull(victim, "Victim must not be null");
        return engine.execute(conn -> {
            String sql = """
                SELECT id, killer_uuid, victim_uuid, context, created_at
                FROM psychosis_event
                WHERE killer_uuid = ? AND victim_uuid = ? AND context = 'open'
                ORDER BY id DESC
                LIMIT 1;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, killer.toString());
                ps.setString(2, victim.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Optional<PsychosisEvent>> findLastOpenKillBetweenAsync(PlayerId killer, PlayerId victim) {
        Objects.requireNonNull(killer, "Killer must not be null");
        Objects.requireNonNull(victim, "Victim must not be null");
        return engine.executeAsync(conn -> {
            String sql = """
                SELECT id, killer_uuid, victim_uuid, context, created_at
                FROM psychosis_event
                WHERE killer_uuid = ? AND victim_uuid = ? AND context = 'open'
                ORDER BY id DESC
                LIMIT 1;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, killer.toString());
                ps.setString(2, victim.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    public int countOpenKillsBetweenSince(PlayerId killer, PlayerId victim, Instant since) {
        Objects.requireNonNull(killer, "Killer must not be null");
        Objects.requireNonNull(victim, "Victim must not be null");
        Objects.requireNonNull(since, "Since must not be null");
        return engine.execute(conn -> {
            String sql = """
                SELECT COUNT(*) FROM psychosis_event
                WHERE killer_uuid = ? AND victim_uuid = ? AND context = 'open' AND created_at > ?;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, killer.toString());
                ps.setString(2, victim.toString());
                ps.setString(3, StorageTimestamps.format(since));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                    return 0;
                }
            }
        });
    }

    public CompletableFuture<Integer> countOpenKillsBetweenSinceAsync(PlayerId killer, PlayerId victim, Instant since) {
        Objects.requireNonNull(killer, "Killer must not be null");
        Objects.requireNonNull(victim, "Victim must not be null");
        Objects.requireNonNull(since, "Since must not be null");
        return engine.executeAsync(conn -> {
            String sql = """
                SELECT COUNT(*) FROM psychosis_event
                WHERE killer_uuid = ? AND victim_uuid = ? AND context = 'open' AND created_at > ?;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, killer.toString());
                ps.setString(2, victim.toString());
                ps.setString(3, StorageTimestamps.format(since));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                    return 0;
                }
            }
        });
    }

    private static PsychosisEvent mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        PlayerId killer = PlayerId.fromString(rs.getString("killer_uuid"));
        PlayerId victim = PlayerId.fromString(rs.getString("victim_uuid"));
        CombatContext context = CombatContext.fromDbValue(rs.getString("context"));
        Instant createdAt = StorageTimestamps.parse(rs.getString("created_at"));

        return new PsychosisEvent(id, killer, victim, context, createdAt);
    }
}
