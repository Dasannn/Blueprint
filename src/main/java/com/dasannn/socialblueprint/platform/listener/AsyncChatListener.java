package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.ChatGradient;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.Tier;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Async chat listener and renderer per T-040, T-041, T-042, T-043, and T-044.
 * - Reads one immutable snapshot per event.
 * - Non-blocking lookup: if profile is not cached, renders with neutral default.
 * - Colors message with tier gradient (#202020 to #FFFFFF).
 * - Attaches compact hover summary to the player's name component.
 * - Never touches Bukkit entities, worlds, or databases on the chat path.
 * - Never cancels, truncates, delays or blocks messages (Constitution §2.2).
 */
public class AsyncChatListener implements Listener {

    private final ProfileService profileService;
    private final ConfigManager configManager;
    private final MessageRegistry messageRegistry;

    public AsyncChatListener(ProfileService profileService, ConfigManager configManager, MessageRegistry messageRegistry) {
        this.profileService = Objects.requireNonNull(profileService, "ProfileService must not be null");
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "MessageRegistry must not be null");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        try {
            // Read one immutable snapshot per event (T-040, T-042)
            RuntimeSnapshot snapshot = configManager.snapshot();

            Player player = event.getPlayer();
            PlayerId playerId = PlayerId.of(player.getUniqueId());

            // Single read from in-memory cache, neutral default if absent (T-042)
            PlayerSocialView view = profileService.getViewQuick(playerId, snapshot);

            // Resolve tier and prefix dynamically from current snapshot per lookup (T-040)
            Tier tier = snapshot.config().tiers().ladder().resolve(view.status());
            String prefixStr = snapshot.config().tiers().prefix(tier);
            Component prefixComp = (prefixStr != null && !prefixStr.isEmpty())
                    ? ColorParser.parse(prefixStr)
                    : Component.empty();

            // Chat gradient from #202020 to bright white by tier (T-041)
            TextColor shade = TextColor.color(ChatGradient.rgb(tier));

            // Format name hover summary from message bundle (T-043)
            Component hoverComponent = buildHoverComponent(snapshot, view, tier);

            // Install renderer: preserves player name, attaches hover, colors message (T-043, T-044)
            event.renderer(createRenderer(prefixComp, hoverComponent, shade));
        } catch (Throwable t) {
            // Fallback renderer preserving display name and complete original message per SB-021
            event.renderer((source, sourceDisplayName, message, viewer) ->
                    sourceDisplayName.append(Component.text(": ")).append(message)
            );
        }
    }

    public ChatRenderer createRenderer(Component prefixComp, Component hoverComponent, TextColor shade) {
        return createRenderer(prefixComp, hoverComponent, shade, (name, hover) ->
                (hover != null && !hover.equals(Component.empty()))
                        ? name.hoverEvent(HoverEvent.showText(hover))
                        : name
        );
    }

    public ChatRenderer createRenderer(
            Component prefixComp,
            Component hoverComponent,
            TextColor shade,
            java.util.function.BiFunction<Component, Component, Component> hoverAttacher
    ) {
        Objects.requireNonNull(hoverAttacher, "hoverAttacher must not be null");
        return (source, sourceDisplayName, message, viewer) -> {
            try {
                Component hoveredName = hoverAttacher.apply(sourceDisplayName, hoverComponent);
                Component coloredMessage = recolorMessage(message, shade);
                if (prefixComp == null || prefixComp.equals(Component.empty())) {
                    return hoveredName
                            .append(Component.text(": "))
                            .append(coloredMessage);
                } else {
                    return prefixComp
                            .append(Component.space())
                            .append(hoveredName)
                            .append(Component.text(": "))
                            .append(coloredMessage);
                }
            } catch (Throwable t) {
                // SB-021 and constitution §2.2: Never lose or truncate messages on failure.
                // Fall back to original display name and the complete original message.
                return sourceDisplayName
                        .append(Component.text(": "))
                        .append(message);
            }
        };
    }

    /**
     * Recolors text descendants while preserving text and non-color decorations.
     */
    public static Component recolorMessage(Component component, TextColor shade) {
        if (component == null) {
            return Component.empty();
        }
        java.util.List<Component> children = component.children();
        if (children.isEmpty()) {
            return component.color(shade);
        }
        java.util.List<Component> recoloredChildren = new java.util.ArrayList<>(children.size());
        for (Component child : children) {
            recoloredChildren.add(recolorMessage(child, shade));
        }
        return component.color(shade).children(recoloredChildren);
    }

    public Component buildHoverComponent(RuntimeSnapshot snapshot, PlayerSocialView view, Tier tier) {
        Component line1 = messageRegistry.render(snapshot, "chat.hover-status",
                Map.of("status", String.valueOf(view.status())));

        String prefixStr = snapshot.config().tiers().prefix(tier);
        Component prefixComp = (prefixStr != null && !prefixStr.isEmpty())
                ? ColorParser.parse(prefixStr)
                : Component.empty();

        Component line2 = messageRegistry.render(snapshot, "chat.hover-tier",
                Map.of("tier", messageRegistry.tierName(snapshot, tier)),
                Map.of("prefix", prefixComp)
        );

        Component line3 = messageRegistry.render(snapshot, "chat.hover-confidence",
                Map.of("confidence", messageRegistry.getRaw(snapshot, "confidence." + view.confidence().name().toLowerCase(Locale.ROOT))));

        Component line4 = messageRegistry.render(snapshot, "chat.hover-psychosis",
                Map.of("psychosis", messageRegistry.getRaw(snapshot, "psychosis." + view.psychosis().name().toLowerCase(Locale.ROOT))));

        Component line5 = messageRegistry.render(snapshot, "chat.hover-contributors",
                Map.of("contributors", String.valueOf(view.contributors())));

        return line1
                .append(Component.newline())
                .append(line2)
                .append(Component.newline())
                .append(line3)
                .append(Component.newline())
                .append(line4)
                .append(Component.newline())
                .append(line5);
    }
}
