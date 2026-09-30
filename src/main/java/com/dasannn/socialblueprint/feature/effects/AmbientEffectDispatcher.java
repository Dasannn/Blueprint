package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.EffectsConfigSection;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Dispatches individual low-status ambient effects privately to an affected player
 * per SB-040, SB-041, and Decision 0002.
 * Absolutely private: no broadcast, no server logging, no leakage to other players.
 */
public class AmbientEffectDispatcher {

    private final Plugin plugin;
    private final MessageRegistry messageRegistry;
    private final ConfigManager configManager;
    private final FakeSilverfishService silverfishService;
    private final Random random = new Random();

    public AmbientEffectDispatcher(
            Plugin plugin,
            MessageRegistry messageRegistry,
            ConfigManager configManager,
            FakeSilverfishService silverfishService
    ) {
        this.plugin = plugin;
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "MessageRegistry must not be null");
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager must not be null");
        this.silverfishService = Objects.requireNonNull(silverfishService, "FakeSilverfishService must not be null");
    }

    public void dispatch(Player player, AmbientEffectType type, EffectsConfigSection config, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(config, "config must not be null");

        switch (type) {
            case SILVERFISH -> dispatchSilverfish(player, config);
            case WHISPER -> dispatchWhisper(player);
            case CREEPER_SOUND -> dispatchCreeperSound(player, snapshot);
            case FAKE_ANNOUNCEMENT -> dispatchFakeAnnouncement(player, config);
        }
    }

    private void dispatchSilverfish(Player player, EffectsConfigSection config) {
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = 1.5 + random.nextDouble() * 2.0;
        double dx = Math.cos(angle) * distance;
        double dz = Math.sin(angle) * distance;

        Location at = player.getLocation().clone().add(dx, 0, dz);
        silverfishService.spawnSilverfish(player, at, config.silverfish().durationTicks());
    }

    private void dispatchWhisper(Player player) {
        int idx = random.nextInt(3) + 1;
        Component whisper = switch (idx) {
            case 1 -> messageRegistry.render("effects.whisper-1");
            case 2 -> messageRegistry.render("effects.whisper-2");
            default -> messageRegistry.render("effects.whisper-3");
        };
        player.sendMessage(whisper);
    }

    private void dispatchCreeperSound(Player player, RuntimeSnapshot snapshot) {
        // Read slot from the snapshot at play time, never cache it in a field (T-137)
        com.dasannn.socialblueprint.config.SoundSlotConfig slot = (snapshot != null)
                ? snapshot.config().sounds().get("creeper-fuse")
                : com.dasannn.socialblueprint.config.SoundSlotConfig.SILENT;
        if (slot.isSilent()) {
            return;
        }
        try {
            // player.playSound sends the sound packet ONLY to this player (private per SB-041, T-137)
            // String overload per T-135 so it can be verified in unit tests without registry
            player.playSound(player.getLocation(), slot.key(), slot.category(), slot.volume(), slot.pitch());
        } catch (Throwable t) {
            if (snapshot != null) {
                snapshot.config().sounds().logKeyWarning("creeper-fuse", slot.key(), t.getMessage());
            }
        }
    }

    private void dispatchFakeAnnouncement(Player player, EffectsConfigSection config) {
        List<String> names = config.fakeAnnouncement().fakeNames();
        String fakeName = (!names.isEmpty()) ? names.get(random.nextInt(names.size())) : "Herobrine";

        boolean isJoin = random.nextBoolean();
        Component announcement = isJoin
                ? messageRegistry.render("effects.fake-join", Map.of("player", fakeName))
                : messageRegistry.render("effects.fake-leave", Map.of("player", fakeName));

        // Send privately to the affected player alone - never broadcasted or logged
        player.sendMessage(announcement);
    }

    public FakeSilverfishService silverfishService() {
        return silverfishService;
    }
}
