package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.feature.gui.StatusGuiHolder;
import com.dasannn.socialblueprint.feature.gui.StatusGuiService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.Objects;

/**
 * Bukkit listener handling interactions inside the Social Profile & Rating History chest GUI.
 * Cancels all item modifications and delegates clicks to {@link StatusGuiService}.
 */
public class StatusGuiListener implements Listener {

    private final StatusGuiService guiService;

    public StatusGuiListener(StatusGuiService guiService) {
        this.guiService = Objects.requireNonNull(guiService, "guiService must not be null");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof StatusGuiHolder holder) {
            // Cancel event so items cannot be moved, stolen, or swapped
            event.setCancelled(true);

            if (event.getRawSlot() < 0 || event.getRawSlot() >= StatusGuiService.INVENTORY_SIZE) {
                return;
            }

            if (event.getClickedInventory() == null) {
                return;
            }

            if (event.getWhoClicked() instanceof Player viewer) {
                guiService.handleClick(viewer, holder, event.getRawSlot());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof StatusGuiHolder) {
            event.setCancelled(true);
        }
    }
}
