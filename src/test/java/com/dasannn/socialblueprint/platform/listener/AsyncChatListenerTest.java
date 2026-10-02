package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.ChatCorruption;
import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.domain.CombatContext;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import java.time.Instant;
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
    private HoverRegistry messageRegistry;
    private ProfileService profileService;
    private PsychosisRepository psychosisRepo;
    private AsyncChatListener chatListener;

    private record HoverCall(String key, java.util.Map<String, String> values,
                             java.util.Map<String, Component> components, Component rendered) {}

    private static class HoverRegistry extends MessageRegistry {
        final java.util.List<HoverCall> calls = new java.util.ArrayList<>();
        HoverRegistry(File folder, Logger logger) { super(folder, "en", logger); }
        @Override public Component render(RuntimeSnapshot snapshot, String key, java.util.Map<String, String> values,
                                          java.util.Map<String, Component> components) {
            Component result = super.render(snapshot, key, values, components);
            calls.add(new HoverCall(key, java.util.Map.copyOf(values), java.util.Map.copyOf(components), result));
            return result;
        }
    }

    @Test
    void sereneHoverUsesNeutralMadnessAndSeparateMagnitude() {
        var snapshot = configManager.snapshot();
        var view = new PlayerSocialView(PlayerId.of(UUID.randomUUID()), "Peaceful", 0,
                Tier.PARTICULAR, ConfidenceLevel.UNKNOWN, PsychosisLevel.SERENITY, 7, 0.36);
        chatListener.buildHoverComponent(snapshot, view, Tier.PARTICULAR);
        assertThat(messageRegistry.calls).anySatisfy(call -> {
            assertThat(call.key()).isEqualTo("chat.hover-psychosis");
            assertThat(call.values()).containsEntry("psychosis", messageRegistry.getRaw(snapshot, "psychosis.neutral.name"));
        }).anySatisfy(call -> {
            assertThat(call.key()).isEqualTo("chat.hover-serenity");
            assertThat(call.values()).containsEntry("serenity", "0.4");
        }).anySatisfy(call -> {
            assertThat(call.key()).isEqualTo("chat.hover-contributors");
            assertThat(call.values()).containsEntry("contributors", "7");
        });
    }

    private void assertHoverInputsAndStructure(RuntimeSnapshot snapshot, Component hover) {
        var calls = messageRegistry.calls;
        assertThat(calls).extracting(HoverCall::key).containsExactly("chat.hover-status", "chat.hover-tier",
                "chat.hover-confidence", "chat.hover-psychosis", "chat.hover-serenity", "chat.hover-contributors");
        assertThat(calls).extracting(HoverCall::values).containsExactly(
                java.util.Map.of("status", "25"),
                java.util.Map.of("tier", messageRegistry.getRaw(snapshot, "tiers.tier2")),
                java.util.Map.of("confidence", messageRegistry.getRaw(snapshot, "confidence.established")),
                java.util.Map.of("psychosis", messageRegistry.getRaw(snapshot, "psychosis.low")),
                java.util.Map.of("serenity", "0.0"),
                java.util.Map.of("contributors", "7"));
        assertThat(calls.get(1).components()).containsOnlyKeys("prefix").containsEntry("prefix",
                ColorParser.parse(snapshot.config().tiers().prefix(Tier.HONORABLE)));
        assertThat(calls.get(0).components()).isEmpty();
        assertThat(calls.get(2).components()).isEmpty();
        assertThat(calls.get(3).components()).isEmpty();
        assertThat(calls.get(4).components()).isEmpty();
        Component expected = calls.getFirst().rendered();
        for (int i = 1; i < calls.size(); i++) expected = expected.append(Component.newline()).append(calls.get(i).rendered());
        assertThat(hover).isEqualTo(expected);
        assertThat(hover.clickEvent()).isNull();
    }

    @BeforeEach
    void setUp() throws Exception {
        File configFile = new File(tempDir, "config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        Logger logger = Logger.getLogger("AsyncChatListenerTest-" + System.nanoTime());
        messageRegistry = new HoverRegistry(tempDir, logger);
        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        StatusCache statusCache = new StatusCache();
        ReputationRepository reputationRepo = new ReputationRepository(storage, statusCache);
        psychosisRepo = new PsychosisRepository(storage);
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
    void corruptionSettingsAreLiveEditableAndRejectUnsafeValuesAtomically() {
        RuntimeSnapshot before = configManager.snapshot();
        assertThat(configManager.isEditableKey(before, "psychosis.chat.medium-rate")).isTrue();
        configManager.set("psychosis.chat.medium-rate", "12");
        RuntimeSnapshot changed = configManager.snapshot();
        assertThat(changed.config().psychosis().chat().mediumRate()).isEqualTo(12);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> configManager.set("psychosis.chat.extreme-extent", "100"))
                .isInstanceOf(com.dasannn.socialblueprint.config.ConfigValidationException.class);
        assertThat(configManager.snapshot()).isSameAs(changed);
        assertThat(before.config().psychosis().chat().mediumRate()).isEqualTo(10);
    }

    @Test
    void everyTierKeepsItsPrefixAndUsesTheSameUncolouredBody() {
        RuntimeSnapshot snapshot = configManager.snapshot();
        Component body = AsyncChatListener.plainBody(Component.text("Hello world"));
        for (Tier tier : Tier.values()) {
            Component prefix = ColorParser.parse(snapshot.config().tiers().prefix(tier));
            Component rendered = chatListener.createRenderer(prefix, Component.empty(), body)
                    .render(null, Component.text("Speaker"), Component.empty(), null);
            assertThat(rendered.children().getLast()).isEqualTo(body);
            assertThat(rendered.children().getLast().color()).isNull();
            assertThat(rendered).isEqualTo(Component.empty().append(prefix).append(Component.space()).append(Component.text("Speaker"))
                    .append(Component.text(": ")).append(body));
        }
    }

    @Test
    void episodeColourIsGradedAndShortMessagesStillDarkenOnlyOnEpisodes() {
        var chat = configManager.snapshot().config().psychosis().chat();
        for (PsychosisLevel level : PsychosisLevel.values()) {
            boolean sawEpisode = false;
            for (int sequence = 0; sequence < 1000; sequence++) {
                Component body = AsyncChatListener.messageBody("hi", level, 123, sequence, chat);
                boolean episode = ChatCorruption.isEpisode("hi", level, 123, sequence, chat);
                assertThat(AsyncChatListener.extractPlainText(body)).isEqualTo("hi");
                assertThat(body.color()).isEqualTo(episode ? TextColor.fromHexString(chat.colour(level)) : null);
                assertThat(body.clickEvent()).isNull();
                assertThat(body.hoverEvent()).isNull();
                if ((sequence & 1) != 0) assertThat(body.color()).isNull();
                sawEpisode |= episode;
            }
            assertThat(sawEpisode).isEqualTo(level.hasMadnessEffects());
        }
    }

    @Test
    void foreignRendererBeforeOrAfterOurListenerIsPreservedAndLoggedOnce() {
        var logger = Logger.getLogger(AsyncChatListener.class.getName());
        java.util.List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();
        var handler = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { records.add(record); }
            public void flush() {}
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            ChatRenderer foreign = (p, name, body, viewer) -> body;
            AsyncChatEvent early = chatEvent(null, Component.text("hi"));
            early.renderer(foreign);
            chatListener.onChat(early);
            chatListener.onRenderedChat(early);
            assertThat(early.renderer()).isSameAs(foreign);
            AsyncChatEvent late = chatEvent(null, Component.text("hi"));
            chatListener.onChat(late);
            assertThat(late.renderer()).isNotSameAs(foreign);
            late.renderer(foreign);
            chatListener.onRenderedChat(late);
            assertThat(late.renderer()).isSameAs(foreign);
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().getMessage()).contains("replaced the chat renderer");
        } finally { logger.removeHandler(handler); }
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

        messageRegistry.calls.clear();
        Component hover = chatListener.buildHoverComponent(snapshot, view, Tier.HONORABLE);
        assertHoverInputsAndStructure(snapshot, hover);
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

        messageRegistry.calls.clear();
        Component hover = chatListener.buildHoverComponent(snapshot, view, Tier.HONORABLE);
        assertHoverInputsAndStructure(snapshot, hover);
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
        Component body = AsyncChatListener.plainBody(Component.text("Test"));
        ChatRenderer renderer = chatListener.createRenderer(prefixComp, Component.empty(), body);

        Component rendered = renderer.render(null, Component.text("Player1"), Component.text("Test"), null);
        String serialized = ColorParser.serialize(rendered);

        assertThat(serialized).contains("&8[&eCITIZEN&8]");
        assertThat(serialized).contains("Player1");
    }

    @Test
    @DisplayName("T-044: Hover is attached to player name and preserves existing name styling")
    void hoverAttachedToPlayerNamePreservingCoexistence() {
        RuntimeSnapshot snapshot = configManager.snapshot();
        Component body = AsyncChatListener.plainBody(Component.text("Hello everyone"));
        Component hoverComp = Component.text("Hover text");

        ChatRenderer renderer = chatListener.createRenderer(Component.empty(), hoverComp, body);

        // EssentialsX or LuckPerms provided a stylized display name
        Component styledName = Component.text("[VIP] ").append(Component.text("CoolGuy"));
        Component message = Component.text("Hello everyone");

        Component rendered = renderer.render(null, styledName, message, null);

        // Hover stays on the name, isolated from the message body.
        HoverEvent<?> hoverEvent = rendered.children().getFirst().hoverEvent();
        assertThat(hoverEvent).isNotNull();
        assertThat(hoverEvent.action()).isEqualTo(HoverEvent.Action.SHOW_TEXT);
    }

    @Test
    @DisplayName("Finding 5: Renderer falls back to original display name and complete message when a dependency throws")
    void rendererFallsBackWhenDependencyThrows() {
        Tier tier = Tier.HONORABLE;
        Component body = AsyncChatListener.plainBody(Component.text("Critical message that must not be dropped"));
        Component prefixComp = Component.text("[Prefix]");
        Component hoverComp = Component.text("Hover text");

        ChatRenderer renderer = chatListener.createRenderer(
                prefixComp,
                hoverComp,
                body,
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
    void playerTextNeverCarriesFormattingOrClickActions() {
        Component input = Component.text("&aHello §cworld <click:run_command:'/op me'>click</click>", NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/op me"))
                .append(Component.text(" &x&f&f&f&f&f&fchild").clickEvent(ClickEvent.openUrl("https://example.com")));
        Component body = AsyncChatListener.plainBody(input);
        assertThat(body.clickEvent()).isNull();
        assertThat(body.hoverEvent()).isNull();
        assertThat(body.children()).isEmpty();
        assertThat(body.color()).isNull();
        assertThat(AsyncChatListener.extractPlainText(body)).doesNotContain("&", "§");
        assertThat(AsyncChatListener.extractPlainText(body)).contains("<click:"); // literal text, never parsed
    }

    @Test
    void asyncMessageIsComputedOnceForSeveralViewersWithoutPlayerOrStorageCalls() throws Exception {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000123");
        PlayerId id = PlayerId.of(uuid);
        RuntimeSnapshot snapshot = configManager.snapshot();
        for (int i = 0; i < snapshot.config().psychosis().extremeThreshold(); i++) {
            psychosisRepo.saveAsync(new PsychosisEvent(id, PlayerId.of(UUID.randomUUID()),
                    CombatContext.OPEN, Instant.now())).join();
        }
        PlayerSocialView view = profileService.loadViewAsync(id, "Speaker", snapshot).join();
        assertThat(view.psychosis()).isEqualTo(PsychosisLevel.EXTREME);
        Thread setupThread = Thread.currentThread();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Player speaker = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if (Thread.currentThread() != setupThread) {
                        AssertionError error = new AssertionError("Off-thread Player call: " + method.getName());
                        failure.set(error);
                        throw error;
                    }
                    if (method.getName().equals("getUniqueId")) return uuid;
                    throw new AssertionError("Unexpected Player call: " + method.getName());
                });
        chatListener.registerPlayer(speaker);
        storage.close(); // Any database access or executor submission now fails.
        String original = "Please bring wooden supplies to the village before sunset";
        Component prefix = ColorParser.parse(snapshot.config().tiers().prefix(view.tier()));
        AtomicBoolean changed = new AtomicBoolean();
        Thread async = new Thread(() -> {
            try {
                for (int sequence = 0; sequence < 100; sequence++) {
                    AsyncChatEvent event = chatEvent(speaker, Component.text(original));
                    chatListener.onChat(event);
                    String expectedText = ChatCorruption.corrupt(original, view.psychosis(),
                            uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits(), sequence,
                            snapshot.config().psychosis().chat());
                    boolean episode = ChatCorruption.isEpisode(original, view.psychosis(),
                            uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits(), sequence,
                            snapshot.config().psychosis().chat());
                    Component expectedBody = Component.text(expectedText);
                    if (episode) expectedBody = expectedBody.color(TextColor.fromHexString("#303030"));
                    if (!expectedText.equals(original)) changed.set(true);
                    Component first = null;
                    for (int reader = 0; reader < 4; reader++) {
                        Audience viewer = reader == 0 ? speaker : (Audience) Proxy.newProxyInstance(Audience.class.getClassLoader(),
                                new Class<?>[]{Audience.class}, (proxy, method, args) -> {
                                    throw new AssertionError("Renderer touched viewer");
                                });
                        Component rendered = event.renderer().render(speaker, Component.text("Speaker"),
                                Component.text("different renderer input " + reader), viewer);
                        assertThat(rendered.children().getLast()).isEqualTo(expectedBody);
                        assertThat(rendered).isEqualTo(Component.empty().append(prefix).append(Component.space())
                                .append(Component.text("Speaker").hoverEvent(HoverEvent.showText(
                                        chatListener.buildHoverComponent(snapshot, view, view.tier()))))
                                .append(Component.text(": ")).append(expectedBody));
                        if (first == null) first = rendered;
                        else assertThat(rendered).isEqualTo(first);
                    }
                    assertThat(event.isCancelled()).isFalse();
                }
                assertThat(profileService.getViewCached(PlayerId.of(UUID.randomUUID()), snapshot).psychosis())
                        .isEqualTo(PsychosisLevel.NEUTRAL); // cold read also submits no storage work
            } catch (Throwable ex) { failure.set(ex); }
        }, "AsyncChatThread");
        async.start();
        async.join();
        assertThat(failure.get()).isNull();
        assertThat(changed).isTrue();
    }

    private static AsyncChatEvent chatEvent(Player player, Component message) {
        return new AsyncChatEvent(true, player, Collections.emptySet(), ChatRenderer.defaultRenderer(),
                message, message, SignedMessage.system(AsyncChatListener.extractPlainText(message), message));
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

        listener.registerPlayer(mockPlayer);

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
