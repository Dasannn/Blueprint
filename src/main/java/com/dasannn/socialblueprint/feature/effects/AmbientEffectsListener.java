package com.dasannn.socialblueprint.feature.effects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;
import java.util.UUID;

/**
 * Event listener guaranteeing entity safety and lifecycle cleanup per T-072, T-073,
 * and SB-042.
 * - Cleans registry on player quit (T-073).
 * - Cleans registry on player world change (T-073).
 * - Harmless silverfish guards: cancels targeting, damage dealt, damage taken, block changes,
 *   and clears death drops/XP (T-072).
 */
public class AmbientEffectsListener implements Listener {

    private final AmbientEntityRegistry registry;
    private final AmbientEffectScheduler scheduler;

    public AmbientEffectsListener(AmbientEntityRegistry registry, AmbientEffectScheduler scheduler) {
        this.registry = Objects.requireNonNull(registry, "AmbientEntityRegistry must not be null");
        this.scheduler = scheduler;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        registry.cleanForPlayer(playerId);
        if (scheduler != null) {
            scheduler.handlePlayerQuit(playerId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        registry.cleanForPlayerWorldChange(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (registry.isManaged(event.getEntity())) {
            event.setCancelled(true);
            event.setTarget(null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (registry.isManaged(event.getDamager())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent event) {
        if (registry.isManaged(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (registry.isManaged(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDeath(EntityDeathEvent event) {
        if (registry.isManaged(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }
}
