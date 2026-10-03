package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.*;
import com.dasannn.socialblueprint.domain.*;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import java.util.HashMap;
import java.util.Map;

/** Profile callbacks carry plain data from storage; Bukkit rendering stays on main. */
public final class PlayerPresentationListener implements Listener {
    private final Plugin plugin;
    private final ProfileService profiles;
    private final ConfigManager configs;
    private final MessageRegistry messages;
    private final Map<PlayerId, MindNotices> baselines = new HashMap<>();
    private final java.util.concurrent.ConcurrentMap<PlayerId, Long> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong sessionSequence = new java.util.concurrent.atomic.AtomicLong();
    public PlayerPresentationListener(Plugin plugin, ProfileService profiles, ConfigManager configs, MessageRegistry messages) {
        this.plugin = plugin; this.profiles = profiles; this.configs = configs; this.messages = messages;
        for (Player player : plugin.getServer().getOnlinePlayers())
            sessions.put(PlayerId.of(player.getUniqueId()), sessionSequence.incrementAndGet());
        profiles.addViewListener((view, snapshot) -> {
            Long session = sessions.get(view.playerId());
            if (session != null) schedule(() -> {
                if (session.equals(sessions.get(view.playerId()))) render(view, snapshot);
            });
        });
        configs.addSnapshotListener(snapshot -> schedule(() -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (!snapshot.config().tabEnabled()) player.playerListName(null);
                profiles.loadViewAsync(PlayerId.of(player.getUniqueId()), player.getName(), snapshot);
            }
        }));
    }
    private void schedule(Runnable action) {
        if (plugin.isEnabled()) {
            try { plugin.getServer().getScheduler().runTask(plugin, action); }
            catch (org.bukkit.plugin.IllegalPluginAccessException stopped) { /* Disable raced the storage callback. */ }
        }
    }
    private void render(PlayerSocialView view, RuntimeSnapshot snapshot) {
        if (configs.snapshot() != snapshot) return;
        Player player = plugin.getServer().getPlayer(view.playerId().uuid());
        if (player == null || !player.isOnline()) return;
        String prefix = snapshot.config().tiers().prefix(snapshot.config().tiers().ladder().resolve(view.status()));
        player.playerListName(snapshot.config().tabEnabled()
                ? PlayerNameRenderer.name(prefix, player.getName()) : null);
        double value = view.psychosis() == PsychosisLevel.SERENITY ? view.psychosisMagnitude() : -view.psychosisMagnitude();
        MindNotices baseline = baselines.get(view.playerId());
        if (baseline == null) baselines.put(view.playerId(), new MindNotices(value));
        else {
            var config = snapshot.config().mindNotices();
            for (var notice : baseline.update(value, config.step(), config.enabled(), config.rises(), config.falls()))
                player.sendMessage(messages.renderWithPrefix(snapshot, notice.key(), notice.values()));
        }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        PlayerId id = PlayerId.of(event.getPlayer().getUniqueId());
        baselines.remove(id);
        sessions.put(id, sessionSequence.incrementAndGet());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerId id = PlayerId.of(event.getPlayer().getUniqueId());
        baselines.remove(id);
        sessions.remove(id);
    }
    public void stop() {
        sessions.clear();
        baselines.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) player.playerListName(null);
    }
}
