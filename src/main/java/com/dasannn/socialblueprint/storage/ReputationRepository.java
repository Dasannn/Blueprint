package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.DecayConfig;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.NonPlayerTarget;
import com.dasannn.socialblueprint.domain.PlayerProfile;
import com.dasannn.socialblueprint.feature.legacy.LegacyImportReport;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.PsychosisEvent;

/**
 * Repository for {@link ReputationEvent}s per T-018, T-019 and ARCHITECTURE.md §4.
 * Writes invalidate the target in {@link StatusCache}.
 */
public final class ReputationRepository {

    private static final Logger LOGGER = Logger.getLogger(ReputationRepository.class.getName());

    private final StorageEngine engine;
    private final StatusCache statusCache;
    private final java.util.function.Supplier<DecayConfig> decayConfigSupplier;
    private final java.time.Clock clock;
    private final java.util.List<java.util.function.Consumer<PlayerId>> invalidationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<Long> warnedCorruptRowIds = ConcurrentHashMap.newKeySet();

    public ReputationRepository(
            StorageEngine engine,
            StatusCache statusCache,
            java.util.function.Supplier<DecayConfig> decayConfigSupplier,
            java.time.Clock clock
    ) {
        this.engine = Objects.requireNonNull(engine, "StorageEngine must not be null");
        this.statusCache = Objects.requireNonNull(statusCache, "StatusCache must not be null");
        this.decayConfigSupplier = (decayConfigSupplier != null)
                ? decayConfigSupplier
                : () -> DecayConfig.defaults();
        this.clock = (clock != null) ? clock : java.time.Clock.systemUTC();
    }

    public ReputationRepository(
            StorageEngine engine,
            StatusCache statusCache,
            java.util.function.Supplier<DecayConfig> decayConfigSupplier
    ) {
        this(engine, statusCache, decayConfigSupplier, java.time.Clock.systemUTC());
    }

    public ReputationRepository(StorageEngine engine, StatusCache statusCache) {
        this(engine, statusCache, () -> DecayConfig.defaults(), java.time.Clock.systemUTC());
    }

    public void addInvalidationListener(java.util.function.Consumer<PlayerId> listener) {
        if (listener != null) {
            invalidationListeners.add(listener);
        }
    }

    public StatusCache statusCache() {
        return statusCache;
    }

    boolean hasWarnedCorruptRow(long id) {
        return warnedCorruptRowIds.contains(id);
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

    ReputationEvent saveInternal(Connection conn, ReputationEvent event) throws SQLException {
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
                    ReputationEvent event = mapRow(rs);
                    if (event != null) {
                        list.add(event);
                    }
                }
                return list;
            }
        }
    }

    List<ReputationEvent> findByTargetStrictInternal(Connection conn, String target) throws SQLException {
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
                    list.add(mapRowStrict(rs));
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
                    compensationRepository.deleteCompensationAsync(compensationId)
                            .exceptionally(error -> {
                                LOGGER.log(Level.WARNING, "Failed to delete compensation row " + compensationId + " after reputation commit", error);
                                return null;
                            });
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
        return executeAdminAdjustmentAsync(actorId, targetId, kind, amount, operation, reason, now, auditRepository, decayConfigSupplier.get());
    }

    public CompletableFuture<AdminAdjustmentResult> executeAdminAdjustmentAsync(
            PlayerId actorId,
            PlayerId targetId,
            HonorKind kind,
            int amount,
            String operation,
            String reason,
            Instant now,
            AuditRepository auditRepository,
            DecayConfig decay
    ) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(auditRepository, "auditRepository must not be null");

        DecayConfig activeDecay = (decay != null) ? decay : decayConfigSupplier.get();

        return engine.executeAsync(conn -> {
            boolean initialAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                List<ReputationEvent> events = findByTargetStrictInternal(conn, targetId.toString());
                Status before = Status.fromEvents(events, activeDecay, now);

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
                        ReputationEvent event = mapRow(rs);
                        if (event != null) {
                            list.add(event);
                        }
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
                        ReputationEvent event = mapRow(rs);
                        if (event != null) {
                            list.add(event);
                        }
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
                        ReputationEvent event = mapRow(rs);
                        if (event != null) {
                            list.add(event);
                        }
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
                    while (rs.next()) {
                        ReputationEvent event = mapRow(rs);
                        if (event != null) {
                            return Optional.of(event);
                        }
                    }
                    return Optional.empty();
                }
            }
        });
    }

    /**
     * Derives status from cache, rebuilding from stored events if absent using active decay configuration and current time.
     */
    public Status getStatus(PlayerId target) {
        Objects.requireNonNull(target, "Target must not be null");
        return getStatus(target, decayConfigSupplier.get(), clock.instant());
    }

    public Status getStatus(PlayerId target, DecayConfig decay, Instant now) {
        Objects.requireNonNull(target, "Target must not be null");
        return statusCache.getOrRebuild(target, () -> findByTarget(target), decay, now);
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
        return executeKillPenaltyAsync(killerId, victimId, worldName, now, settings, psychosisRepository,
                com.dasannn.socialblueprint.domain.MindInput.KILL.defaults());
    }

    public CompletableFuture<KillPenaltyResult> executeKillPenaltyAsync(
            PlayerId killerId, PlayerId victimId, String worldName, Instant now, KillPenaltySettings settings,
            PsychosisRepository psychosisRepository, com.dasannn.socialblueprint.domain.MindInputConfig mindInput
    ) {
        Objects.requireNonNull(killerId, "killerId must not be null");
        Objects.requireNonNull(victimId, "victimId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        Objects.requireNonNull(psychosisRepository, "psychosisRepository must not be null");
        psychosisRepository.notifyKill(new PsychosisEvent(0L, killerId, victimId, CombatContext.OPEN, now));

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
                psychosisEvent = psychosisRepository.saveInternal(conn, psychosisEvent, mindInput);

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
            psychosisRepository.notifyKill(result.psychosisEvent());
            return result;
        });
    }

    ReputationEvent mapRowStrict(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String targetStr = rs.getString("target_uuid");
        try {
            if (targetStr == null) {
                throw new IllegalArgumentException("Target UUID cannot be null");
            }

            String actorStr = rs.getString("actor_uuid");
            PlayerId actor = actorStr != null ? PlayerId.fromString(actorStr) : null;
            PlayerId target = PlayerId.fromString(targetStr);
            int delta = rs.getInt("delta");
            HonorKind kind = HonorKind.fromDbValue(rs.getString("kind"));
            double cost = rs.getDouble("cost");
            String reason = rs.getString("reason");
            Instant createdAt = StorageTimestamps.parse(rs.getString("created_at"));

            return new ReputationEvent(id, actor, target, delta, kind, cost, reason, createdAt);
        } catch (IllegalArgumentException | DateTimeParseException | NullPointerException ex) {
            throw new StorageException(
                    "Corrupt reputation event row id=" + id + " for target=" + targetStr + ": " + ex.getMessage(), ex);
        }
    }

    private ReputationEvent mapRow(ResultSet rs) throws SQLException {
        try {
            return mapRowStrict(rs);
        } catch (StorageException ex) {
            long id = rs.getLong("id");
            String targetStr = rs.getString("target_uuid");
            if (warnedCorruptRowIds.add(id)) {
                String causeMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                LOGGER.log(Level.WARNING,
                        "Skipping corrupt reputation event row id=" + id + " for target=" + targetStr + ": " + causeMsg);
            }
            return null;
        }
    }

    public boolean hasLegacyImport(PlayerId target) {
        Objects.requireNonNull(target, "Target must not be null");
        return engine.execute(conn -> hasLegacyImportInternal(conn, target.toString()));
    }

    public CompletableFuture<Boolean> hasLegacyImportAsync(PlayerId target) {
        Objects.requireNonNull(target, "Target must not be null");
        return engine.executeAsync(conn -> hasLegacyImportInternal(conn, target.toString()));
    }

    Optional<Integer> findLegacyImportDeltaInternal(Connection conn, String targetUuid) throws SQLException {
        String sql = """
            SELECT delta FROM reputation_event
            WHERE target_uuid = ? AND kind = 'legacy_import'
            LIMIT 1;
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getInt("delta"));
                }
                return Optional.empty();
            }
        }
    }

    boolean hasLegacyImportInternal(Connection conn, String targetUuid) throws SQLException {
        return findLegacyImportDeltaInternal(conn, targetUuid).isPresent();
    }

    public record LegacyCandidate(PlayerId target, String lastKnownName, int score) {
        public LegacyCandidate {
            Objects.requireNonNull(target, "target must not be null");
        }
    }

    public CompletableFuture<LegacyImportReport> executeLegacyImportAsync(
            List<LegacyCandidate> candidates,
            List<LegacyImportReport.SkippedEntry> preSkipped,
            int totalRead,
            PlayerId actor,
            String sourceName,
            ProfileRepository profileRepository,
            AuditRepository auditRepository,
            Instant now
    ) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(preSkipped, "preSkipped must not be null");
        Objects.requireNonNull(now, "now must not be null");

        return engine.executeAsync(conn -> {
            boolean initialAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                List<LegacyImportReport.SkippedEntry> allSkipped = new ArrayList<>(preSkipped);
                List<PlayerId> importedTargets = new ArrayList<>();
                Map<PlayerId, Integer> importedDeltas = new HashMap<>();

                for (LegacyCandidate candidate : candidates) {
                    if (importedDeltas.containsKey(candidate.target())) {
                        int kept = importedDeltas.get(candidate.target());
                        int incoming = candidate.score();
                        String displayName = candidate.lastKnownName() != null ? candidate.lastKnownName() : candidate.target().toString();
                        allSkipped.add(new LegacyImportReport.SkippedEntry(
                                displayName,
                                LegacyImportReport.SkipReason.ALREADY_IMPORTED,
                                "Already imported (kept " + kept + ", ignored " + incoming + ")",
                                kept,
                                incoming
                        ));
                        continue;
                    }

                    Optional<Integer> existingDelta = findLegacyImportDeltaInternal(conn, candidate.target().toString());
                    if (existingDelta.isPresent()) {
                        int kept = existingDelta.get();
                        int incoming = candidate.score();
                        String displayName = candidate.lastKnownName() != null ? candidate.lastKnownName() : candidate.target().toString();
                        allSkipped.add(new LegacyImportReport.SkippedEntry(
                                displayName,
                                LegacyImportReport.SkipReason.ALREADY_IMPORTED,
                                "Already imported (kept " + kept + ", ignored " + incoming + ")",
                                kept,
                                incoming
                        ));
                        continue;
                    }

                    ReputationEvent repEvent = new ReputationEvent(
                            0L,
                            null,
                            candidate.target(),
                            candidate.score(),
                            HonorKind.LEGACY_IMPORT,
                            0.0,
                            "commands.admin.import.reason",
                            now
                    );
                    saveInternal(conn, repEvent);
                    importedTargets.add(candidate.target());
                    importedDeltas.put(candidate.target(), candidate.score());

                    if (candidate.lastKnownName() != null && profileRepository != null) {
                        PlayerProfile profile = PlayerProfile.create(candidate.target(), candidate.lastKnownName(), now);
                        profileRepository.insertIfAbsentInternal(conn, profile);
                    }
                }

                if (!importedTargets.isEmpty() && auditRepository != null) {
                    PlayerId auditActor = actor != null ? actor : PlayerId.CONSOLE;
                    String targetIdentifier = sourceName != null && !sourceName.isBlank() ? sourceName : "legacy_config";
                    AuditEvent audit = new AuditEvent(
                            0L,
                            auditActor,
                            "legacy_import",
                            NonPlayerTarget.of(targetIdentifier).identifier(),
                            "0",
                            String.valueOf(importedTargets.size()),
                            now
                    );
                    auditRepository.saveInternal(conn, audit);
                }

                conn.commit();

                return new LegacyImportReport(
                        totalRead,
                        importedTargets.size(),
                        allSkipped.size(),
                        allSkipped
                );
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(initialAutoCommit);
            }
        }).thenApply(report -> {
            for (LegacyCandidate c : candidates) {
                notifyInvalidation(c.target());
            }
            return report;
        });
    }
}
