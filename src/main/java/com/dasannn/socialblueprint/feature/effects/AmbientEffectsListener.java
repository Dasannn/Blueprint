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
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;

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
    private final AmbientEffectDispatcher dispatcher;

    public AmbientEffectsListener(AmbientEntityRegistry registry, AmbientEffectScheduler scheduler) {
        this(registry, scheduler, null);
    }

    public AmbientEffectsListener(AmbientEntityRegistry registry, AmbientEffectScheduler scheduler, AmbientEffectDispatcher dispatcher) {
        this.registry = Objects.requireNonNull(registry, "AmbientEntityRegistry must not be null");
        this.scheduler = scheduler;
        this.dispatcher = dispatcher;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanupPlayer(event.getPlayer().getUniqueId(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        cleanupPlayer(event.getPlayer().getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(org.bukkit.event.player.PlayerMoveEvent event) {
        if (event instanceof org.bukkit.event.player.PlayerTeleportEvent) return;
        if (dispatcher != null) {
            dispatcher.restoreBlocksOnMove(event.getPlayer(), event.getTo());
            dispatcher.moveAnimalViewer(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (dispatcher != null) {
            dispatcher.restoreBlocks(event.getPlayer().getUniqueId());
            dispatcher.removeAnimalViewer(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (dispatcher != null) dispatcher.restoreBlocks(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerItemHeld(org.bukkit.event.player.PlayerItemHeldEvent event) {
        if (dispatcher != null) dispatcher.restoreBlocks(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerSwapHands(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        if (dispatcher != null) dispatcher.restoreBlocks(event.getPlayer().getUniqueId());
    }

    void cleanupPlayer(UUID playerId, boolean quit) {
        registry.cleanForPlayer(playerId);
        if (quit && scheduler != null) scheduler.handlePlayerQuit(playerId);
        if (!quit && scheduler != null) scheduler.handlePlayerWorldChange(playerId);
        if (dispatcher != null) dispatcher.cancelPending(playerId);
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
        cancelIfManaged(event.getDamager(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent event) {
        cancelIfManaged(event.getEntity(), event);
    }

    /**
     * A fake silverfish neither deals nor takes damage (SB-042). Constructing an
     * EntityDamageEvent needs a live server registry, so a unit test cannot
     * reach this decision through the handlers; it calls this instead.
     */
    void cancelIfManaged(Entity entity, Cancellable event) {
        if (registry.isManaged(entity)) {
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
