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
import org.bukkit.event.player.AsyncPlayerChatEvent;
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
 * - Prepares shared Psychosis corruption once per bridged message at LOWEST.
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRenderedChat(AsyncChatEvent event) {
        finishChat(event, event.isCancelled());
        if (!event.isCancelled() && !(event.renderer() instanceof OwnedRenderer) && !(event.renderer() instanceof ChatRenderer.Default))
            warnForeignRenderer();
    }

    private record ChatIdentity(PlayerId id, AtomicLong sequence, String world) {}
    private record PreparedChat(RuntimeSnapshot snapshot, PlayerSocialView view, Component body, boolean changed) {}
    // ponytail: one preparation lock; use per-player locks only if chat throughput warrants it.
    private final Map<Object, PreparedChat> prepared = Collections.synchronizedMap(new IdentityHashMap<>());
    // Paper completes legacy MONITOR before modern LOWEST. Retain only the value,
    // never the first event, until its opposite event consumes it or it expires.
    private record BridgeChat(String original, boolean legacy, long thread, long expires, PreparedChat context) {}
    private static final long BRIDGE_TTL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    private final Map<Player, java.util.List<BridgeChat>> bridges = new IdentityHashMap<>();
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
        this(profileService, configManager, messageRegistry, statusGuiService, mainThreadRunner, playerResolver, false);
    }

    public AsyncChatListener(
            ProfileService profileService,
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            com.dasannn.socialblueprint.feature.gui.StatusGuiService statusGuiService,
            Consumer<Runnable> mainThreadRunner,
            Function<UUID, Player> playerResolver,
            boolean essentialsChat
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
    public void onQuit(PlayerQuitEvent event) {
        identities.remove(event.getPlayer());
        synchronized (prepared) { bridges.remove(event.getPlayer()); }
    }

    public void onPrepareChat(AsyncChatEvent event) {
        if (event.isCancelled() || prepared.containsKey(event) || event.renderer() instanceof OwnedRenderer) return;
        try {
            PreparedChat context = prepareChat(event, event.getPlayer(), extractPlainText(event.message()),
                    extractPlainText(event.originalMessage()), false);
            if (context == null) event.setCancelled(true);
            else if (context.changed()) event.message(context.body());
        } catch (Throwable t) {
            // Preserve chat availability if preparation fails.
        }
    }

    @SuppressWarnings("deprecation")
    public void onPrepareLegacyChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled() || prepared.containsKey(event)) return;
        try {
            PreparedChat context = prepareChat(event, event.getPlayer(), event.getMessage(), event.getMessage(), true);
            if (context == null) event.setCancelled(true);
            else if (context.changed()) {
                String body = extractPlainText(context.body());
                if (!body.equals(event.getMessage())) event.setMessage(body);
            }
        } catch (Throwable t) {
            // Preserve chat availability if preparation fails.
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRenderedLegacyChat(AsyncPlayerChatEvent event) {
        finishChat(event, event.isCancelled());
    }

    private void finishChat(Object event, boolean cancelled) {
        synchronized (prepared) {
            PreparedChat context = prepared.remove(event);
            if (cancelled && context != null) {
                bridges.values().forEach(values -> values.removeIf(value -> value.context() == context));
            }
            pruneBridges(System.nanoTime());
        }
    }

    private void pruneBridges(long now) {
        bridges.values().removeIf(values -> {
            values.removeIf(value -> now - value.expires() >= 0);
            return values.isEmpty();
        });
    }

    private PreparedChat prepareChat(Object event, Player player, String plain, String original, boolean legacy) {
        synchronized (prepared) {
            PreparedChat existing = prepared.get(event);
            if (existing != null) return existing;
            long now = System.nanoTime();
            pruneBridges(now);
            ChatIdentity identity = identities.get(player);
            java.util.List<BridgeChat> pending = bridges.get(player);
            if (identity != null && pending != null) {
                for (var iterator = pending.iterator(); iterator.hasNext();) {
                    BridgeChat bridge = iterator.next();
                    if (bridge.legacy() != legacy && bridge.thread() == Thread.currentThread().threadId()
                            && (bridge.original().equals(original)
                            || extractPlainText(bridge.context().body()).equals(plain))) {
                        iterator.remove();
                        if (pending.isEmpty()) bridges.remove(player);
                        prepared.put(event, bridge.context());
                        return bridge.context();
                    }
                }
            }
            RuntimeSnapshot snapshot = configManager.snapshot();
            if (consumeReason(player, plain, snapshot)) return null;
            boolean disabled = snapshot.config().worldRules().isDisabled(identity != null ? identity.world() : null);
            String filtered = disabled ? plain : snapshot.config().chatFilter().apply(plain,
                    messageRegistry.getRaw(snapshot, "chat-filter.replacement"));
            PlayerSocialView view = profileService.getViewCached(identity != null ? identity.id() : null, snapshot);
            long sequence = identity != null ? identity.sequence().getAndIncrement() : 1;
            UUID uuid = identity != null ? identity.id().uuid() : null;
            long seed = uuid != null ? uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits() : 0;
            Component body = messageBodyInWorld(filtered, view.psychosis(), seed, sequence,
                    snapshot.config().psychosis().chat(), snapshot.config().worldRules(), identity != null ? identity.world() : "");
            boolean episode = body.color() != null;
            // Intact input retains its formatting; filtering alone keeps the original filter output.
            if (!episode) body = Component.text(filtered);
            PreparedChat context = new PreparedChat(snapshot, view, body, episode || !filtered.equals(plain));
            prepared.put(event, context);
            if (identity != null) bridges.computeIfAbsent(player, ignored -> new java.util.ArrayList<>())
                    .add(new BridgeChat(original, legacy, Thread.currentThread().threadId(), now + BRIDGE_TTL_NANOS, context));
            return context;
        }
    }

    private boolean consumeReason(Player player, String rawReason, RuntimeSnapshot snapshot) {
        if (statusGuiService == null) return false;
        ChatIdentity identity = identities.get(player);
        UUID uuid = identity != null ? identity.id().uuid() : null;
        if (uuid == null) return false;
        if (snapshot.config().worldRules().isDisabled(identity.world())) {
            mainThreadRunner.accept(() -> statusGuiService.cancelPendingReason(uuid));
            return false;
        }
        if (!statusGuiService.hasPendingReason(uuid)) return false;
        mainThreadRunner.accept(() -> {
            Player online = playerResolver.apply(uuid);
            if (online != null && online.isOnline()) statusGuiService.consumePendingReason(online, rawReason);
            else statusGuiService.cancelPendingReason(uuid);
        });
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (event.isCancelled() || event.renderer() instanceof OwnedRenderer) return;
        try {
            if (!prepared.containsKey(event)) onPrepareChat(event);
            PreparedChat context = prepared.get(event);
            if (event.isCancelled() || context == null) return;
            if (!(event.renderer() instanceof ChatRenderer.Default)
                    && context.snapshot().config().foreignRenderer().mode().equals("leave")) {
                warnForeignRenderer();
                return;
            }
            installRenderer(event, context.snapshot(), context.view(),
                    context.changed() ? context.body() : event.message());
        } catch (Throwable t) {
            Component body = plainBody(event.message());
            if (event.renderer() instanceof ChatRenderer.Default) {
                event.renderer(new OwnedRenderer((source, sourceDisplayName, message, viewer) ->
                        Component.empty().append(sourceDisplayName).append(Component.text(": ")).append(body)));
            }
        }
    }

    private void installRenderer(AsyncChatEvent event, RuntimeSnapshot snapshot, PlayerSocialView view, Component body) {
        Tier tier = snapshot.config().tiers().ladder().resolve(view.status());
        String prefixStr = com.dasannn.socialblueprint.domain.PlayerNameFormat.prefix(snapshot.config().tiers().prefix(tier));
        Component prefixComp = (prefixStr != null && !prefixStr.isEmpty())
                ? ColorParser.parse(prefixStr) : Component.empty();
        Component hover = buildHoverComponent(snapshot, view, tier);
        ChatRenderer renderer = event.renderer();
        if (!(renderer instanceof ChatRenderer.Default)) {
            event.renderer(createForeignRenderer(renderer, prefixComp, hover, body, snapshot.config().foreignRenderer()));
        } else {
            event.renderer(createRenderer(prefixComp, hover, body));
        }
    }

    public ChatRenderer createForeignRenderer(ChatRenderer foreign, Component prefix, Component hover, Component body,
            com.dasannn.socialblueprint.config.ForeignRendererConfig config) {
        if (foreign instanceof OwnedRenderer || config.mode().equals("leave")) return foreign;
        Component prefixComp = config.prefix().equals("before-line") ? attachHover(prefix, hover) : prefix;
        Component leading = prefix.equals(Component.empty()) ? Component.empty()
                : Component.empty().append(prefixComp).append(Component.space());
        // Reuse the default name summary, preserving name styling/click actions and the foreign line structure.
        return new OwnedRenderer((source, name, message, viewer) -> {
            Component hoveredName = attachHover(name, hover);
            Component displayName = config.prefix().equals("display-name") ? leading.append(hoveredName) : hoveredName;
            Component line = foreign.render(source, displayName, body, viewer);
            return config.prefix().equals("before-line") && !prefix.equals(Component.empty()) ? leading.append(line) : line;
        });
    }

    public ChatRenderer createRenderer(Component prefixComp, Component hoverComponent, Component body) {
        return createRenderer(prefixComp, hoverComponent, body, AsyncChatListener::attachHover);
    }

    private static Component attachHover(Component name, Component hover) {
        return (hover != null && !hover.equals(Component.empty()))
                ? name.hoverEvent(HoverEvent.showText(hover)) : name;
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
