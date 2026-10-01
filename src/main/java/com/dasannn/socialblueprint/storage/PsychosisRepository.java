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
    private final java.util.List<java.util.function.Consumer<PsychosisEvent>> killListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addKillListener(java.util.function.Consumer<PsychosisEvent> listener) { killListeners.add(listener); }

    /** Called with plain data before enqueueing a kill, then with its persisted id. */
    public void notifyKill(PsychosisEvent event) {
        if (event.context() == CombatContext.OPEN) for (var listener : killListeners) listener.accept(event);
    }

    public record Streak(double activeMillis, long lastKillId, Instant lastKillAt) {}

    public Streak loadStreak(PlayerId player) {
        return engine.execute(conn -> {
            double credit = 0;
            try (PreparedStatement ps = conn.prepareStatement("SELECT active_millis FROM psychosis_streak WHERE player_uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) credit = rs.getDouble(1); }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT MAX(id), MAX(created_at) FROM psychosis_event WHERE killer_uuid = ? AND context = 'open'")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    String timestamp = rs.getString(2);
                    return new Streak(credit, rs.getLong(1), timestamp == null ? null : StorageTimestamps.parse(timestamp));
                }
            }
        });
    }

    public CompletableFuture<Void> saveStreakAsync(PlayerId player, double millis, long killId) {
        if (!Double.isFinite(millis) || millis < 0) throw new IllegalArgumentException("Invalid active duration");
        return engine.runAsync(conn -> {
            // A queued pre-kill flush must never restore the broken streak after the kill commits.
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO psychosis_streak(player_uuid, active_millis)
                    SELECT ?, ? WHERE NOT EXISTS
                        (SELECT 1 FROM psychosis_event WHERE killer_uuid = ? AND context = 'open' AND id > ?)
                    ON CONFLICT(player_uuid) DO UPDATE SET active_millis = excluded.active_millis
                    """)) {
                ps.setString(1, player.toString()); ps.setDouble(2, millis);
                ps.setString(3, player.toString()); ps.setLong(4, killId);
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> clampStreaksAsync(double maxMillis) {
        return engine.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE psychosis_streak SET active_millis = MIN(active_millis, ?)")) {
                ps.setDouble(1, maxMillis); ps.executeUpdate();
            }
        });
    }
    private final java.util.List<java.util.function.Consumer<PlayerId>> invalidationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public PsychosisRepository(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "StorageEngine must not be null");
    }

    public void addInvalidationListener(java.util.function.Consumer<PlayerId> listener) {
        if (listener != null) {
            invalidationListeners.add(listener);
        }
    }

    void notifyInvalidation(PlayerId player) {
        for (java.util.function.Consumer<PlayerId> listener : invalidationListeners) {
            listener.accept(player);
        }
    }

    public PsychosisEvent save(PsychosisEvent event) {
        Objects.requireNonNull(event, "Event must not be null");
        notifyKill(event);
        PsychosisEvent persisted = engine.execute(conn -> saveTransactional(conn, event));
        notifyKill(persisted);
        notifyInvalidation(persisted.killer());
        return persisted;
    }

    public CompletableFuture<PsychosisEvent> saveAsync(PsychosisEvent event) {
        Objects.requireNonNull(event, "Event must not be null");
        notifyKill(event);
        return engine.executeAsync(conn -> saveTransactional(conn, event))
                .thenApply(persisted -> {
                    notifyKill(persisted);
                    notifyInvalidation(persisted.killer());
                    return persisted;
                });
    }

    private PsychosisEvent saveTransactional(Connection conn, PsychosisEvent event) throws SQLException {
        boolean auto = conn.getAutoCommit();
        if (!auto) return saveInternal(conn, event);
        try {
            conn.setAutoCommit(false);
            PsychosisEvent saved = saveInternal(conn, event);
            conn.commit();
            return saved;
        } catch (SQLException | RuntimeException ex) {
            conn.rollback();
            throw ex;
        } finally { conn.setAutoCommit(auto); }
    }

    PsychosisEvent saveInternal(Connection conn, PsychosisEvent event) throws SQLException {
        if (event.context() == CombatContext.OPEN) {
            try (PreparedStatement reset = conn.prepareStatement("UPDATE psychosis_streak SET active_millis = 0 WHERE player_uuid = ?")) {
                reset.setString(1, event.killer().toString());
                reset.executeUpdate();
            }
        }
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
