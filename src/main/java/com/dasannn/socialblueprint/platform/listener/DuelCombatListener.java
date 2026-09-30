package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.KillPenaltyConfigSection;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.feature.duel.DuelService;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Platform combat listener per T-061, T-062, T-063, T-130 to T-133 and ARCHITECTURE.md §5:
 * - A kill inside an active duel affects neither status nor Psychosis (SB-031).
 * - A kill outside a duel raises Psychosis and applies a status penalty if eligible (SB-032, T-130).
 * - Tracks recent combat damage to distinguish combat logs on quit (SB-033).
 * - Survives disconnects long enough to allow reconnect grace periods.
 */
public class DuelCombatListener implements Listener {

    private final DuelService duelService;
    private final PsychosisRepository psychosisRepository;
    private final ConfigManager configManager;
    private final ReputationRepository reputationRepository;

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
        if (!(damaged instanceof Player victim)) {
            return;
        }

        Player attacker = resolvePlayerAttacker(damager);
        if (attacker != null && !attacker.getUniqueId().equals(victim.getUniqueId())) {
            duelService.recordCombatDamage(
                    PlayerId.of(victim.getUniqueId()),
                    PlayerId.of(attacker.getUniqueId()),
                    Instant.now()
            );
        }
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
            DamageSource damageSource = event.getDamageSource();
            Entity fatalCause = damageSource != null ? damageSource.getCausingEntity() : null;
            if (fatalCause instanceof Player p) {
                killer = p;
            }
        } catch (Throwable ignored) {
            // DamageSource or DamageType unresolvable without server registry
        }
        if (killer == null && victim.getKiller() != null) {
            killer = victim.getKiller();
        }

        PlayerId killerId = (killer != null && !killer.getUniqueId().equals(victim.getUniqueId()))
                ? PlayerId.of(killer.getUniqueId())
                : null;

        handleDeath(victimId, killerId, worldName, Instant.now(), configManager.snapshot());
    }

    /**
     * Decides combat outcomes and persistence for player deaths per T-061, T-062, T-130 to T-133.
     * Separated from Bukkit event unwrapping so that decision logic can be tested
     * purely through domain facts without instantiating server-bound Bukkit classes.
     *
     * @param victimId the deceased player
     * @param killerId the killer player, or null if environmental / non-player
     * @param worldName the name of the world where death occurred, or null
     * @param now timestamp of death
     * @param snapshot configuration snapshot
     */
    public CompletableFuture<Void> handleDeath(
            PlayerId victimId,
            PlayerId killerId,
            String worldName,
            Instant now,
            com.dasannn.socialblueprint.config.RuntimeSnapshot snapshot
    ) {
        Objects.requireNonNull(victimId, "victimId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (killerId != null && !killerId.equals(victimId)) {
            if (duelService.areInSameActiveDuel(killerId, victimId)) {
                // T-061 / SB-031: Kill inside active duel is free!
                // Neither social status nor Killing Psychosis moves.
                duelService.handleDeath(victimId, killerId, now, snapshot);

                // Record duel event in repository with CombatContext.DUEL (ignored by PsychosisCalculator)
                return psychosisRepository.saveAsync(new PsychosisEvent(0L, killerId, victimId, CombatContext.DUEL, now))
                        .thenApply(saved -> null);
            } else {
                // T-062 / SB-032 / T-130: Kill outside duel raises Killing Psychosis and applies status penalty if eligible.
                if (duelService.isInActiveDuel(victimId)) {
                    duelService.handleDeath(victimId, null, now, snapshot);
                }

                KillPenaltyConfigSection killPenalty = snapshot.config().killPenalty();
                boolean penaltyEligible = reputationRepository != null
                        && killPenalty.isEnabled()
                        && (worldName == null || !killPenalty.isWorldExempt(worldName));

                CompletableFuture<Void> penalty = penaltyEligible
                        ? psychosisRepository.countOpenKillsBetweenSinceAsync(killerId, victimId,
                                now.minus(killPenalty.pairCooldown()))
                                .thenCompose(killsInCooldown -> {
                                    if (killsInCooldown != 0) {
                                        return CompletableFuture.completedFuture(null);
                                    }
                                    return reputationRepository.calculateSystemKillLossSinceAsync(killerId,
                                            now.minus(killPenalty.capWindow()))
                                            .thenCompose(currentLoss -> {
                                                int remainingLoss = killPenalty.maxLoss() - currentLoss;
                                                if (remainingLoss <= 0) {
                                                    return CompletableFuture.completedFuture(null);
                                                }
                                                int penaltyDelta = Math.max(killPenalty.delta(), -remainingLoss);
                                                if (penaltyDelta >= 0) {
                                                    return CompletableFuture.completedFuture(null);
                                                }
                                                ReputationEvent repEvent = new ReputationEvent(
                                                        0L, null, killerId, penaltyDelta, HonorKind.SYSTEM_KILL,
                                                        0.0, "kill-penalty.reason", now
                                                );
                                                return reputationRepository.saveAsync(repEvent).thenApply(saved -> null);
                                            });
                                })
                        : CompletableFuture.completedFuture(null);

                return penalty.thenCompose(ignored -> psychosisRepository.saveAsync(
                        new PsychosisEvent(0L, killerId, victimId, CombatContext.OPEN, now))
                        .thenApply(saved -> null));
            }
        } else {
            // Environmental or non-player death
            if (duelService.isInActiveDuel(victimId)) {
                duelService.handleDeath(victimId, null, now, snapshot);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    public CompletableFuture<Void> handleDeath(PlayerId victimId, PlayerId killerId, Instant now, com.dasannn.socialblueprint.config.RuntimeSnapshot snapshot) {
        return handleDeath(victimId, killerId, null, now, snapshot);
    }

    public CompletableFuture<Void> handleDeath(PlayerId victimId, PlayerId killerId, Instant now) {
        return handleDeath(victimId, killerId, null, now, configManager.snapshot());
    }

    public CompletableFuture<Void> handleDeath(PlayerId victimId, PlayerId killerId) {
        return handleDeath(victimId, killerId, null, Instant.now(), configManager.snapshot());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        duelService.handlePlayerQuit(PlayerId.of(player.getUniqueId()), Instant.now(), configManager.snapshot());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        duelService.handlePlayerJoin(PlayerId.of(player.getUniqueId()), configManager.snapshot());
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
