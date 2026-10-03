package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.DuelConfigSection;
import com.dasannn.socialblueprint.config.KillPenaltyConfigSection;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.domain.duel.ActiveDuelSession;
import com.dasannn.socialblueprint.feature.duel.DuelService;
import com.dasannn.socialblueprint.storage.KillPenaltyResult;
import com.dasannn.socialblueprint.storage.KillPenaltySettings;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Platform combat listener per T-061, T-062, T-063, T-130 to T-133, T-139 and ARCHITECTURE.md §5:
 * - A kill inside an active duel affects neither status nor Psychosis (SB-031).
 * - A kill outside a duel raises Psychosis and applies a status penalty if eligible (SB-032, T-130).
 * - Tracks recent combat damage to distinguish combat logs on quit (SB-033).
 * - Survives disconnects long enough to allow reconnect grace periods.
 * - An attack's duel context is decided when it lands, not when the victim dies (T-139).
 */
public class DuelCombatListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(DuelCombatListener.class.getName());
    private static final int MAX_MAP_SIZE = 1000;
    private static final Duration PROJECTILE_EXPIRATION = Duration.ofSeconds(60);

    private final DuelService duelService;
    private final PsychosisRepository psychosisRepository;
    private final ConfigManager configManager;
    private final ReputationRepository reputationRepository;
    private com.dasannn.socialblueprint.storage.MindRepository mindRepository;

    public void bindMind(com.dasannn.socialblueprint.storage.MindRepository mind) { this.mindRepository = mind; }

    private final Map<PlayerId, AttackRecord> victimAttackRecords = new ConcurrentHashMap<>();
    private final Map<Projectile, ProjectileLaunchRecord> projectileLaunches = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<UUID, ProjectileLaunchRecord> projectileLaunchesByUuid = new ConcurrentHashMap<>();

    record AttackRecord(
            PlayerId attackerId,
            CombatContext context,
            Instant timestamp
    ) {
        AttackRecord {
            Objects.requireNonNull(attackerId, "attackerId must not be null");
            Objects.requireNonNull(context, "context must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }

        boolean isExpired(Instant now, Duration window) {
            if (window == null) {
                return false;
            }
            return timestamp.plus(window).isBefore(now);
        }
    }

    record ProjectileLaunchRecord(
            PlayerId shooterId,
            String duelId,
            Set<PlayerId> participants,
            Instant launchedAt
    ) {
        ProjectileLaunchRecord {
            Objects.requireNonNull(shooterId, "shooterId must not be null");
            Objects.requireNonNull(participants, "participants must not be null");
            Objects.requireNonNull(launchedAt, "launchedAt must not be null");
        }
    }

    public DuelCombatListener(
            DuelService duelService,
            PsychosisRepository psychosisRepository,
            ConfigManager configManager,
            ReputationRepository reputationRepository
    ) {
        this.duelService = Objects.requireNonNull(duelService, "duelService must not be null");
        this.psychosisRepository = Objects.requireNonNull(psychosisRepository, "psychosisRepository must not be null");
        this.configManager = Objects.requireNonNull(configManager, "configManager must not be null");
        this.reputationRepository = reputationRepository;
    }

    public DuelCombatListener(
            DuelService duelService,
            PsychosisRepository psychosisRepository,
            ConfigManager configManager
    ) {
        this(duelService, psychosisRepository, configManager, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        handleProjectileLaunch(event.getEntity());
    }

    void handleProjectileLaunch(Projectile projectile) {
        handleProjectileLaunch(projectile, Instant.now());
    }

    void handleProjectileLaunch(Projectile projectile, Instant now) {
        if (projectile == null) {
            return;
        }
        ProjectileSource shooter = projectile.getShooter();
        if (!(shooter instanceof Player player)) {
            return;
        }

        RuntimeSnapshot snapshot = configManager.snapshot();
        if (player.getWorld() != null && snapshot.config().worldRules().isDisabled(player.getWorld().getName())) return;
        PlayerId shooterId = PlayerId.of(player.getUniqueId());
        ActiveDuelSession session = duelService.getActiveDuel(shooterId);
        String duelId = session != null ? session.id() : null;
        Set<PlayerId> participants = session != null
                ? new HashSet<>(session.allParticipants())
                : Collections.emptySet();

        ProjectileLaunchRecord record = new ProjectileLaunchRecord(shooterId, duelId, participants, now);
        projectileLaunches.put(projectile, record);
        try {
            UUID uuid = projectile.getUniqueId();
            if (uuid != null) {
                projectileLaunchesByUuid.put(uuid, record);
            }
        } catch (Throwable ignored) {
        }
        pruneExpiredProjectiles(now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        handleDamage(event.getDamager(), event.getEntity());
    }

    /**
     * The decision behind {@link #onEntityDamageByEntity}, separated from the
     * event that carries it. Constructing an {@code EntityDamageByEntityEvent}
     * needs a live server registry, so a unit test cannot reach the logic
     * through the handler; it calls this instead.
     */
    void handleDamage(Entity damager, Entity damaged) {
        handleDamage(damager, damaged, Instant.now());
    }

    void handleDamage(Entity damager, Entity damaged, Instant now) {
        if (!(damaged instanceof Player victim)) {
            return;
        }

        RuntimeSnapshot snapshot = configManager.snapshot();
        if (victim.getWorld() != null && snapshot.config().worldRules().isDisabled(victim.getWorld().getName())) return;
        Player attacker = resolvePlayerAttacker(damager);
        if (attacker != null && attacker.getWorld() != null && snapshot.config().worldRules().isDisabled(attacker.getWorld().getName())) return;
        if (attacker != null && !attacker.getUniqueId().equals(victim.getUniqueId())) {
            PlayerId victimId = PlayerId.of(victim.getUniqueId());
            PlayerId attackerId = PlayerId.of(attacker.getUniqueId());

            duelService.recordCombatDamage(
                    victimId,
                    attackerId,
                    now
            );

            // T-139: Record duel context against attacker and victim when qualifying hit lands
            CombatContext context = resolveAttackContext(damager, attackerId, victimId);
            recordAttackContext(victimId, attackerId, context, now, snapshot);
        }
    }

    CombatContext resolveAttackContext(Entity damager, PlayerId attackerId, PlayerId victimId) {
        if (damager instanceof Projectile projectile) {
            ProjectileLaunchRecord launch = removeProjectileLaunch(projectile);
            if (launch != null) {
                if (launch.duelId() != null && launch.participants().contains(victimId)) {
                    return CombatContext.DUEL;
                }
                return CombatContext.OPEN;
            }
        }

        // Melee or unrecorded projectile hit: evaluate against active duel membership when it lands
        boolean inSameDuel = duelService.areInSameActiveDuel(attackerId, victimId);
        return inSameDuel ? CombatContext.DUEL : CombatContext.OPEN;
    }

    private ProjectileLaunchRecord removeProjectileLaunch(Projectile projectile) {
        if (projectile == null) {
            return null;
        }
        ProjectileLaunchRecord record = projectileLaunches.remove(projectile);
        if (record == null) {
            try {
                UUID uuid = projectile.getUniqueId();
                if (uuid != null) {
                    record = projectileLaunchesByUuid.remove(uuid);
                }
            } catch (Throwable ignored) {
            }
        } else {
            try {
                UUID uuid = projectile.getUniqueId();
                if (uuid != null) {
                    projectileLaunchesByUuid.remove(uuid);
                }
            } catch (Throwable ignored) {
            }
        }
        return record;
    }

    private void recordAttackContext(PlayerId victimId, PlayerId attackerId, CombatContext context, Instant now, RuntimeSnapshot snapshot) {
        victimAttackRecords.put(victimId, new AttackRecord(attackerId, context, now));
        pruneExpiredVictimRecords(now, snapshot);
    }

    private Duration getAttackContextWindow(RuntimeSnapshot snapshot) {
        if (snapshot != null && snapshot.config() != null && snapshot.config().duel() != null) {
            Duration custom = snapshot.config().duel().attackContextWindow();
            if (custom != null) {
                return custom;
            }
        }
        return DuelConfigSection.DEFAULT_ATTACK_CONTEXT_WINDOW;
    }

    private void pruneExpiredVictimRecords(Instant now, RuntimeSnapshot snapshot) {
        Duration window = getAttackContextWindow(snapshot);
        victimAttackRecords.values().removeIf(rec -> rec.isExpired(now, window));
        if (victimAttackRecords.size() > MAX_MAP_SIZE) {
            var it = victimAttackRecords.entrySet().iterator();
            while (it.hasNext() && victimAttackRecords.size() > MAX_MAP_SIZE) {
                it.next();
                it.remove();
            }
        }
    }

    private void pruneExpiredProjectiles(Instant now) {
        synchronized (projectileLaunches) {
            projectileLaunches.values().removeIf(rec ->
                    rec.launchedAt().plus(PROJECTILE_EXPIRATION).isBefore(now)
            );
            if (projectileLaunches.size() > MAX_MAP_SIZE) {
                var it = projectileLaunches.entrySet().iterator();
                while (it.hasNext() && projectileLaunches.size() > MAX_MAP_SIZE) {
                    it.next();
                    it.remove();
                }
            }
        }
        projectileLaunchesByUuid.values().removeIf(rec ->
                rec.launchedAt().plus(PROJECTILE_EXPIRATION).isBefore(now)
        );
        if (projectileLaunchesByUuid.size() > MAX_MAP_SIZE) {
            var it = projectileLaunchesByUuid.entrySet().iterator();
            while (it.hasNext() && projectileLaunchesByUuid.size() > MAX_MAP_SIZE) {
                it.next();
                it.remove();
            }
        }
    }

    public void clear() {
        victimAttackRecords.clear();
        synchronized (projectileLaunches) {
            projectileLaunches.clear();
        }
        projectileLaunchesByUuid.clear();
    }

    Player resolveKiller(Player victim, DamageSource damageSource) {
        if (damageSource != null) {
            Entity fatalCause = damageSource.getCausingEntity();
            Entity directCause = damageSource.getDirectEntity();
            if (fatalCause instanceof Player p) {
                return p;
            }
            if (directCause instanceof Projectile projectile) {
                if (projectile.getShooter() instanceof Player p) {
                    return p;
                }
                return victim.getKiller();
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        PlayerId victimId = PlayerId.of(victim.getUniqueId());
        String worldName = null;
        try {
            if (victim.getWorld() != null) {
                worldName = victim.getWorld().getName();
            }
        } catch (Throwable ignored) {
            // World lookup might throw in mocked environments without server
        }

        Player killer = null;
        try {
            killer = resolveKiller(victim, event.getDamageSource());
        } catch (Throwable ignored) {
            // DamageSource or DamageType unresolvable without server registry
        }

        PlayerId killerId = (killer != null && !killer.getUniqueId().equals(victim.getUniqueId()))
                ? PlayerId.of(killer.getUniqueId())
                : null;

        RuntimeSnapshot snapshot = configManager.snapshot();
        String killerWorld = killer != null && killer.getWorld() != null ? killer.getWorld().getName() : null;
        handleDeath(victimId, killerId, worldName, killerWorld, Instant.now(), snapshot)
                .exceptionally(ex -> {
                    LOGGER.log(Level.SEVERE, "Failed to record death outcome for killer=" + killerId + " victim=" + victimId, ex);
                    return null;
                });
    }

    /**
     * Decides combat outcomes and persistence for player deaths per T-061, T-062, T-130 to T-133, T-139.
     * Separated from Bukkit event unwrapping so that decision logic can be tested
     * purely through domain facts without instantiating server-bound Bukkit classes.
     *
     * @param victimId the deceased player
     * @param killerId the killer player, or null if environmental / non-player
     * @param worldName the name of the world where death occurred, or null
     * @param now timestamp of death
     * @param snapshot configuration snapshot
     */
    public CompletableFuture<KillPenaltyResult> handleDeath(
            PlayerId victimId,
            PlayerId killerId,
            String worldName,
            Instant now,
            com.dasannn.socialblueprint.config.RuntimeSnapshot snapshot
    ) {
        return handleDeath(victimId, killerId, worldName, null, now, snapshot);
    }

    public CompletableFuture<KillPenaltyResult> handleDeath(PlayerId victimId, PlayerId killerId,
            String worldName, String killerWorld, Instant now, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(victimId, "victimId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        // T-139: The context belongs to the attack, not to the death.
        // Cleared on death per rule.
        AttackRecord attackRecord = victimAttackRecords.remove(victimId);
        if (snapshot.config().worldRules().isDisabled(worldName) || snapshot.config().worldRules().isDisabled(killerWorld))
            return CompletableFuture.completedFuture(new KillPenaltyResult(0, null, null));

        CombatContext deathContext = context(victimId, killerId, attackRecord, now, snapshot);
        CompletableFuture<?> death = mindRepository != null && deathContext != CombatContext.DUEL
                ? mindRepository.applyAsync(victimId, com.dasannn.socialblueprint.domain.MindInput.DEATH,
                    snapshot.config().psychosis().input(com.dasannn.socialblueprint.domain.MindInput.DEATH),
                    killerId == null ? "environment" : killerId.toString(), now)
                : CompletableFuture.completedFuture(null);
        return handleKill(victimId, killerId, worldName, now, snapshot, deathContext)
                .thenCombine(death, (outcome, ignored) -> outcome);
    }

    /** Shared with near-death; environmental damage uses current accepted-duel membership. */
    public CombatContext damageContext(PlayerId victim, PlayerId attacker, Instant now, RuntimeSnapshot snapshot) {
        return context(victim, attacker, victimAttackRecords.get(victim), now, snapshot);
    }

    private CombatContext context(PlayerId victim, PlayerId attacker, AttackRecord record, Instant now, RuntimeSnapshot snapshot) {
        if (attacker == null || attacker.equals(victim))
            return duelService.isInActiveDuel(victim) ? CombatContext.DUEL : CombatContext.OPEN;
        if (record != null && record.attackerId().equals(attacker) && !record.isExpired(now, getAttackContextWindow(snapshot)))
            return record.context();
        return duelService.areInSameActiveDuel(attacker, victim) ? CombatContext.DUEL : CombatContext.OPEN;
    }

    private CompletableFuture<KillPenaltyResult> handleKill(PlayerId victimId, PlayerId killerId, String worldName,
            Instant now, RuntimeSnapshot snapshot, CombatContext context) {

        if (killerId != null && !killerId.equals(victimId)) {
            if (context == CombatContext.DUEL) {
                // T-061 / SB-031: Kill inside active duel is free!
                // Neither social status nor Killing Psychosis moves.
                duelService.handleDeath(victimId, killerId, now, snapshot);

                // Record duel event in repository with CombatContext.DUEL (ignored by PsychosisCalculator)
                return psychosisRepository.saveAsync(new PsychosisEvent(0L, killerId, victimId, CombatContext.DUEL, now))
                        .thenApply(saved -> new KillPenaltyResult(0, null, saved));
            } else {
                // T-062 / SB-032 / T-130: Kill outside duel raises Killing Psychosis and applies status penalty if eligible.
                if (duelService.isInActiveDuel(victimId)) {
                    duelService.handleDeath(victimId, null, now, snapshot);
                }

                if (reputationRepository == null) {
                    return psychosisRepository.saveAsync(new PsychosisEvent(0L, killerId, victimId, CombatContext.OPEN, now), snapshot.config().psychosis().input(com.dasannn.socialblueprint.domain.MindInput.KILL))
                            .thenApply(saved -> new KillPenaltyResult(0, null, saved));
                }

                KillPenaltyConfigSection killPenalty = snapshot.config().killPenalty();
                KillPenaltySettings settings = new KillPenaltySettings(
                        killPenalty.isEnabled(),
                        killPenalty.delta(),
                        killPenalty.pairCooldown(),
                        killPenalty.capWindow(),
                        killPenalty.maxLoss(),
                        killPenalty.exemptWorlds()
                );

                return reputationRepository.executeKillPenaltyAsync(
                        killerId,
                        victimId,
                        worldName,
                        now,
                        settings,
                        psychosisRepository,
                        snapshot.config().psychosis().input(com.dasannn.socialblueprint.domain.MindInput.KILL)
                );
            }
        } else {
            // Environmental or non-player death
            if (duelService.isInActiveDuel(victimId)) {
                duelService.handleDeath(victimId, null, now, snapshot);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    public CompletableFuture<KillPenaltyResult> handleDeath(PlayerId victimId, PlayerId killerId, Instant now, com.dasannn.socialblueprint.config.RuntimeSnapshot snapshot) {
        return handleDeath(victimId, killerId, "world", now, snapshot);
    }

    public CompletableFuture<KillPenaltyResult> handleDeath(PlayerId victimId, PlayerId killerId, Instant now) {
        return handleDeath(victimId, killerId, "world", now, configManager.snapshot());
    }

    public CompletableFuture<KillPenaltyResult> handleDeath(PlayerId victimId, PlayerId killerId) {
        return handleDeath(victimId, killerId, "world", Instant.now(), configManager.snapshot());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        PlayerId id = PlayerId.of(event.getPlayer().getUniqueId());
        victimAttackRecords.remove(id);
        victimAttackRecords.entrySet().removeIf(entry -> entry.getValue().attackerId().equals(id));
        duelService.handleWorldChange(id, configManager.snapshot());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerId playerId = PlayerId.of(player.getUniqueId());
        victimAttackRecords.remove(playerId);
        synchronized (projectileLaunches) {
            projectileLaunches.values().removeIf(rec -> rec.shooterId().equals(playerId));
        }
        projectileLaunchesByUuid.values().removeIf(rec -> rec.shooterId().equals(playerId));
        RuntimeSnapshot snapshot = configManager.snapshot();
        duelService.handleWorldChange(playerId, snapshot);
        duelService.handlePlayerQuit(playerId, Instant.now(), snapshot);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        RuntimeSnapshot snapshot = configManager.snapshot();
        PlayerId id = PlayerId.of(player.getUniqueId());
        duelService.handleWorldChange(id, snapshot);
        duelService.handlePlayerJoin(id, snapshot);
    }

    private Player resolvePlayerAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
