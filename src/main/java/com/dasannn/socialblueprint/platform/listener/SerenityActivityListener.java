package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.feature.profile.SerenityService;
import org.bukkit.Input;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.inventory.InventoryClickEvent;

/** Qualifying activity is player input/gameplay, not chat, vehicles or passive world movement. */
public final class SerenityActivityListener implements Listener {
    private final SerenityService service;
    public SerenityActivityListener(SerenityService service) { this.service = service; }

    private void activity(Player player) {
        refreshAfk(player);
        service.activity(PlayerId.of(player.getUniqueId()));
    }

    public void refreshAfk(Player player) {
        boolean afk = player.getMetadata("afk").stream().anyMatch(value -> value.asBoolean());
        service.setMetadataAfk(PlayerId.of(player.getUniqueId()), afk);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || !event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        Input input = player.getCurrentInput();
        if (!player.isInsideVehicle() && hasMovementInput(input.isForward(), input.isBackward(),
                input.isLeft(), input.isRight(), input.isJump())) activity(player);
    }

    public static boolean hasMovementInput(boolean forward, boolean backward, boolean left, boolean right, boolean jump) {
        return forward || backward || left || right || jump;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void mine(BlockBreakEvent event) { activity(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void build(BlockPlaceEvent event) { activity(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void use(PlayerInteractEvent event) {
        // Air interactions are often pre-cancelled; only accepted block/item use qualifies.
        if ((event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                && event.useInteractedBlock() != org.bukkit.event.Event.Result.DENY)
                || (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR && event.getItem() != null
                && event.useItemInHand() != org.bukkit.event.Event.Result.DENY)) activity(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void inventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) activity(player);
    }
}
