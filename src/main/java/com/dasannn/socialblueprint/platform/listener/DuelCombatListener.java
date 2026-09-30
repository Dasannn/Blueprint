package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.feature.duel.DuelService;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
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

/**
 * Platform combat listener per T-061, T-062, T-063 and ARCHITECTURE.md §5:
 * - A kill inside an active duel affects neither status nor Psychosis (SB-031).
 * - A kill outside a duel raises Psychosis and NEVER touches status (SB-032).
 * - Tracks recent combat damage to distinguish combat logs on quit (SB-033).
 * - Survives disconnects long enough to allow reconnect grace periods.
 */
public class DuelCombatListener implements Listener {

    private final DuelService duelService;
    private final PsychosisRepository psychosisRepository;
    private final ConfigManager configManager;

    public DuelCombatListener(
            DuelService duelService,
            PsychosisRepository psychosisRepository,
            ConfigManager configManager
    ) {
        this.duelService = Objects.requireNonNull(duelService, "duelService must not be null");
        this.psychosisRepository = Objects.requireNonNull(psychosisRepository, "psychosisRepository must not be null");
        this.configManager = Objects.requireNonNull(configManager, "configManager must not be null");
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

        Player killer = null;
        try {
            killer = resolveKiller(victim, event.getDamageSource());
        } catch (Throwable ignored) {
            // DamageSource or DamageType unresolvable without server registry
        }

        PlayerId killerId = (killer != null && !killer.getUniqueId().equals(victim.getUniqueId()))
                ? PlayerId.of(killer.getUniqueId())
                : null;

        handleDeath(victimId, killerId, Instant.now(), configManager.snapshot());
    }

    /**
     * Decides combat outcomes and persistence for player deaths per T-061, T-062, and SB-031, SB-032.
     * Separated from Bukkit event unwrapping so that decision logic can be tested
     * purely through domain facts without instantiating server-bound Bukkit classes.
     *
     * @param victimId the deceased player
     * @param killerId the killer player, or null if environmental / non-player
     * @param now timestamp of death
     * @param snapshot configuration snapshot
     */
    public void handleDeath(PlayerId victimId, PlayerId killerId, Instant now, com.dasannn.socialblueprint.config.RuntimeSnapshot snapshot) {
        Objects.requireNonNull(victimId, "victimId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (killerId != null && !killerId.equals(victimId)) {
            if (duelService.areInSameActiveDuel(killerId, victimId)) {
                // T-061 / SB-031: Kill inside active duel is free!
                // Neither social status nor Killing Psychosis moves.
                duelService.handleDeath(victimId, killerId, now, snapshot);

                // Record duel event in repository with CombatContext.DUEL (ignored by PsychosisCalculator)
                psychosisRepository.saveAsync(new PsychosisEvent(0L, killerId, victimId, CombatContext.DUEL, now));
                // Note: Social status is NEVER modified.
            } else {
                // T-062 / SB-032: Kill outside duel raises Killing Psychosis and NEVER touches status.
                if (duelService.isInActiveDuel(victimId)) {
                    duelService.handleDeath(victimId, null, now, snapshot);
                }

                // Record open kill in repository with CombatContext.OPEN (raises Psychosis)
                psychosisRepository.saveAsync(new PsychosisEvent(0L, killerId, victimId, CombatContext.OPEN, now));
                // Note: Social status is NEVER modified.
            }
        } else {
            // Environmental or non-player death
            if (duelService.isInActiveDuel(victimId)) {
                duelService.handleDeath(victimId, null, now, snapshot);
            }
        }
    }

    public void handleDeath(PlayerId victimId, PlayerId killerId, Instant now) {
        handleDeath(victimId, killerId, now, configManager.snapshot());
    }

    public void handleDeath(PlayerId victimId, PlayerId killerId) {
        handleDeath(victimId, killerId, Instant.now(), configManager.snapshot());
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
