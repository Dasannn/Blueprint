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
import org.bukkit.event.player.AsyncPlayerChatEvent;
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
    private final java.util.concurrent.atomic.AtomicInteger cacheReads = new java.util.concurrent.atomic.AtomicInteger();

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
    void sereneHoverUsesOneMentalStateLine() {
        var snapshot = configManager.snapshot();
        var view = new PlayerSocialView(PlayerId.of(UUID.randomUUID()), "Peaceful", 0,
                Tier.PARTICULAR, ConfidenceLevel.UNKNOWN, PsychosisLevel.SERENITY, 7, 0.36);
        chatListener.buildHoverComponent(snapshot, view, Tier.PARTICULAR);
        assertThat(messageRegistry.calls).hasSize(5);
        assertThat(messageRegistry.calls).anySatisfy(call -> {
            assertThat(call.key()).isEqualTo("chat.hover-mental-state-serenity");
            assertThat(call.values()).containsExactlyEntriesOf(java.util.Map.of("value", "0.4"));
        }).anySatisfy(call -> {
            assertThat(call.key()).isEqualTo("chat.hover-contributors");
            assertThat(call.values()).containsEntry("contributors", "7");
        });
    }

    private void assertHoverInputsAndStructure(RuntimeSnapshot snapshot, Component hover) {
        var calls = messageRegistry.calls;
        assertThat(calls).extracting(HoverCall::key).containsExactly("chat.hover-status", "chat.hover-tier",
                "chat.hover-confidence", "chat.hover-mental-state-psychosis", "chat.hover-contributors");
        assertThat(calls).extracting(HoverCall::values).containsExactly(
                java.util.Map.of("status", "25"),
                java.util.Map.of("tier", messageRegistry.getRaw(snapshot, "tiers.tier2")),
                java.util.Map.of("confidence", messageRegistry.getRaw(snapshot, "confidence.established")),
                java.util.Map.of("psychosis", messageRegistry.getRaw(snapshot, "psychosis.low"), "value", "0"),
                java.util.Map.of("contributors", "7"));
        assertThat(calls.get(1).components()).containsOnlyKeys("prefix").containsEntry("prefix",
                ColorParser.parse(snapshot.config().tiers().prefix(Tier.HONORABLE)));
        assertThat(calls.get(0).components()).isEmpty();
        assertThat(calls.get(2).components()).isEmpty();
        assertThat(calls.get(3).components()).containsOnlyKeys("psychosis").containsEntry("psychosis",
                ColorParser.parse(messageRegistry.getRaw(snapshot, "psychosis.low")));
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
        ) {
            @Override public PlayerSocialView getViewCached(PlayerId id, RuntimeSnapshot snapshot) {
                cacheReads.incrementAndGet();
                return super.getViewCached(id, snapshot);
            }
        };

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
            assertThat(sawEpisode).isEqualTo(level.ordinal() >= PsychosisLevel.MEDIUM.ordinal());
        }
    }

    @Test
    void foreignWrapperReplacesBodyAndPlacesPrefixWithoutChangingForeignFormat() {
        Component prefix = Component.text("[tier]", NamedTextColor.GREEN);
        Component name = Component.text("Nick", NamedTextColor.YELLOW)
                .hoverEvent(HoverEvent.showText(Component.text("foreign hover")))
                .clickEvent(ClickEvent.suggestCommand("/msg Nick "));
        Component body = Component.text("shared body", NamedTextColor.DARK_GRAY);
        var snapshot = configManager.snapshot();
        var view = new PlayerSocialView(PlayerId.of(UUID.randomUUID()), "Nick", 25,
                Tier.HONORABLE, ConfidenceLevel.ESTABLISHED, PsychosisLevel.LOW, 7);
        Component hover = chatListener.buildHoverComponent(snapshot, view, Tier.HONORABLE);
        assertHoverInputsAndStructure(snapshot, hover);
        Component hoveredName = chatListener.createRenderer(Component.empty(), hover, body)
                .render(null, name, Component.empty(), null).children().getFirst();
        assertThat(hoveredName.hoverEvent()).isEqualTo(HoverEvent.showText(hover));
        assertThat(hoveredName.clickEvent()).isEqualTo(name.clickEvent());
        Audience viewer = Audience.empty();
        AtomicReference<Component> passedName = new AtomicReference<>();
        AtomicReference<Component> passedBody = new AtomicReference<>();
        ChatRenderer foreign = (source, displayName, message, reader) -> {
            assertThat(reader).isSameAs(viewer);
            passedName.set(displayName);
            passedBody.set(message);
            return Component.text("world > ", NamedTextColor.BLUE).append(displayName)
                    .append(Component.text(" :: ")).append(message);
        };
        for (String placement : java.util.List.of("before-line", "display-name", "none")) {
            var config = new com.dasannn.socialblueprint.config.ForeignRendererConfig("wrap", placement);
            ChatRenderer wrapper = chatListener.createForeignRenderer(foreign, prefix, hover, body, config);
            Component rendered = wrapper.render(null, name, Component.text("ignored input"), viewer);
            Component expectedPrefix = placement.equals("before-line") ? prefix.hoverEvent(HoverEvent.showText(hover)) : prefix;
            Component leading = Component.empty().append(expectedPrefix).append(Component.space());
            Component expectedName = placement.equals("display-name") ? leading.append(hoveredName) : hoveredName;
            Component expectedLine = Component.text("world > ", NamedTextColor.BLUE).append(expectedName)
                    .append(Component.text(" :: ")).append(body);
            assertThat(passedBody.get()).isSameAs(body);
            assertThat(passedName.get()).isEqualTo(expectedName);
            assertThat(rendered).isEqualTo(placement.equals("before-line") ? leading.append(expectedLine) : expectedLine);
            assertThat(chatListener.createForeignRenderer(wrapper, prefix, hover, body, config)).isSameAs(wrapper);
        }
        var leave = new com.dasannn.socialblueprint.config.ForeignRendererConfig("leave", "before-line");
        assertThat(chatListener.createForeignRenderer(foreign, prefix, hover, body, leave)).isSameAs(foreign);
        Component untouched = foreign.render(null, name, body, viewer);
        assertThat(passedName.get()).isSameAs(name);
        assertThat(untouched).isEqualTo(Component.text("world > ", NamedTextColor.BLUE).append(name)
                .append(Component.text(" :: ")).append(body));
    }

    @Test
    void beforeLinePrefixOffersSummaryWhenForeignRendererIgnoresDisplayName() {
        Component prefix = Component.text("[tier]", NamedTextColor.GREEN);
        Component body = Component.text("shared body");
        var snapshot = configManager.snapshot();
        Component hover = chatListener.buildHoverComponent(snapshot,
                profileService.getViewCached(null, snapshot), Tier.PARTICULAR);
        ChatRenderer foreign = (source, ignoredName, message, viewer) -> Component.text("fixed name: ").append(message);
        var config = new com.dasannn.socialblueprint.config.ForeignRendererConfig("wrap", "before-line");
        Component rendered = chatListener.createForeignRenderer(foreign, prefix, hover, body, config)
                .render(null, Component.text("Speaker"), Component.empty(), null);
        assertThat(rendered.children().getFirst()).isEqualTo(prefix.hoverEvent(HoverEvent.showText(hover)));
        assertThat(rendered.children().getLast()).isEqualTo(Component.text("fixed name: ").append(body));
        Component withoutPrefix = chatListener.createForeignRenderer(foreign, Component.empty(), hover, body, config)
                .render(null, Component.text("Speaker"), Component.empty(), null);
        assertThat(withoutPrefix).isEqualTo(Component.text("fixed name: ").append(body));
    }

    @Test
    void highestListenerWrapsForeignRendererOnceAndKeepsWordFilteringInBothModes() throws Exception {
        var annotation = AsyncChatListener.class.getMethod("onChat", AsyncChatEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);
        assertThat(annotation.priority()).isEqualTo(org.bukkit.event.EventPriority.HIGHEST);
        var preparation = AsyncChatListener.class.getMethod("onPrepareChat", AsyncChatEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);
        assertThat(preparation.priority()).isEqualTo(org.bukkit.event.EventPriority.LOWEST);
        try (var in = getClass().getClassLoader().getResourceAsStream("plugin.yml");
             var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
            var manifest = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
            assertThat(manifest.getStringList("softdepend")).contains("EssentialsChat");
            assertThat(manifest.getStringList("loadbefore")).contains("LDActivities");
        }
        var initialSnapshot = configManager.snapshot();
        Component hover = chatListener.buildHoverComponent(initialSnapshot,
                profileService.getViewCached(null, initialSnapshot), Tier.PARTICULAR);
        cacheReads.set(0);
        Component prefix = ColorParser.parse(configManager.config().tiers().prefix(Tier.PARTICULAR));
        String input = "Please idiot bring wooden supplies";
        String filtered = configManager.config().chatFilter().apply(input,
                messageRegistry.getRaw(configManager.snapshot(), "chat-filter.replacement"));
        assertThat(filtered).isNotEqualTo(input);
        ChatRenderer foreign = (source, name, message, viewer) -> Component.text("<").append(name)
                .append(Component.text("> ")).append(message);
        for (String placement : java.util.List.of("before-line", "display-name", "none")) {
            configManager.set("chat.foreign-renderer.prefix", placement);
            AsyncChatEvent event = chatEvent(null, Component.text(input));
            event.renderer(foreign);
            chatListener.onPrepareChat(event);
            Component preparedBody = event.message();
            assertThat(preparedBody).isEqualTo(Component.text(filtered));
            assertThat(event.renderer()).isSameAs(foreign);
            chatListener.onChat(event);
            assertThat(event.message()).isSameAs(preparedBody);
            ChatRenderer wrapper = event.renderer();
            assertThat(wrapper).isNotSameAs(foreign);
            Component expectedPrefix = placement.equals("before-line") ? prefix.hoverEvent(HoverEvent.showText(hover)) : prefix;
            Component leading = Component.empty().append(expectedPrefix).append(Component.space());
            Component name = Component.text("Speaker").clickEvent(ClickEvent.suggestCommand("/msg Speaker "));
            Component hoveredName = name.hoverEvent(HoverEvent.showText(hover));
            Component expectedName = placement.equals("display-name") ? leading.append(hoveredName) : hoveredName;
            Component expected = Component.text("<").append(expectedName).append(Component.text("> "))
                    .append(Component.text(filtered));
            if (placement.equals("before-line")) expected = leading.append(expected);
            assertThat(wrapper.render(null, name, Component.empty(), null)).isEqualTo(expected);
            chatListener.onChat(event);
            assertThat(event.renderer()).isSameAs(wrapper);
            assertThat(wrapper.render(null, name, Component.text("changed input"), null)).isEqualTo(expected);
            assertThat(event.isCancelled()).isFalse();
        }
        configManager.set("chat.foreign-renderer.mode", "leave");
        AsyncChatEvent event = chatEvent(null, Component.text(input));
        event.renderer(foreign);
        chatListener.onPrepareChat(event);
        assertThat(event.message()).isEqualTo(Component.text(filtered));
        chatListener.onChat(event);
        assertThat(event.renderer()).isSameAs(foreign);
        assertThat(event.message()).isEqualTo(Component.text(filtered));
        chatListener = new AsyncChatListener(profileService, configManager, messageRegistry, null, null, null, true);
        AsyncChatEvent intact = chatEvent(null, Component.text("ordinary message"));
        intact.renderer(foreign);
        Component originalBody = intact.message();
        chatListener.onPrepareChat(intact);
        chatListener.onChat(intact);
        assertThat(intact.message()).isSameAs(originalBody);
        assertThat(intact.renderer()).isSameAs(foreign);
        assertThat(cacheReads.get()).isEqualTo(5); // Preparation also applies in leave mode.
    }

    @SuppressWarnings("deprecation")
    @Test
    void intactMessagesRetainModernFormattingAndLegacyIdentityInBothModes() throws Exception {
        for (String mode : java.util.List.of("wrap", "leave")) {
            configManager.set("chat.foreign-renderer.mode", mode);
            Player speaker = preparedSpeaker("world", true);
            // Sequence zero is an episode; sequence one must preserve even code-looking input.
            AsyncChatEvent episode = chatEvent(speaker, Component.text("diamantes wooden supplies"));
            chatListener.onPrepareChat(episode);
            assertThat(episode.message().color()).isNotNull();
            chatListener.onRenderedChat(episode);
            String episodeText = new String(AsyncChatListener.extractPlainText(episode.message()));
            AsyncPlayerChatEvent legacyEpisode = new AsyncPlayerChatEvent(true, speaker, episodeText, Collections.emptySet());
            chatListener.onPrepareLegacyChat(legacyEpisode);
            assertThat(legacyEpisode.getMessage()).isSameAs(episodeText);
            chatListener.onRenderedLegacyChat(legacyEpisode);
            String input = new String("ordinary &a message \u00a7b with links");
            Component original = Component.text(input, NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.openUrl("https://example.com"))
                    .append(Component.text(" formatted", NamedTextColor.GOLD));
            AsyncChatEvent modern = chatEvent(speaker, original);
            ChatRenderer foreign = (source, name, body, viewer) -> body;
            modern.renderer(foreign);
            chatListener.onPrepareChat(modern);
            assertThat(modern.message()).isSameAs(original);
            chatListener.onChat(modern);
            assertThat(modern.message()).isSameAs(original);
            if (mode.equals("leave")) assertThat(modern.renderer()).isSameAs(foreign);
            else assertThat(modern.renderer().render(speaker, Component.text("Speaker"), Component.empty(), null)
                    .children().getLast()).isSameAs(original);
            chatListener.onRenderedChat(modern);
            String legacyInput = new String(AsyncChatListener.extractPlainText(original));
            AsyncPlayerChatEvent legacy = new AsyncPlayerChatEvent(true, speaker, legacyInput, Collections.emptySet());
            chatListener.onPrepareLegacyChat(legacy);
            assertThat(legacy.getMessage()).isSameAs(legacyInput);
            chatListener.onRenderedLegacyChat(legacy);
            assertThat(messageSequence(speaker)).isEqualTo(2);
            assertThat(chatState("bridges")).isEmpty();
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    void neutralLegacyMessagesKeepIdentityAndStillFilterInBothModes() {
        for (String mode : java.util.List.of("wrap", "leave")) {
            configManager.set("chat.foreign-renderer.mode", mode);
            String original = new String("ordinary &a message \u00a7b");
            Component component = Component.text(original, NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.openUrl("https://example.com"));
            AsyncChatEvent modern = chatEvent(null, component);
            modern.renderer((source, name, body, viewer) -> body);
            chatListener.onPrepareChat(modern);
            chatListener.onChat(modern);
            assertThat(modern.message()).isSameAs(component);
            chatListener.onRenderedChat(modern);
            AsyncPlayerChatEvent intact = new AsyncPlayerChatEvent(true, null, original, Collections.emptySet());
            chatListener.onPrepareLegacyChat(intact);
            assertThat(intact.getMessage()).isSameAs(original);
            chatListener.onRenderedLegacyChat(intact);
            String input = "Please idiot bring wooden supplies";
            String filtered = configManager.config().chatFilter().apply(input,
                    messageRegistry.getRaw(configManager.snapshot(), "chat-filter.replacement"));
            assertThat(filtered).isNotEqualTo(input);
            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, null, input, Collections.emptySet());
            chatListener.onPrepareLegacyChat(event);
            assertThat(event.getMessage()).isEqualTo(filtered);
            chatListener.onRenderedLegacyChat(event);
        }
    }

    @Test
    void foreignRendererBeforeOrAfterOurListenerIsPreservedAndLoggedOnce() {
        configManager.set("chat.foreign-renderer.mode", "leave");
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
    void disabledWorldKeepsPrefixAndLeavesChatBodyUnfiltered() {
        UUID uuid = UUID.randomUUID();
        org.bukkit.World world = (org.bukkit.World) Proxy.newProxyInstance(
                org.bukkit.World.class.getClassLoader(), new Class<?>[]{org.bukkit.World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "minigames";
                    throw new AssertionError("Unexpected World call");
                });
        Player speaker = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getUniqueId" -> uuid;
                    default -> throw new AssertionError("Unexpected Player call");
                });
        chatListener.registerPlayer(speaker);
        String original = "Please idiot bring wooden supplies to the village before sunset";
        AsyncChatEvent event = chatEvent(speaker, Component.text(original));
        chatListener.onChat(event);
        Component rendered = event.renderer().render(speaker, Component.text("Speaker"), event.message(), speaker);
        assertThat(event.isCancelled()).isFalse();
        assertThat(event.message()).isEqualTo(Component.text(original));
        assertThat(rendered.children().getLast()).isEqualTo(Component.text(original));
        var snapshot = configManager.snapshot();
        assertThat(rendered.children().getFirst()).isEqualTo(ColorParser.parse(snapshot.config().tiers().prefix(Tier.PARTICULAR)));
    }

    @Test
    void asyncMessageIsComputedOnceForSeveralViewersWithoutPlayerOrStorageCalls() throws Exception {
        sharedBodyAcrossViewers(false);
    }

    @Test
    void foreignRendererSharesCorruptionAndEpisodeColourWithoutPlayerOrStorageCalls() throws Exception {
        sharedBodyAcrossViewers(true);
    }

    @Test
    void capturingRendererReadsCorruptedEventBodyBeforeHighestAndReusesOneDecision() throws Exception {
        sharedBodyAcrossViewers(true, true);
    }

    @Test
    void disabledWorldSkipsFilterCorruptionAndColourInDefaultPath() throws Exception {
        sharedBodyAcrossViewers(false, false, true);
    }

    @Test
    void disabledWorldSkipsFilterCorruptionAndColourInForeignPath() throws Exception {
        sharedBodyAcrossViewers(true, false, true);
    }

    @Test
    void disabledWorldPreparesPlainBodyForCapturingForeignRenderer() throws Exception {
        sharedBodyAcrossViewers(true, true, true);
    }

    private void sharedBodyAcrossViewers(boolean foreign) throws Exception {
        sharedBodyAcrossViewers(foreign, false);
    }

    private void sharedBodyAcrossViewers(boolean foreign, boolean capturing) throws Exception {
        sharedBodyAcrossViewers(foreign, capturing, false);
    }

    private void sharedBodyAcrossViewers(boolean foreign, boolean capturing, boolean disabled) throws Exception {
        if (capturing) chatListener = new AsyncChatListener(profileService, configManager, messageRegistry,
                null, null, null, true);
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
                    if (method.getName().equals("getWorld")) return Proxy.newProxyInstance(
                            org.bukkit.World.class.getClassLoader(), new Class<?>[]{org.bukkit.World.class},
                            (world, call, values) -> {
                                if (Thread.currentThread() != setupThread) throw new AssertionError("Off-thread World call");
                                if (call.getName().equals("getName")) return disabled ? "minigames" : "world";
                                throw new AssertionError("Unexpected World call: " + call.getName());
                            });
                    if (method.getName().equals("getUniqueId")) return uuid;
                    throw new AssertionError("Unexpected Player call: " + method.getName());
                });
        chatListener.registerPlayer(speaker);
        storage.close(); // Any database access or executor submission now fails.
        String unfiltered = "Please IMBÉCIL bring idiot wooden supplies to the village before sunset";
        String original = disabled ? unfiltered : snapshot.config().chatFilter().apply(unfiltered,
                messageRegistry.getRaw(snapshot, "chat-filter.replacement"));
        if (!disabled) assertThat(original).contains("bobba").doesNotContain("IMBÉCIL", "idiot");
        Component prefix = ColorParser.parse(snapshot.config().tiers().prefix(view.tier()));
        AtomicBoolean changed = new AtomicBoolean();
        Thread async = new Thread(() -> {
            try {
                for (int sequence = 0; sequence < 100; sequence++) {
                    AsyncChatEvent event = chatEvent(speaker, Component.text(unfiltered));
                    if (foreign && !capturing) event.renderer((source, name, message, viewer) ->
                            Component.empty().append(name).append(Component.text(" :: ")).append(message));
                    chatListener.onPrepareChat(event);
                    Component preparedBody = event.message();
                    chatListener.onPrepareChat(event); // Preparing twice must not reroll either.
                    assertThat(event.message()).isSameAs(preparedBody);
                    if (capturing) {
                        // Simulate EssentialsChat at HIGHEST: capture the event body and ignore render's message argument.
                        Component captured = event.message();
                        event.renderer((source, name, ignored, viewer) ->
                                Component.empty().append(name).append(Component.text(" :: ")).append(captured));
                    }
                    chatListener.onChat(event);
                    if (foreign) {
                        assertThat(event.message()).isSameAs(preparedBody);
                        chatListener.onChat(event); // Must not reroll or advance the speaker sequence.
                    }
                    PsychosisLevel effectiveLevel = disabled ? PsychosisLevel.NEUTRAL : view.psychosis();
                    String expectedText = ChatCorruption.corrupt(original, effectiveLevel,
                            uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits(), sequence,
                            snapshot.config().psychosis().chat());
                    boolean episode = ChatCorruption.isEpisode(original, effectiveLevel,
                            uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits(), sequence,
                            snapshot.config().psychosis().chat());
                    Component expectedBody = Component.text(expectedText);
                    if (episode) expectedBody = expectedBody.color(TextColor.fromHexString("#303030"));
                    assertThat(preparedBody).isEqualTo(expectedBody);
                    assertThat(event.message()).isEqualTo(expectedBody);
                    if (!expectedText.equals(original)) changed.set(true);
                    Component first = null;
                    for (int reader = 0; reader < 4; reader++) {
                        Audience viewer = reader == 0 ? speaker : (Audience) Proxy.newProxyInstance(Audience.class.getClassLoader(),
                                new Class<?>[]{Audience.class}, (proxy, method, args) -> {
                                    throw new AssertionError("Renderer touched viewer");
                                });
                        Component rendered = event.renderer().render(speaker, Component.text("Speaker"),
                                Component.text("different renderer input " + reader), viewer);
                        if (foreign) {
                            Component hover = chatListener.buildHoverComponent(snapshot, view, view.tier());
                            Component hoveredName = chatListener.createRenderer(Component.empty(), hover, expectedBody)
                                    .render(null, Component.text("Speaker"), Component.empty(), null).children().getFirst();
                            Component foreignLine = Component.empty().append(hoveredName)
                                    .append(Component.text(" :: ")).append(expectedBody);
                            assertThat(rendered).isEqualTo(Component.empty().append(prefix.hoverEvent(HoverEvent.showText(hover)))
                                    .append(Component.space()).append(foreignLine));
                            assertThat(rendered.children().getLast().children().getLast()).isSameAs(preparedBody);
                        } else {
                            assertThat(rendered.children().getLast()).isEqualTo(expectedBody);
                            assertThat(rendered).isEqualTo(Component.empty().append(prefix).append(Component.space())
                                    .append(Component.text("Speaker").hoverEvent(HoverEvent.showText(
                                            chatListener.buildHoverComponent(snapshot, view, view.tier()))))
                                    .append(Component.text(": ")).append(expectedBody));
                        }
                        if (first == null) first = rendered;
                        else assertThat(rendered).isEqualTo(first);
                    }
                    assertThat(event.isCancelled()).isFalse();
                    assertThat(cacheReads.get()).isEqualTo(sequence + 1);
                    chatListener.onRenderedChat(event);
                }
                assertThat(profileService.getViewCached(PlayerId.of(UUID.randomUUID()), snapshot).psychosis())
                        .isEqualTo(PsychosisLevel.NEUTRAL); // cold read also submits no storage work
            } catch (Throwable ex) { failure.set(ex); }
        }, "AsyncChatThread");
        async.start();
        async.join();
        assertThat(failure.get()).isNull();
        assertThat(changed.get()).isEqualTo(!disabled);
        var identitiesField = AsyncChatListener.class.getDeclaredField("identities");
        identitiesField.setAccessible(true);
        Object identity = ((java.util.Map<?, ?>) identitiesField.get(chatListener)).get(speaker);
        var sequenceMethod = identity.getClass().getDeclaredMethod("sequence");
        sequenceMethod.setAccessible(true);
        assertThat(((java.util.concurrent.atomic.AtomicLong) sequenceMethod.invoke(identity)).get()).isEqualTo(100);
    }

    private static class MinigameReader implements org.bukkit.event.Listener {
        String modern;
        String legacy;

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.NORMAL)
        public void modern(AsyncChatEvent event) {
            modern = AsyncChatListener.extractPlainText(event.message());
        }

        @SuppressWarnings("deprecation")
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.NORMAL)
        public void legacy(AsyncPlayerChatEvent event) { legacy = event.getMessage(); }
    }

    private Player preparedSpeaker(String worldName, boolean psychotic) {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000123");
        PlayerId id = PlayerId.of(uuid);
        if (psychotic) {
            configManager.set("psychosis.chat.extreme-rate", "50");
            for (int i = 0; i < configManager.snapshot().config().psychosis().extremeThreshold(); i++) {
                psychosisRepo.saveAsync(new PsychosisEvent(id, PlayerId.of(UUID.randomUUID()),
                        CombatContext.OPEN, Instant.now())).join();
            }
        }
        PlayerSocialView view = profileService.loadViewAsync(id, "Speaker", configManager.snapshot()).join();
        assertThat(view.psychosis()).isEqualTo(psychotic ? PsychosisLevel.EXTREME : PsychosisLevel.NEUTRAL);
        org.bukkit.World world = (org.bukkit.World) Proxy.newProxyInstance(
                org.bukkit.World.class.getClassLoader(), new Class<?>[]{org.bukkit.World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return worldName;
                    throw new AssertionError("Unexpected World call: " + method.getName());
                });
        Player speaker = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getWorld" -> world;
                    default -> throw new AssertionError("Unexpected Player call: " + method.getName());
                });
        chatListener.registerPlayer(speaker);
        cacheReads.set(0);
        return speaker;
    }

    private long messageSequence(Player speaker) throws Exception {
        var field = AsyncChatListener.class.getDeclaredField("identities");
        field.setAccessible(true);
        Object identity = ((java.util.Map<?, ?>) field.get(chatListener)).get(speaker);
        var sequence = identity.getClass().getDeclaredMethod("sequence");
        sequence.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicLong) sequence.invoke(identity)).get();
    }

    private java.util.Map<?, ?> chatState(String name) throws Exception {
        var field = AsyncChatListener.class.getDeclaredField(name);
        field.setAccessible(true);
        return (java.util.Map<?, ?>) field.get(chatListener);
    }

    @Test
    void lowestModernMessageIsFilteredThenCorruptedBeforeNormalMinigameRead() throws Exception {
        Player speaker = preparedSpeaker("world", true);
        String input = "Please idiot bring diamantes wooden supplies";
        String filtered = configManager.config().chatFilter().apply(input,
                messageRegistry.getRaw(configManager.snapshot(), "chat-filter.replacement"));
        Component expected = AsyncChatListener.messageBody(filtered, PsychosisLevel.EXTREME, 0x123, 0,
                configManager.config().psychosis().chat());
        assertThat(AsyncChatListener.extractPlainText(expected)).isNotEqualTo(filtered);
        AsyncChatEvent event = chatEvent(speaker, Component.text(input));
        chatListener.onPrepareChat(event);
        assertThat(event.message()).isEqualTo(expected);
        assertThat(event.message().color()).isNotNull();
        MinigameReader minigame = new MinigameReader();
        minigame.modern(event);
        assertThat(minigame.modern).isEqualTo(AsyncChatListener.extractPlainText(expected)).isNotEqualTo(input);
        assertThat(MinigameReader.class.getMethod("modern", AsyncChatEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class).priority())
                .isEqualTo(org.bukkit.event.EventPriority.NORMAL);
        chatListener.onChat(event);
        assertThat(event.renderer().render(speaker, Component.text("Speaker"), Component.text(input), null)
                .children().getLast()).isEqualTo(expected);
        assertThat(cacheReads.get()).isEqualTo(1);
        assertThat(messageSequence(speaker)).isEqualTo(1);
        chatListener.onRenderedChat(event);
        assertThat(chatState("prepared")).isEmpty();
    }

    @SuppressWarnings("deprecation")
    @Test
    void lowestLegacyMessageIsPlainCorruptionBeforeNormalMinigameRead() throws Exception {
        Player speaker = preparedSpeaker("world", true);
        String input = "Please idiot bring diamantes wooden supplies";
        String filtered = configManager.config().chatFilter().apply(input,
                messageRegistry.getRaw(configManager.snapshot(), "chat-filter.replacement"));
        String expected = ChatCorruption.corrupt(filtered, PsychosisLevel.EXTREME, 0x123, 0,
                configManager.config().psychosis().chat());
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, speaker, input, Collections.emptySet());
        chatListener.onPrepareLegacyChat(event);
        assertThat(event.getMessage()).isEqualTo(expected).isNotEqualTo(filtered).doesNotContain("§", "&");
        MinigameReader minigame = new MinigameReader();
        minigame.legacy(event);
        assertThat(minigame.legacy).isEqualTo(expected).isNotEqualTo(input);
        assertThat(AsyncChatListener.class.getMethod("onPrepareLegacyChat", AsyncPlayerChatEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class).priority())
                .isEqualTo(org.bukkit.event.EventPriority.LOWEST);
        assertThat(MinigameReader.class.getMethod("legacy", AsyncPlayerChatEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class).priority())
                .isEqualTo(org.bukkit.event.EventPriority.NORMAL);
        chatListener.onPrepareLegacyChat(event);
        assertThat(cacheReads.get()).isEqualTo(1);
        assertThat(messageSequence(speaker)).isEqualTo(1);
        chatListener.onRenderedLegacyChat(event);
        assertThat(chatState("prepared")).isEmpty();
    }

    @Test
    void legacyThenModernShareDecisionAcrossMonitorAndRestoreCapturedColour() throws Exception {
        pairedMessages(false, "world", true);
    }

    @Test
    void modernThenLegacyShareDecisionAcrossMonitor() throws Exception {
        pairedMessages(true, "world", true);
    }

    @Test
    void disabledWorldLeavesBothEventMessagesUntouchedAtLowest() throws Exception {
        pairedMessages(false, "minigames", true);
    }

    @Test
    void neutralMessagesStayIntactForBothNormalReaders() throws Exception {
        pairedMessages(true, "world", false);
    }

    @SuppressWarnings("deprecation")
    private void pairedMessages(boolean modernFirst, String world, boolean psychotic) throws Exception {
        Player speaker = preparedSpeaker(world, psychotic);
        boolean disabled = world.equals("minigames");
        String input = disabled ? "Please idiot bring diamantes wooden supplies" : "diamantes wooden supplies";
        var snapshot = configManager.snapshot();
        MinigameReader minigame = new MinigameReader();
        for (int sequence = 0; sequence < 4; sequence++) {
            Component expected = AsyncChatListener.messageBody(input,
                    disabled || !psychotic ? PsychosisLevel.NEUTRAL : PsychosisLevel.EXTREME,
                    0x123, sequence, snapshot.config().psychosis().chat());
            AsyncChatEvent modern = chatEvent(speaker, Component.text(input));
            AsyncPlayerChatEvent legacy = new AsyncPlayerChatEvent(true, speaker, input, Collections.emptySet());
            if (modernFirst) {
                chatListener.onPrepareChat(modern);
                minigame.modern(modern);
                chatListener.onChat(modern);
                chatListener.onRenderedChat(modern);
                assertThat(chatState("prepared")).isEmpty();
                legacy.setMessage(minigame.modern); // Bridge forwards the modified mutable message.
                chatListener.onPrepareLegacyChat(legacy);
                minigame.legacy(legacy);
                chatListener.onRenderedLegacyChat(legacy);
            } else {
                chatListener.onPrepareLegacyChat(legacy);
                minigame.legacy(legacy);
                chatListener.onRenderedLegacyChat(legacy);
                assertThat(chatState("prepared")).isEmpty();
                modern.message(Component.text(minigame.legacy)); // Original remains input, as in Paper.
                chatListener.onPrepareChat(modern);
                minigame.modern(modern);
                Component captured = modern.message();
                modern.renderer((source, name, ignored, viewer) -> Component.text("<")
                        .append(name).append(Component.text("> ")).append(captured));
                chatListener.onChat(modern);
                chatListener.onRenderedChat(modern);
            }
            assertThat(minigame.modern).isEqualTo(minigame.legacy)
                    .isEqualTo(AsyncChatListener.extractPlainText(expected));
            assertThat(modern.message()).isEqualTo(expected);
            if (psychotic && !disabled && sequence % 2 == 0) {
                assertThat(minigame.modern).isNotEqualTo(input);
                assertThat(modern.message().color()).isNotNull();
            } else {
                assertThat(minigame.modern).isEqualTo(input);
                assertThat(modern.message().color()).isNull();
            }
            Component line = modern.renderer().render(speaker, Component.text("Speaker"), Component.text("ignored"), null);
            Component renderedBody = modernFirst ? line.children().getLast()
                    : line.children().getLast().children().getLast();
            assertThat(renderedBody).isEqualTo(expected);
            assertThat(cacheReads.get()).isEqualTo(sequence + 1);
            assertThat(messageSequence(speaker)).isEqualTo(sequence + 1);
            assertThat(chatState("prepared")).isEmpty();
            assertThat(chatState("bridges")).isEmpty();
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    void cancelledLegacyContextAndHandoffAreClearedAtMonitor() throws Exception {
        Player speaker = preparedSpeaker("world", true);
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, speaker, "diamantes", Collections.emptySet());
        chatListener.onPrepareLegacyChat(event);
        event.setCancelled(true);
        chatListener.onRenderedLegacyChat(event);
        assertThat(chatState("prepared")).isEmpty();
        assertThat(chatState("bridges")).isEmpty();
        var monitor = AsyncChatListener.class.getMethod("onRenderedLegacyChat", AsyncPlayerChatEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);
        assertThat(monitor.priority()).isEqualTo(org.bukkit.event.EventPriority.MONITOR);
        assertThat(monitor.ignoreCancelled()).isFalse();
    }

    @Test
    void leaveModeStillPublishesEpisodeBodyAtLowest() throws Exception {
        configManager.set("chat.foreign-renderer.mode", "leave");
        Player speaker = preparedSpeaker("world", true);
        Component expected = AsyncChatListener.messageBody("diamantes wooden supplies",
                PsychosisLevel.EXTREME, 0x123, 0, configManager.config().psychosis().chat());
        AsyncChatEvent event = chatEvent(speaker, Component.text("diamantes wooden supplies"));
        ChatRenderer foreign = (source, name, body, viewer) -> body;
        event.renderer(foreign);
        chatListener.onPrepareChat(event);
        Component body = event.message();
        assertThat(body).isEqualTo(expected);
        assertThat(AsyncChatListener.extractPlainText(body)).isNotEqualTo("diamantes wooden supplies");
        assertThat(body.color()).isNotNull();
        chatListener.onChat(event);
        assertThat(event.renderer()).isSameAs(foreign);
        assertThat(event.message()).isSameAs(body);
        assertThat(cacheReads.get()).isEqualTo(1);
        assertThat(messageSequence(speaker)).isEqualTo(1);
    }

    @Test
    void unmatchedHandoffExpiresBeforeAnotherMessageCanReuseIt() throws Exception {
        Player speaker = preparedSpeaker("world", true);
        AsyncChatEvent first = chatEvent(speaker, Component.text("diamantes wooden supplies"));
        chatListener.onPrepareChat(first);
        chatListener.onRenderedChat(first);
        assertThat(chatState("prepared")).isEmpty();
        assertThat(chatState("bridges")).hasSize(1);
        // Advance cleanup's monotonic deadline without sleeping or changing the clock.
        var prune = AsyncChatListener.class.getDeclaredMethod("pruneBridges", long.class);
        prune.setAccessible(true);
        prune.invoke(chatListener, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6));
        assertThat(chatState("bridges")).isEmpty();
        AsyncPlayerChatEvent next = new AsyncPlayerChatEvent(true, speaker,
                "diamantes wooden supplies", Collections.emptySet());
        chatListener.onPrepareLegacyChat(next);
        assertThat(next.getMessage()).isEqualTo("diamantes wooden supplies"); // Protected sequence 1.
        assertThat(cacheReads.get()).isEqualTo(2);
        assertThat(messageSequence(speaker)).isEqualTo(2);
        chatListener.onRenderedLegacyChat(next);
        chatListener.onQuit(new org.bukkit.event.player.PlayerQuitEvent(speaker, Component.empty()));
        assertThat(chatState("bridges")).isEmpty();
        assertThat(chatState("identities")).isEmpty();
    }

    @Test
    void modernCancellationClearsContextAndHandoffAtMonitor() throws Exception {
        Player speaker = preparedSpeaker("world", true);
        AsyncChatEvent event = chatEvent(speaker, Component.text("diamantes wooden supplies"));
        chatListener.onPrepareChat(event);
        event.setCancelled(true);
        chatListener.onRenderedChat(event);
        assertThat(chatState("prepared")).isEmpty();
        assertThat(chatState("bridges")).isEmpty();
    }

    private static AsyncChatEvent chatEvent(Player player, Component message) {
        return new AsyncChatEvent(true, player, Collections.emptySet(), ChatRenderer.defaultRenderer(),
                message, message, SignedMessage.system(AsyncChatListener.extractPlainText(message), message));
    }

    @Test
    void preparationSnapshotAndBodySurviveReloadBetweenPriorities() {
        AsyncChatEvent event = chatEvent(null, Component.text("ordinary message"));
        ChatRenderer foreign = (source, name, body, viewer) -> body;
        event.renderer(foreign);
        var snapshot = configManager.snapshot();
        Component hover = chatListener.buildHoverComponent(snapshot,
                profileService.getViewCached(null, snapshot), Tier.PARTICULAR);
        Component prefix = ColorParser.parse(snapshot.config().tiers().prefix(Tier.PARTICULAR))
                .hoverEvent(HoverEvent.showText(hover));
        cacheReads.set(0); // Exclude the expected-hover fixture lookup.
        chatListener.onPrepareChat(event);
        Component body = event.message();
        configManager.set("chat.foreign-renderer.mode", "leave");
        chatListener.onChat(event);
        assertThat(event.renderer()).isNotSameAs(foreign);
        assertThat(event.renderer().render(null, Component.text("Speaker"), Component.empty(), null))
                .isEqualTo(Component.empty().append(prefix).append(Component.space()).append(body));
        assertThat(cacheReads.get()).isEqualTo(1);
    }

    @Test
    void preparedContextIsClearedAtMonitorEvenWhenCancelled() throws Exception {
        var field = AsyncChatListener.class.getDeclaredField("prepared");
        field.setAccessible(true);
        var contexts = (java.util.Map<?, ?>) field.get(chatListener);
        for (boolean cancelled : java.util.List.of(false, true)) {
            AsyncChatEvent event = chatEvent(null, Component.text("ordinary message"));
            event.renderer((source, name, body, viewer) -> body);
            chatListener.onPrepareChat(event);
            assertThat(contexts.size()).isEqualTo(1);
            if (cancelled) event.setCancelled(true);
            else {
                chatListener.onChat(event);
                assertThat(contexts.size()).isEqualTo(1);
            }
            chatListener.onRenderedChat(event);
            assertThat(contexts).isEmpty();
        }
        var monitor = AsyncChatListener.class.getMethod("onRenderedChat", AsyncChatEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);
        assertThat(monitor.ignoreCancelled()).isFalse();
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
