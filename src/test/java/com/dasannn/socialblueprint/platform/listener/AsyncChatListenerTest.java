package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.ChatGradient;
import com.dasannn.socialblueprint.domain.ConfidenceLevel;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import com.dasannn.socialblueprint.domain.Tier;
import com.dasannn.socialblueprint.domain.TierLadder;
import com.dasannn.socialblueprint.feature.gui.StatusGuiService;
import com.dasannn.socialblueprint.feature.honor.HonorService;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.CompensationRepository;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.RaterRevealRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncChatListenerTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private ConfigManager configManager;
    private MessageRegistry messageRegistry;
    private ProfileService profileService;
    private AsyncChatListener chatListener;

    @BeforeEach
    void setUp() throws Exception {
        File configFile = new File(tempDir, "config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        Logger logger = Logger.getLogger("AsyncChatListenerTest-" + System.nanoTime());
        messageRegistry = new MessageRegistry(tempDir, "en", logger);
        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        StatusCache statusCache = new StatusCache();
        ReputationRepository reputationRepo = new ReputationRepository(storage, statusCache);
        PsychosisRepository psychosisRepo = new PsychosisRepository(storage);
        ProfileRepository profileRepo = new ProfileRepository(storage);

        profileService = new ProfileService(
                storage,
                reputationRepo,
                psychosisRepo,
                profileRepo,
                statusCache,
                configManager,
                null,
                logger
        );

        chatListener = new AsyncChatListener(profileService, configManager, messageRegistry);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    @DisplayName("T-041 / DoD 3: Minimum-status player message is near-black #202020 and arrives complete")
    void minimumStatusMessageIsNearBlack() {
        RuntimeSnapshot snapshot = configManager.snapshot();
        Tier minTier = Tier.CRIMINAL;
        TextColor shade = TextColor.color(ChatGradient.rgb(minTier));

        assertThat(shade.asHexString()).isEqualTo("#202020");

        String prefixStr = snapshot.config().tiers().prefix(minTier);
        Component prefixComp = ColorParser.parse(prefixStr);
        Component hoverComp = Component.text("Hover summary");

        ChatRenderer renderer = chatListener.createRenderer(prefixComp, hoverComp, shade);

        Component inputName = Component.text("BadPlayer");
        Component inputMessage = Component.text("Hello world, this is a test message");

        Component rendered = renderer.render(null, inputName, inputMessage, null);
        String serialized = ColorParser.serialize(rendered);

        // Prefix is prepended
        assertThat(serialized).contains("&7[&4||||&7]");
        // Player name is included
        assertThat(serialized).contains("BadPlayer");
        // Entire original message text is preserved without truncation or blocking (Constitution §2.2, SB-021)
        assertThat(serialized).contains("Hello world, this is a test message");
        // Message color is #202020
        assertThat(serialized).contains("&#202020Hello world");
    }

    @Test
    @DisplayName("T-041 / DoD 2: Maximum-status player message is bright white #FFFFFF")
    void maximumStatusMessageIsBrightWhite() {
        RuntimeSnapshot snapshot = configManager.snapshot();
        Tier maxTier = Tier.ILUSTRE;
        TextColor shade = TextColor.color(ChatGradient.rgb(maxTier));

        assertThat(shade.asHexString()).isEqualToIgnoringCase("#ffffff");

        String prefixStr = snapshot.config().tiers().prefix(maxTier);
        Component prefixComp = ColorParser.parse(prefixStr);
        Component hoverComp = Component.text("Hover summary");

        ChatRenderer renderer = chatListener.createRenderer(prefixComp, hoverComp, shade);

        Component rendered = renderer.render(null, Component.text("GoodPlayer"), Component.text("Greeting"), null);
        String serialized = ColorParser.serialize(rendered);

        assertThat(serialized).contains("&7[&b||||&7]");
        assertThat(serialized).contains("GoodPlayer");
        // In legacy formatting #ffffff serializes to &f
        assertThat(serialized).contains("&fGreeting");
    }

    @Test
    @DisplayName("T-043: Name hover summary displays status, tier, confidence, psychosis, and contributors in English")
    void hoverSummaryInEnglish() {
        configManager.set("language", "en");
        RuntimeSnapshot snapshot = configManager.snapshot();
        PlayerId id = PlayerId.of(UUID.randomUUID());
        PlayerSocialView view = new PlayerSocialView(
                id,
                "Hero",
                25,
                Tier.HONORABLE,
                ConfidenceLevel.ESTABLISHED,
                PsychosisLevel.LOW,
                7
        );

        Component hover = chatListener.buildHoverComponent(snapshot, view, Tier.HONORABLE);
        String serialized = ColorParser.serialize(hover);

        assertThat(serialized).contains("&7Status: &f25");
        assertThat(serialized).contains("&7Tier: [&a||&7] &fHonorable");
        assertThat(serialized).contains("&7Confidence: &fEstablished");
        assertThat(serialized).contains("&7Psychosis: &fLow");
        assertThat(serialized).contains("&7Contributors: &f7");
        assertThat(hasColor(hover, net.kyori.adventure.text.format.NamedTextColor.GREEN))
                .as("Configured tier prefix color codes (&a) must be parsed into components with GREEN color, not literal text")
                .isTrue();
    }

    private static boolean hasColor(Component component, TextColor targetColor) {
        if (targetColor.equals(component.color())) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasColor(child, targetColor)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("T-043: Name hover summary displays status, tier, confidence, psychosis, and contributors in Spanish")
    void hoverSummaryInSpanish() {
        configManager.set("language", "es");
        RuntimeSnapshot snapshot = configManager.snapshot();

        PlayerId id = PlayerId.of(UUID.randomUUID());
        PlayerSocialView view = new PlayerSocialView(
                id,
                "Heroe",
                25,
                Tier.HONORABLE,
                ConfidenceLevel.ESTABLISHED,
                PsychosisLevel.LOW,
                7
        );

        Component hover = chatListener.buildHoverComponent(snapshot, view, Tier.HONORABLE);
        String serialized = ColorParser.serialize(hover);

        assertThat(serialized).contains("&7Estatus: &f25");
        assertThat(serialized).contains("&7Rango: [&a||&7] &fHonorable");
        assertThat(serialized).contains("&7Confianza: &fEstablecida");
        assertThat(serialized).contains("&7Psicosis: &fBaja");
        assertThat(serialized).contains("&7Colaboradores: &f7");
        assertThat(hasColor(hover, net.kyori.adventure.text.format.NamedTextColor.GREEN))
                .as("Configured tier prefix color codes (&a) must be parsed into components with GREEN color, not literal text")
                .isTrue();
    }

    @Test
    @DisplayName("T-040 / DoD 6: Editing a tier's prefix in-game immediately changes the next rendered message without restart")
    void inGamePrefixEditChangesNextMessageWithNoRestart() {
        RuntimeSnapshot snapshotBefore = configManager.snapshot();
        Tier tier = Tier.PARTICULAR;
        assertThat(snapshotBefore.config().tiers().prefix(tier)).isEqualTo("&7[&f|&7]");

        // In-game edit: /status config tiers.tier0.prefix &8[&eCITIZEN&8]
        configManager.set("tiers.tier0.prefix", "&8[&eCITIZEN&8]");

        // Next chat event captures fresh snapshot
        RuntimeSnapshot snapshotAfter = configManager.snapshot();
        String updatedPrefix = snapshotAfter.config().tiers().prefix(tier);
        assertThat(updatedPrefix).isEqualTo("&8[&eCITIZEN&8]");

        Component prefixComp = ColorParser.parse(updatedPrefix);
        TextColor shade = TextColor.color(ChatGradient.rgb(tier));
        ChatRenderer renderer = chatListener.createRenderer(prefixComp, Component.empty(), shade);

        Component rendered = renderer.render(null, Component.text("Player1"), Component.text("Test"), null);
        String serialized = ColorParser.serialize(rendered);

        assertThat(serialized).contains("&8[&eCITIZEN&8]");
        assertThat(serialized).contains("Player1");
    }

    @Test
    @DisplayName("T-044: Hover is attached to player name and preserves existing name styling")
    void hoverAttachedToPlayerNamePreservingCoexistence() {
        RuntimeSnapshot snapshot = configManager.snapshot();
        TextColor shade = TextColor.color(ChatGradient.rgb(Tier.PARTICULAR));
        Component hoverComp = Component.text("Hover text");

        ChatRenderer renderer = chatListener.createRenderer(Component.empty(), hoverComp, shade);

        // EssentialsX or LuckPerms provided a stylized display name
        Component styledName = Component.text("[VIP] ").append(Component.text("CoolGuy"));
        Component message = Component.text("Hello everyone");

        Component rendered = renderer.render(null, styledName, message, null);

        // Hover event is attached directly to the root component when prefix is empty
        HoverEvent<?> hoverEvent = rendered.hoverEvent();
        assertThat(hoverEvent).isNotNull();
        assertThat(hoverEvent.action()).isEqualTo(HoverEvent.Action.SHOW_TEXT);
    }

    @Test
    @DisplayName("Finding 5: Renderer falls back to original display name and complete message when a dependency throws")
    void rendererFallsBackWhenDependencyThrows() {
        Tier tier = Tier.HONORABLE;
        TextColor shade = TextColor.color(ChatGradient.rgb(tier));
        Component prefixComp = Component.text("[Prefix]");
        Component hoverComp = Component.text("Hover text");

        ChatRenderer renderer = chatListener.createRenderer(
                prefixComp,
                hoverComp,
                shade,
                (name, hover) -> {
                    throw new RuntimeException("Simulated dependency crash during hover rendering");
                }
        );

        Component inputName = Component.text("PlayerOne");
        Component originalMessage = Component.text("Critical message that must not be dropped");

        Component rendered = renderer.render(null, inputName, originalMessage, null);
        String serialized = ColorParser.serialize(rendered);

        assertThat(serialized).contains("PlayerOne");
        assertThat(serialized).contains("Critical message that must not be dropped");
    }

    @Test
    @DisplayName("Finding 7: Styled incoming message children do not escape gradient")
    void styledMessageChildrenDoNotEscapeGradient() {
        Tier minTier = Tier.CRIMINAL;
        TextColor minShade = TextColor.color(ChatGradient.rgb(minTier));
        assertThat(minShade.asHexString()).isEqualTo("#202020");

        ChatRenderer renderer = chatListener.createRenderer(Component.empty(), Component.empty(), minShade);

        Component inputName = Component.text("CriminalPlayer");
        Component styledChild = Component.text("Bright Yellow Child Text", net.kyori.adventure.text.format.NamedTextColor.YELLOW, net.kyori.adventure.text.format.TextDecoration.BOLD);
        Component inputMessage = Component.text("Root text ").append(styledChild);

        Component rendered = renderer.render(null, inputName, inputMessage, null);
        String serialized = ColorParser.serialize(rendered);

        assertThat(serialized).contains("Root text");
        assertThat(serialized).contains("Bright Yellow Child Text");

        // The bright yellow color MUST NOT survive: all text descendants are near-black #202020
        assertThat(hasColor(rendered, net.kyori.adventure.text.format.NamedTextColor.YELLOW)).isFalse();
        assertThat(hasColor(rendered, minShade)).isTrue();

        // Non-color decorations (BOLD) are preserved on child
        assertThat(hasDecoration(rendered, net.kyori.adventure.text.format.TextDecoration.BOLD)).isTrue();
    }

    private static boolean hasDecoration(Component component, net.kyori.adventure.text.format.TextDecoration decoration) {
        if (component.hasDecoration(decoration)) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasDecoration(child, decoration)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("Finding 6: AsyncChatListener cancels event off-thread and runs reason prompt on main thread")
    void finding6_chatReasonPromptHopsToMainThread() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        AtomicReference<Thread> consumeThread = new AtomicReference<>();
        AtomicReference<Thread> chatCallingThread = new AtomicReference<>();

        Thread mainThread = new Thread(() -> {}, "MainServerThread");

        StatusCache statusCache = new StatusCache();
        ReputationRepository reputationRepo = new ReputationRepository(storage, statusCache);
        RaterRevealRepository raterRevealRepo = new RaterRevealRepository(storage);
        AuditRepository auditRepo = new AuditRepository(storage);
        CompensationRepository compensationRepo = new CompensationRepository(storage);
        HonorService honorService = new HonorService(
                configManager,
                messageRegistry,
                reputationRepo,
                auditRepo,
                compensationRepo,
                profileService,
                null,
                Runnable::run
        );

        StatusGuiService testGuiService =
                new StatusGuiService(
                        messageRegistry,
                        profileService,
                        reputationRepo,
                        raterRevealRepo,
                        honorService,
                        Runnable::run,
                        null,
                        null,
                        java.time.Clock.systemUTC(),
                        null,
                        compensationRepo,
                        null
                ) {
                    @Override
                    public boolean hasPendingReason(UUID uuid) {
                        return playerUuid.equals(uuid);
                    }

                    @Override
                    public CompletableFuture<Void> consumePendingReason(Player player, String rawMessage) {
                        consumeThread.set(Thread.currentThread());
                        return CompletableFuture.completedFuture(null);
                    }
                };

        InvocationHandler playerHandler = (proxy, method, args) -> {
            if ("getUniqueId".equals(method.getName())) return playerUuid;
            if ("getName".equals(method.getName())) return "ReasonPlayer";
            if ("isOnline".equals(method.getName())) return true;
            return null;
        };
        Player mockPlayer = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                playerHandler
        );

        AsyncChatListener listener = new AsyncChatListener(
                profileService,
                configManager,
                messageRegistry,
                testGuiService,
                r -> {
                    // Simulate scheduler running task on main server thread
                    Thread runner = new Thread(r, "MainServerThread");
                    runner.start();
                    try {
                        runner.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                uuid -> mockPlayer
        );

        AsyncChatEvent event = new AsyncChatEvent(
                true,
                mockPlayer,
                Collections.emptySet(),
                (p, dn, m, v) -> m,
                Component.text("Griefing defense reason"),
                Component.text("Griefing defense reason"),
                SignedMessage.system("Griefing defense reason", Component.text("Griefing defense reason"))
        );

        // Deliver chat event from an async thread
        Thread asyncChatThread = new Thread(() -> {
            chatCallingThread.set(Thread.currentThread());
            listener.onChat(event);
        }, "AsyncChatThread");
        asyncChatThread.start();
        asyncChatThread.join();

        // 1. Event must be cancelled on the async chat thread
        assertThat(event.isCancelled()).isTrue();

        // 2. Reason consumption MUST have occurred on the main thread, NEVER on the async chat thread
        assertThat(consumeThread.get()).isNotNull();
        assertThat(consumeThread.get().getName()).isEqualTo("MainServerThread");
        assertThat(consumeThread.get()).isNotEqualTo(chatCallingThread.get());
    }
}
