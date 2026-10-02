package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.feature.gui.StatusGuiHolder;
import com.dasannn.socialblueprint.feature.gui.StatusGuiService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bukkit listener handling interactions inside the Social Profile & Rating History chest GUI.
 * Cancels all item modifications and delegates clicks to {@link StatusGuiService}.
 * Logs click failures at the boundary (Finding 6) and cleans up pending reason prompts on quit (Finding 3).
 */
public class StatusGuiListener implements Listener {

    private final StatusGuiService guiService;
    private final Logger logger;

    public StatusGuiListener(StatusGuiService guiService) {
        this(guiService, Logger.getLogger(StatusGuiListener.class.getName()));
    }

    public StatusGuiListener(StatusGuiService guiService, Logger logger) {
        this.guiService = Objects.requireNonNull(guiService, "guiService must not be null");
        this.logger = logger != null ? logger : Logger.getLogger(StatusGuiListener.class.getName());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof StatusGuiHolder holder) {
            // Cancel event so items cannot be moved, stolen, or swapped
            event.setCancelled(true);

            if (event.getRawSlot() < 0 || event.getRawSlot() >= holder.layout().size()) {
                return;
            }

            if (event.getClickedInventory() == null) {
                return;
            }

            if (event.getWhoClicked() instanceof Player viewer) {
                String viewerName = viewer.getName();
                guiService.handleClick(viewer, holder, event.getRawSlot())
                        .exceptionally(ex -> {
                            logger.log(Level.SEVERE, "Failed handling GUI click for player " + viewerName, ex);
                            return null;
                        });
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof StatusGuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof StatusGuiHolder holder) {
            guiService.discardHonorPreview(holder);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        guiService.cancelPendingReason(event.getPlayer().getUniqueId());
    }
}
