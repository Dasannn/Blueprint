package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.ChatCorruption;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.Tier;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Async chat listener and renderer per T-040, T-041, T-042, T-043, and T-044.
 * - Reads one immutable snapshot per event.
 * - Non-blocking lookup: if profile is not cached, renders with neutral default.
 * - Computes shared plain-text Psychosis corruption once per event.
 * - Attaches compact hover summary to the player's name component.
 * - Never touches Bukkit entities, worlds, or databases on the chat path.
 * - Never cancels, truncates, delays or blocks messages (Constitution §2.2).
 */
public class AsyncChatListener implements Listener {

    private record OwnedRenderer(ChatRenderer delegate) implements ChatRenderer {
        @Override public Component render(Player source, Component name, Component message,
                                          net.kyori.adventure.audience.Audience viewer) {
            return delegate.render(source, name, message, viewer);
        }
    }
    private final java.util.concurrent.atomic.AtomicBoolean warnedRenderer = new java.util.concurrent.atomic.AtomicBoolean();

    private void warnForeignRenderer() {
        if (warnedRenderer.compareAndSet(false, true)) java.util.logging.Logger.getLogger(AsyncChatListener.class.getName())
                .warning("Another plugin replaced the chat renderer; SocialBlueprint leaves it in control of chat presentation.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRenderedChat(AsyncChatEvent event) {
        if (!(event.renderer() instanceof OwnedRenderer) && !(event.renderer() instanceof ChatRenderer.Default))
            warnForeignRenderer();
    }

    private record ChatIdentity(PlayerId id, AtomicLong sequence, String world) {}
    // Identity keys never invoke a Player method (including hashCode) on the chat thread.
    private final Map<Player, ChatIdentity> identities = Collections.synchronizedMap(new IdentityHashMap<>());
    private final ProfileService profileService;
    private final ConfigManager configManager;
    private final MessageRegistry messageRegistry;
    private final com.dasannn.socialblueprint.feature.gui.StatusGuiService statusGuiService;
    private final Consumer<Runnable> mainThreadRunner;
    private final Function<UUID, Player> playerResolver;

    public AsyncChatListener(ProfileService profileService, ConfigManager configManager, MessageRegistry messageRegistry) {
        this(profileService, configManager, messageRegistry, null, null, null);
    }

    public AsyncChatListener(
            ProfileService profileService,
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            com.dasannn.socialblueprint.feature.gui.StatusGuiService statusGuiService
    ) {
        this(profileService, configManager, messageRegistry, statusGuiService, null, null);
    }

    public AsyncChatListener(
            ProfileService profileService,
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            com.dasannn.socialblueprint.feature.gui.StatusGuiService statusGuiService,
            Consumer<Runnable> mainThreadRunner
    ) {
        this(profileService, configManager, messageRegistry, statusGuiService, mainThreadRunner, null);
    }

    public AsyncChatListener(
            ProfileService profileService,
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            com.dasannn.socialblueprint.feature.gui.StatusGuiService statusGuiService,
            Consumer<Runnable> mainThreadRunner,
            Function<UUID, Player> playerResolver
    ) {
        this.profileService = Objects.requireNonNull(profileService, "ProfileService must not be null");
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "MessageRegistry must not be null");
        this.statusGuiService = statusGuiService;
        if (statusGuiService != null && mainThreadRunner == null) {
            throw new IllegalArgumentException("GUI chat prompts require a main-thread runner");
        }
        this.mainThreadRunner = mainThreadRunner != null ? mainThreadRunner : Runnable::run;
        this.playerResolver = playerResolver != null ? playerResolver : (uuid -> {
            try {
                return org.bukkit.Bukkit.getPlayer(uuid);
            } catch (Throwable t) {
                return null;
            }
        });
    }

    /** Called on the main thread, including online players during enable. */
    public void registerPlayer(Player player) {
        identities.put(player, new ChatIdentity(PlayerId.of(player.getUniqueId()), new AtomicLong(), player.getWorld() != null ? player.getWorld().getName() : ""));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { registerPlayer(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        ChatIdentity previous = identities.get(player);
        identities.put(player, new ChatIdentity(PlayerId.of(player.getUniqueId()),
                previous != null ? previous.sequence() : new AtomicLong(), player.getWorld().getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { identities.remove(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        try {
            // Hook GUI pending written reason prompt before normal chat formatting (Finding 3 / Finding 6)
            ChatIdentity identity = identities.get(event.getPlayer());
            UUID playerUuid = identity != null ? identity.id().uuid() : null;
            RuntimeSnapshot snapshot = configManager.snapshot();
            boolean disabled = snapshot.config().worldRules().isDisabled(identity != null ? identity.world() : null);
            if (disabled && playerUuid != null && statusGuiService != null)
                mainThreadRunner.accept(() -> statusGuiService.cancelPendingReason(playerUuid));
            if (!disabled && playerUuid != null && statusGuiService != null && statusGuiService.hasPendingReason(playerUuid)) {
                event.setCancelled(true);
                String rawReason = extractPlainText(event.message());
                mainThreadRunner.accept(() -> {
                    Player player = playerResolver.apply(playerUuid);
                    if (player != null && player.isOnline()) {
                        statusGuiService.consumePendingReason(player, rawReason);
                    } else {
                        statusGuiService.cancelPendingReason(playerUuid);
                    }
                });
                return;
            }
            // Read one immutable snapshot per event (T-040, T-042)
            String plain = extractPlainText(event.message());
            String original = disabled ? plain : snapshot.config().chatFilter().apply(plain,
                    messageRegistry.getRaw(snapshot, "chat-filter.replacement"));
            if (!original.equals(extractPlainText(event.message()))) event.message(Component.text(original));
            if (!(event.renderer() instanceof ChatRenderer.Default)) {
                warnForeignRenderer();
                return;
            }

            PlayerId playerId = identity != null ? identity.id() : null;

            // Single read from in-memory cache, neutral default if absent (T-042)
            PlayerSocialView view = profileService.getViewCached(playerId, snapshot);

            // Resolve tier and prefix dynamically from current snapshot per lookup (T-040)
            Tier tier = snapshot.config().tiers().ladder().resolve(view.status());
            String prefixStr = com.dasannn.socialblueprint.domain.PlayerNameFormat.prefix(snapshot.config().tiers().prefix(tier));
            Component prefixComp = (prefixStr != null && !prefixStr.isEmpty())
                    ? ColorParser.parse(prefixStr)
                    : Component.empty();


            long sequence = identity != null ? identity.sequence().getAndIncrement() : 1;
            long speakerSeed = playerUuid != null
                    ? playerUuid.getMostSignificantBits() ^ playerUuid.getLeastSignificantBits() : 0;
            Component body = messageBodyInWorld(original, view.psychosis(), speakerSeed, sequence,
                    snapshot.config().psychosis().chat(), snapshot.config().worldRules(), identity != null ? identity.world() : "");

            // Format name hover summary from message bundle (T-043)
            Component hoverComponent = buildHoverComponent(snapshot, view, tier);

            // Install renderer: preserves player name, attaches hover, captures shared body (T-043, T-044)
            event.renderer(createRenderer(prefixComp, hoverComponent, body));
        } catch (Throwable t) {
            // Keep even the fallback body fixed for all viewers.
            Component body = plainBody(event.message());
            if (event.renderer() instanceof ChatRenderer.Default) {
                event.renderer(new OwnedRenderer((source, sourceDisplayName, message, viewer) ->
                        Component.empty().append(sourceDisplayName).append(Component.text(": ")).append(body)));
            }
        }
    }

    public ChatRenderer createRenderer(Component prefixComp, Component hoverComponent, Component body) {
        return createRenderer(prefixComp, hoverComponent, body, (name, hover) ->
                (hover != null && !hover.equals(Component.empty()))
                        ? name.hoverEvent(HoverEvent.showText(hover))
                        : name
        );
    }

    public ChatRenderer createRenderer(
            Component prefixComp,
            Component hoverComponent,
            Component body,
            java.util.function.BiFunction<Component, Component, Component> hoverAttacher
    ) {
        Objects.requireNonNull(hoverAttacher, "hoverAttacher must not be null");
        Objects.requireNonNull(body, "body must not be null");
        return new OwnedRenderer((source, sourceDisplayName, message, viewer) -> {
            Component hoveredName;
            try {
                hoveredName = hoverAttacher.apply(sourceDisplayName, hoverComponent);
            } catch (Throwable t) {
                hoveredName = sourceDisplayName;
            }
            // Isolate prefix/name styling and actions from the plain message body.
            return PlayerNameRenderer.join(prefixComp, hoveredName).append(Component.text(": ")).append(body);
        });
    }

    public static Component messageBodyInWorld(String original, com.dasannn.socialblueprint.domain.PsychosisLevel level,
                                               long seed, long sequence, com.dasannn.socialblueprint.domain.ChatCorruptionConfig chat,
                                               com.dasannn.socialblueprint.config.WorldRules rules, String world) {
        return messageBody(original, rules.allowsWorld(world) ? level
                : com.dasannn.socialblueprint.domain.PsychosisLevel.NEUTRAL, seed, sequence, chat);
    }

    public static Component messageBody(String original, com.dasannn.socialblueprint.domain.PsychosisLevel level,
                                        long seed, long sequence, com.dasannn.socialblueprint.domain.ChatCorruptionConfig chat) {
        boolean episode = ChatCorruption.isEpisode(original, level, seed, sequence, chat);
        Component body = Component.text(episode ? ChatCorruption.corruptEpisode(original, level, seed, sequence, chat)
                : ChatCorruption.plain(original));
        return episode ? body.color(net.kyori.adventure.text.format.TextColor.fromHexString(chat.colour(level))) : body;
    }

    public static Component plainBody(Component message) {
        return Component.text(ChatCorruption.plain(extractPlainText(message)));
    }

    public Component buildHoverComponent(RuntimeSnapshot snapshot, PlayerSocialView view, Tier tier) {
        Component line1 = messageRegistry.render(snapshot, "chat.hover-status",
                Map.of("status", String.valueOf(view.status())));

        String prefixStr = com.dasannn.socialblueprint.domain.PlayerNameFormat.prefix(snapshot.config().tiers().prefix(tier));
        Component prefixComp = (prefixStr != null && !prefixStr.isEmpty())
                ? ColorParser.parse(prefixStr)
                : Component.empty();

        Component line2 = messageRegistry.render(snapshot, "chat.hover-tier",
                Map.of("tier", messageRegistry.tierName(snapshot, tier)),
                Map.of("prefix", prefixComp)
        );

        Component line3 = messageRegistry.render(snapshot, "chat.hover-confidence",
                Map.of("confidence", messageRegistry.getRaw(snapshot, "confidence." + view.confidence().name().toLowerCase(Locale.ROOT))));

        Component line4 = messageRegistry.renderMentalState(snapshot,
                messageRegistry.mentalStateLine(snapshot, view, "chat.hover-mental-state", false));
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

    public static String extractPlainText(Component component) {
        if (component == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (component instanceof net.kyori.adventure.text.TextComponent tc) {
            sb.append(tc.content());
        }
        for (Component child : component.children()) {
            sb.append(extractPlainText(child));
        }
        return sb.toString();
    }
}
