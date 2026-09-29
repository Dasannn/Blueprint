package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

/**
 * Player lifecycle listener per T-042 and T-044.
 * - Warms up player profiles on join asynchronously.
 * - Evicts or cleans up on quit.
 * - Never calls setDisplayName, setPlayerListName, or setCustomName (T-044).
 */
public class PlayerLifecycleListener implements Listener {

    private final ProfileService profileService;
    private final ConfigManager configManager;

    public PlayerLifecycleListener(ProfileService profileService, ConfigManager configManager) {
        this.profileService = Objects.requireNonNull(profileService, "ProfileService must not be null");
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        RuntimeSnapshot snapshot = configManager.snapshot();
        profileService.warmUp(
                PlayerId.of(player.getUniqueId()),
                player.getName(),
                snapshot
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        profileService.evict(PlayerId.of(player.getUniqueId()));
    }
}
