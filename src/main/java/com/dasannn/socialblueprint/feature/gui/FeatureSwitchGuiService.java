package com.dasannn.socialblueprint.feature.gui;

import com.dasannn.socialblueprint.command.PermissionChecker;
import com.dasannn.socialblueprint.command.StatusConfigCommand;
import com.dasannn.socialblueprint.config.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Bukkit boundary for the feature-switch view; config commands own every write. */
public final class FeatureSwitchGuiService implements Listener {
    private final ConfigManager config;
    private final MessageRegistry messages;
    private final StatusConfigCommand edits;
    private final Consumer<Runnable> mainThread;
    private final GuiRenderer renderer;

    public FeatureSwitchGuiService(ConfigManager config, MessageRegistry messages,
                                   StatusConfigCommand edits, Consumer<Runnable> mainThread) {
        this.config = Objects.requireNonNull(config);
        this.messages = Objects.requireNonNull(messages);
        this.edits = Objects.requireNonNull(edits);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.renderer = new GuiRenderer(messages, null);
    }

    public CompletableFuture<Void> execute(CommandSender sender, String[] args, RuntimeSnapshot snapshot) {
        if (!PermissionChecker.hasPermission(sender, "admin-features", snapshot)) {
            sender.sendMessage(messages.renderWithPrefix(snapshot, "commands.no-permission"));
        } else if (!(sender instanceof Player player) || args.length != 1) {
            sender.sendMessage(messages.renderWithPrefix(snapshot, "features.usage"));
        } else {
            Holder holder = new Holder(player.getUniqueId());
            holder.inventory = Bukkit.createInventory(holder, 54, messages.render(snapshot, "features.title"));
            refresh(holder, snapshot);
            player.openInventory(holder.inventory);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void refresh(Holder holder, RuntimeSnapshot snapshot) {
        holder.view = FeatureSwitchLayout.compute(snapshot, holder.page);
        Map<Integer, GuiSlot> resolved = new LinkedHashMap<>();
        holder.view.layout().slots().forEach((index, slot) ->
                resolved.put(index, StatusGuiService.resolveSlotText(slot, snapshot, messages)));
        renderer.render(holder.inventory, new GuiLayout(54, resolved), snapshot);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.viewer.equals(player.getUniqueId())
                || holder.busy || event.getRawSlot() < 0 || event.getRawSlot() >= 54) return;
        int page = holder.view.pageAfterClick(event.getRawSlot());
        if (page != holder.page) {
            holder.page = page;
            refresh(holder, config.snapshot());
            return;
        }
        FeatureSwitchLayout.Switch item = holder.view.switches().get(event.getRawSlot());
        if (item == null) return;
        RuntimeSnapshot snapshot = config.snapshot();
        holder.busy = true;
        edits.toggleFeatureAsync(player, item.key(), snapshot).whenComplete((ignored, error) -> mainThread.accept(() -> {
            holder.busy = false;
            if (error != null) {
                Logger.getLogger(FeatureSwitchGuiService.class.getName()).log(Level.SEVERE, "Feature toggle failed", error);
                player.sendMessage(messages.renderWithPrefix(snapshot, "commands.config.set-failed",
                        Map.of("key", item.key(), "error", error.getMessage() == null ? "" : error.getMessage())));
            }
            if (player.isOnline() && player.getOpenInventory().getTopInventory() == holder.inventory) {
                refresh(holder, config.snapshot());
            }
        }));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    private static final class Holder implements InventoryHolder {
        private final UUID viewer;
        private Inventory inventory;
        private FeatureSwitchLayout.View view;
        private boolean busy;
        private int page;
        private Holder(UUID viewer) { this.viewer = viewer; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
