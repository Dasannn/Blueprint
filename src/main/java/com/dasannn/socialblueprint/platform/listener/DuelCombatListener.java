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
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = resolvePlayerAttacker(event.getDamager());
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

        DamageSource damageSource = event.getDamageSource();
        Entity fatalCause = damageSource != null ? damageSource.getCausingEntity() : null;

        Player killer = null;
        if (fatalCause instanceof Player p) {
            killer = p;
        } else if (victim.getKiller() != null) {
            killer = victim.getKiller();
        }

        Instant now = Instant.now();

        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            PlayerId killerId = PlayerId.of(killer.getUniqueId());

            if (duelService.areInSameActiveDuel(killerId, victimId)) {
                // T-061 / SB-031: Kill inside active duel is free!
                // Neither social status nor Killing Psychosis moves.
                duelService.handleDeath(victimId, killerId, now, configManager.snapshot());

                // Record duel event in repository with CombatContext.DUEL (ignored by PsychosisCalculator)
                psychosisRepository.saveAsync(new PsychosisEvent(0L, killerId, victimId, CombatContext.DUEL, now));
                // Note: Social status is NEVER modified.
            } else {
                // T-062 / SB-032: Kill outside duel raises Killing Psychosis and NEVER touches status.
                if (duelService.isInActiveDuel(victimId)) {
                    duelService.handleDeath(victimId, null, now, configManager.snapshot());
                }

                // Record open kill in repository with CombatContext.OPEN (raises Psychosis)
                psychosisRepository.saveAsync(new PsychosisEvent(0L, killerId, victimId, CombatContext.OPEN, now));
                // Note: Social status is NEVER modified.
            }
        } else {
            // Environmental or non-player death
            if (duelService.isInActiveDuel(victimId)) {
                duelService.handleDeath(victimId, null, now, configManager.snapshot());
            }
        }
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
