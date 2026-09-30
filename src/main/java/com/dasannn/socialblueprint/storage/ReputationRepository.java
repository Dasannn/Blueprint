package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Status;

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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.PsychosisEvent;

/**
 * Repository for {@link ReputationEvent}s per T-018, T-019 and ARCHITECTURE.md §4.
 * Writes invalidate the target in {@link StatusCache}.
 */
public final class ReputationRepository {

    private final StorageEngine engine;
    private final StatusCache statusCache;
    private final java.util.List<java.util.function.Consumer<PlayerId>> invalidationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public ReputationRepository(StorageEngine engine, StatusCache statusCache) {
        this.engine = Objects.requireNonNull(engine, "StorageEngine must not be null");
        this.statusCache = Objects.requireNonNull(statusCache, "StatusCache must not be null");
    }

    public void addInvalidationListener(java.util.function.Consumer<PlayerId> listener) {
        if (listener != null) {
            invalidationListeners.add(listener);
        }
    }

    public StatusCache statusCache() {
        return statusCache;
    }

    private void notifyInvalidation(PlayerId target) {
        statusCache.invalidate(target);
        for (java.util.function.Consumer<PlayerId> listener : invalidationListeners) {
            listener.accept(target);
        }
    }

    /**
     * Saves a reputation event and invalidates the target player in {@link StatusCache}.
     * Returns the persisted event with its generated database ID.
     */
    public ReputationEvent save(ReputationEvent event) {
        Objects.requireNonNull(event, "Event must not be null");
        ReputationEvent persisted = engine.execute(conn -> saveInternal(conn, event));
        notifyInvalidation(persisted.target());
        return persisted;
    }

    public CompletableFuture<ReputationEvent> saveAsync(ReputationEvent event) {
        Objects.requireNonNull(event, "Event must not be null");
        return engine.executeAsync(conn -> saveInternal(conn, event))
                .thenApply(persisted -> {
                    notifyInvalidation(persisted.target());
                    return persisted;
                });
    }

    private ReputationEvent saveInternal(Connection conn, ReputationEvent event) throws SQLException {
        String sql = """
            INSERT INTO reputation_event (actor_uuid, target_uuid, delta, kind, cost, reason, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?);
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            if (event.actor() != null) {
                ps.setString(1, event.actor().toString());
            } else {
                ps.setNull(1, Types.VARCHAR);
            }
            ps.setString(2, event.target().toString());
            ps.setInt(3, event.delta());
            ps.setString(4, event.kind().dbValue());
            ps.setDouble(5, event.cost());
            if (event.reason() != null) {
                ps.setString(6, event.reason());
            } else {
                ps.setNull(6, Types.VARCHAR);
            }
            ps.setString(7, StorageTimestamps.format(event.createdAt()));

            ps.executeUpdate();
            long generatedId = 0L;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    generatedId = keys.getLong(1);
                }
            }
            return new ReputationEvent(
                    generatedId,
                    event.actor(),
                    event.target(),
                    event.delta(),
                    event.kind(),
                    event.cost(),
                    event.reason(),
                    event.createdAt()
            );
        }
    }

    List<ReputationEvent> findByTargetInternal(Connection conn, String target) throws SQLException {
        String sql = """
            SELECT id, actor_uuid, target_uuid, delta, kind, cost, reason, created_at
            FROM reputation_event
            WHERE target_uuid = ?
            ORDER BY id ASC;
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, target);
            try (ResultSet rs = ps.executeQuery()) {
                List<ReputationEvent> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            }
        }
    }

    public List<ReputationEvent> findByTarget(PlayerId target) {
        Objects.requireNonNull(target, "Target must not be null");
        return engine.execute(conn -> findByTargetInternal(conn, target.toString()));
    }

    public CompletableFuture<List<ReputationEvent>> findByTargetAsync(PlayerId target) {
        Objects.requireNonNull(target, "Target must not be null");
        return engine.executeAsync(conn -> findByTargetInternal(conn, target.toString()));
    }

    public CompletableFuture<ReputationEvent> commitPlayerHonorAsync(
            ReputationEvent event,
            Long compensationId,
            CompensationRepository compensationRepository
    ) {
        Objects.requireNonNull(event, "Event must not be null");
        return engine.executeAsync(conn -> {
            boolean initialAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                ReputationEvent saved = saveInternal(conn, event);
                if (compensationId != null && compensationRepository != null) {
                    compensationRepository.markEventWrittenInternal(conn, compensationId);
                }
                conn.commit();
                if (compensationId != null && compensationRepository != null) {
                    compensationRepository.deleteCompensationAsync(compensationId);
                }
                return saved;
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(initialAutoCommit);
            }
        }).thenApply(saved -> {
            notifyInvalidation(saved.target());
            return saved;
        });
    }

    public CompletableFuture<AdminAdjustmentResult> executeAdminAdjustmentAsync(
            PlayerId actorId,
            PlayerId targetId,
            HonorKind kind,
            int amount,
            String operation,
            String reason,
            Instant now,
            AuditRepository auditRepository
    ) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(auditRepository, "auditRepository must not be null");

        return engine.executeAsync(conn -> {
            boolean initialAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                List<ReputationEvent> events = findByTargetInternal(conn, targetId.toString());
                Status before = Status.fromEvents(events);

                int delta;
                int afterScore;
                if (kind == HonorKind.ADMIN_RESET) {
                    afterScore = 0;
                    try {
                        delta = Math.negateExact(before.value());
                        ReputationEvent repEvent = new ReputationEvent(
                                actorId,
                                targetId,
                                delta,
                                kind,
                                0.0,
                                reason,
                                now
                        );
                        saveInternal(conn, repEvent);
                    } catch (ArithmeticException e) {
                        // Negating Integer.MIN_VALUE overflows 32-bit signed int.
                        // Compensate with two events in the same transaction summing to +2,147,483,648:
                        // part1 = Integer.MAX_VALUE (2,147,483,647) and part2 = 1.
                        delta = Integer.MAX_VALUE;
                        ReputationEvent part1 = new ReputationEvent(
                                actorId,
                                targetId,
                                Integer.MAX_VALUE,
                                kind,
                                0.0,
                                reason,
                                now
                        );
                        ReputationEvent part2 = new ReputationEvent(
                                actorId,
                                targetId,
                                1,
                                kind,
                                0.0,
                                reason,
                                now
                        );
                        saveInternal(conn, part1);
                        saveInternal(conn, part2);
                    }
                } else {
                    delta = amount;
                    afterScore = before.value() + delta;
                    ReputationEvent repEvent = new ReputationEvent(
                            actorId,
                            targetId,
                            delta,
                            kind,
                            0.0,
                            reason,
                            now
                    );
                    saveInternal(conn, repEvent);
                }

                AuditEvent audit = new AuditEvent(
                        actorId,
                        operation,
                        targetId,
                        String.valueOf(before.value()),
                        String.valueOf(afterScore),
                        now
                );
                auditRepository.saveInternal(conn, audit);

                conn.commit();
                return new AdminAdjustmentResult(before.value(), afterScore, delta);
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(initialAutoCommit);
            }
        }).thenApply(result -> {
            notifyInvalidation(targetId);
            return result;
        });
    }

    public List<ReputationEvent> findByActor(PlayerId actor) {
        Objects.requireNonNull(actor, "Actor must not be null");
        return engine.execute(conn -> {
            String sql = """
                SELECT id, actor_uuid, target_uuid, delta, kind, cost, reason, created_at
                FROM reputation_event
                WHERE actor_uuid = ?
                ORDER BY id ASC;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, actor.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    List<ReputationEvent> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            }
        });
    }

    public CompletableFuture<List<ReputationEvent>> findByActorAsync(PlayerId actor) {
        Objects.requireNonNull(actor, "Actor must not be null");
        return engine.executeAsync(conn -> {
            String sql = """
                SELECT id, actor_uuid, target_uuid, delta, kind, cost, reason, created_at
                FROM reputation_event
                WHERE actor_uuid = ?
                ORDER BY id ASC;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, actor.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    List<ReputationEvent> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            }
        });
    }

    public CompletableFuture<List<ReputationEvent>> findByActorSinceAsync(PlayerId actor, Instant since) {
        Objects.requireNonNull(actor, "Actor must not be null");
        Objects.requireNonNull(since, "Instant since must not be null");
        return engine.executeAsync(conn -> {
            String sql = """
                SELECT id, actor_uuid, target_uuid, delta, kind, cost, reason, created_at
                FROM reputation_event
                WHERE actor_uuid = ?
                  AND created_at > ?
                ORDER BY id ASC;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, actor.toString());
                ps.setString(2, StorageTimestamps.format(since));
                try (ResultSet rs = ps.executeQuery()) {
                    List<ReputationEvent> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            }
        });
    }

    public CompletableFuture<Optional<ReputationEvent>> findLastRatingBetweenAsync(PlayerId actor, PlayerId target) {
        Objects.requireNonNull(actor, "Actor must not be null");
        Objects.requireNonNull(target, "Target must not be null");
        return engine.executeAsync(conn -> {
            String sql = """
                SELECT id, actor_uuid, target_uuid, delta, kind, cost, reason, created_at
                FROM reputation_event
                WHERE actor_uuid = ?
                  AND target_uuid = ?
                  AND (kind = 'positive' OR kind = 'negative')
                ORDER BY id DESC
                LIMIT 1;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, actor.toString());
                ps.setString(2, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    /**
     * Derives status from cache, rebuilding from stored events if absent.
     */
    public Status getStatus(PlayerId target) {
        Objects.requireNonNull(target, "Target must not be null");
        return statusCache.getOrRebuild(target, () -> findByTarget(target));
    }

    public int countActorRatingsSince(PlayerId actor, Instant since) {
        Objects.requireNonNull(actor, "Actor must not be null");
        Objects.requireNonNull(since, "Instant since must not be null");
        return engine.execute(conn -> {
            String sql = """
                SELECT COUNT(*) FROM reputation_event
                WHERE actor_uuid = ?
                  AND (kind = 'positive' OR kind = 'negative')
                  AND created_at > ?;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, actor.toString());
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

    public int countPairRatingsSince(PlayerId actor, PlayerId target, HonorKind kind, Instant since) {
        Objects.requireNonNull(actor, "Actor must not be null");
        Objects.requireNonNull(target, "Target must not be null");
        Objects.requireNonNull(kind, "HonorKind must not be null");
        Objects.requireNonNull(since, "Instant since must not be null");

        String dbKind = kind.isPositive() ? "positive" : "negative";

        return engine.execute(conn -> {
            String sql = """
                SELECT COUNT(*) FROM reputation_event
                WHERE actor_uuid = ?
                  AND target_uuid = ?
                  AND kind = ?
                  AND created_at > ?;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, actor.toString());
                ps.setString(2, target.toString());
                ps.setString(3, dbKind);
                ps.setString(4, StorageTimestamps.format(since));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                    return 0;
                }
            }
        });
    }

    public StorageEngine engine() {
        return engine;
    }

    int calculateSystemKillLossSinceInternal(Connection conn, PlayerId target, Instant since) throws SQLException {
        String sql = """
            SELECT COALESCE(SUM(ABS(delta)), 0)
            FROM reputation_event
            WHERE target_uuid = ?
              AND kind = 'system_kill'
              AND created_at > ?;
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, target.toString());
            ps.setString(2, StorageTimestamps.format(since));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        }
    }

    public int calculateSystemKillLossSince(PlayerId target, Instant since) {
        Objects.requireNonNull(target, "Target must not be null");
        Objects.requireNonNull(since, "Since must not be null");
        return engine.execute(conn -> calculateSystemKillLossSinceInternal(conn, target, since));
    }

    public CompletableFuture<Integer> calculateSystemKillLossSinceAsync(PlayerId target, Instant since) {
        Objects.requireNonNull(target, "Target must not be null");
        Objects.requireNonNull(since, "Since must not be null");
        return engine.executeAsync(conn -> calculateSystemKillLossSinceInternal(conn, target, since));
    }

    int countKillPenaltyClaimsSinceInternal(Connection conn, PlayerId killer, PlayerId victim, Instant since) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM kill_penalty_claim
            WHERE killer_uuid = ? AND victim_uuid = ? AND created_at > ?;
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
    }

    public int countKillPenaltyClaimsSince(PlayerId killer, PlayerId victim, Instant since) {
        Objects.requireNonNull(killer, "Killer must not be null");
        Objects.requireNonNull(victim, "Victim must not be null");
        Objects.requireNonNull(since, "Since must not be null");
        return engine.execute(conn -> countKillPenaltyClaimsSinceInternal(conn, killer, victim, since));
    }

    public CompletableFuture<Integer> countKillPenaltyClaimsSinceAsync(PlayerId killer, PlayerId victim, Instant since) {
        Objects.requireNonNull(killer, "Killer must not be null");
        Objects.requireNonNull(victim, "Victim must not be null");
        Objects.requireNonNull(since, "Since must not be null");
        return engine.executeAsync(conn -> countKillPenaltyClaimsSinceInternal(conn, killer, victim, since));
    }

    void saveKillPenaltyClaimInternal(Connection conn, PlayerId killer, PlayerId victim, Instant createdAt) throws SQLException {
        String sql = """
            INSERT INTO kill_penalty_claim (killer_uuid, victim_uuid, created_at)
            VALUES (?, ?, ?);
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, killer.toString());
            ps.setString(2, victim.toString());
            ps.setString(3, StorageTimestamps.format(createdAt));
            ps.executeUpdate();
        }
    }

    public CompletableFuture<KillPenaltyResult> executeKillPenaltyAsync(
            PlayerId killerId,
            PlayerId victimId,
            String worldName,
            Instant now,
            KillPenaltySettings settings,
            PsychosisRepository psychosisRepository
    ) {
        Objects.requireNonNull(killerId, "killerId must not be null");
        Objects.requireNonNull(victimId, "victimId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        Objects.requireNonNull(psychosisRepository, "psychosisRepository must not be null");

        return engine.executeAsync(conn -> {
            boolean initialAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                // Fail closed: worldName == null means world is unknown/unresolvable -> exempt from penalty (Finding 5)
                boolean worldEligible = worldName != null && !settings.isWorldExempt(worldName);
                boolean penaltyEligible = settings.enabled() && worldEligible;

                int penaltyDelta = 0;
                ReputationEvent repEvent = null;

                if (penaltyEligible) {
                    Instant cooldownSince = now.minus(settings.pairCooldown());
                    int claimsInCooldown = countKillPenaltyClaimsSinceInternal(conn, killerId, victimId, cooldownSince);
                    if (claimsInCooldown == 0) {
                        Instant capSince = now.minus(settings.capWindow());
                        int currentLoss = calculateSystemKillLossSinceInternal(conn, killerId, capSince);
                        int remainingLoss = settings.maxLoss() - currentLoss;
                        if (remainingLoss > 0) {
                            int calculatedDelta = Math.max(settings.delta(), -remainingLoss);
                            if (calculatedDelta < 0) {
                                penaltyDelta = calculatedDelta;
                                repEvent = new ReputationEvent(
                                        0L,
                                        null,
                                        killerId,
                                        penaltyDelta,
                                        HonorKind.SYSTEM_KILL,
                                        0.0,
                                        "kill-penalty.reason",
                                        now
                                );
                                repEvent = saveInternal(conn, repEvent);
                                saveKillPenaltyClaimInternal(conn, killerId, victimId, now);
                            }
                        }
                    }
                }

                // Insert PsychosisEvent inside the exact same transaction (Finding 2)
                PsychosisEvent psychosisEvent = new PsychosisEvent(0L, killerId, victimId, CombatContext.OPEN, now);
                psychosisEvent = psychosisRepository.saveInternal(conn, psychosisEvent);

                conn.commit();
                return new KillPenaltyResult(penaltyDelta, repEvent, psychosisEvent);
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(initialAutoCommit);
            }
        }).thenApply(result -> {
            if (result.wasPenaltyCharged()) {
                notifyInvalidation(killerId);
            }
            psychosisRepository.notifyInvalidation(killerId);
            return result;
        });
    }

    private static ReputationEvent mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String actorStr = rs.getString("actor_uuid");
        PlayerId actor = actorStr != null ? PlayerId.fromString(actorStr) : null;
        PlayerId target = PlayerId.fromString(rs.getString("target_uuid"));
        int delta = rs.getInt("delta");
        HonorKind kind = HonorKind.fromDbValue(rs.getString("kind"));
        double cost = rs.getDouble("cost");
        String reason = rs.getString("reason");
        Instant createdAt = StorageTimestamps.parse(rs.getString("created_at"));

        return new ReputationEvent(id, actor, target, delta, kind, cost, reason, createdAt);
    }
}
