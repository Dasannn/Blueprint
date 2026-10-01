package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.EffectsConfigSection;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.PluginConfig;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.config.SingleEffectConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class AmbientPrivacyTest {

    @TempDir
    File tempDir;

    private RecordingMessageRegistry messageRegistry;
    private ConfigManager configManager;
    private RuntimeSnapshot snapshot;
    private EffectsConfigSection effectsConfig;

    private MockPlayer targetPlayer;
    private MockPlayer observingPlayer;

    public record RenderCall(String key, Map<String, String> placeholders, boolean withPrefix) {}

    public static class RecordingMessageRegistry extends MessageRegistry {
        private final List<RenderCall> renderedCalls = new CopyOnWriteArrayList<>();

        public RecordingMessageRegistry(File dataFolder, String language, Logger logger) {
            super(dataFolder, language, logger);
        }

        public void clearCalls() {
            renderedCalls.clear();
        }

        public boolean hasCall(String key) {
            return renderedCalls.stream().anyMatch(c -> c.key().equals(key));
        }

        public Optional<RenderCall> findCall(String key) {
            return renderedCalls.stream().filter(c -> c.key().equals(key)).findFirst();
        }

        @Override
        public Component render(RuntimeSnapshot snapshot, String key, Map<String, String> placeholders) {
            renderedCalls.add(new RenderCall(key, Map.copyOf(placeholders), false));
            return super.render(snapshot, key, placeholders);
        }

        @Override
        public Component render(String key) {
            renderedCalls.add(new RenderCall(key, Collections.emptyMap(), false));
            return super.render(key);
        }

        @Override
        public Component render(String key, Map<String, String> placeholders) {
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), false));
            return super.render(key, placeholders);
        }
    }

    private static class MockPlayer {
        final UUID uuid;
        final String name;
        final List<Component> receivedMessages = new ArrayList<>();
        final List<PlayedSoundRecord> playedSounds = new ArrayList<>();
        final List<Entity> shownEntities = new ArrayList<>();
        final List<Entity> hiddenEntities = new ArrayList<>();
        final Player proxy;

        record PlayedSoundRecord(Location loc, String sound, SoundCategory category, float volume, float pitch) {}

        MockPlayer(UUID uuid, String name, World mockWorld) {
            this.uuid = uuid;
            this.name = name;
            Location loc = new Location(mockWorld, 100, 64, 100);

            InvocationHandler handler = (p, method, args) -> {
                String mName = method.getName();
                if ("equals".equals(mName) && method.getParameterCount() == 1) return p == args[0];
                if ("hashCode".equals(mName) && method.getParameterCount() == 0) return System.identityHashCode(p);
                if ("toString".equals(mName) && method.getParameterCount() == 0) return name;
                if ("getUniqueId".equals(mName)) return uuid;
                if ("getName".equals(mName)) return name;
                if ("isOnline".equals(mName)) return true;
                if ("getLocation".equals(mName)) return loc;
                if ("getWorld".equals(mName)) return mockWorld;
                if ("sendMessage".equals(mName)) {
                    if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                        receivedMessages.add(comp);
                    }
                    return null;
                }
                if ("playSound".equals(mName)) {
                    if (args != null && args.length >= 5) {
                        playedSounds.add(new PlayedSoundRecord(
                                (Location) args[0],
                                String.valueOf(args[1]),
                                (SoundCategory) args[2],
                                ((Number) args[3]).floatValue(),
                                ((Number) args[4]).floatValue()
                        ));
                    }
                    return null;
                }
                if ("showEntity".equals(mName)) {
                    if (args != null && args.length >= 2 && args[1] instanceof Entity ent) {
                        shownEntities.add(ent);
                    }
                    return null;
                }
                if ("hideEntity".equals(mName)) {
                    if (args != null && args.length >= 2 && args[1] instanceof Entity ent) {
                        hiddenEntities.add(ent);
                    }
                    return null;
                }
                return defaultValue(method.getReturnType());
            };

            this.proxy = (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(),
                    new Class<?>[]{Player.class},
                    handler
            );
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        copyResource("messages_en.yml", new File(tempDir, "messages_en.yml"));
        copyResource("messages_es.yml", new File(tempDir, "messages_es.yml"));

        Logger logger = Logger.getLogger("AmbientPrivacyTest-" + System.nanoTime());
        messageRegistry = new RecordingMessageRegistry(tempDir, "en", logger);

        SingleEffectConfig silverfish = new SingleEffectConfig(Duration.ofSeconds(60), 2);
        SingleEffectConfig whisper = new SingleEffectConfig(Duration.ofSeconds(30), 3);
        SingleEffectConfig creeper = new SingleEffectConfig(Duration.ofSeconds(120), 1);
        SingleEffectConfig fakeAnnounce = new SingleEffectConfig(Duration.ofSeconds(300), 1);

        effectsConfig = new EffectsConfigSection(
                Duration.ofSeconds(30),
                silverfish,
                whisper,
                creeper,
                fakeAnnounce
        );

        File configFile = new File(tempDir, "config.yml");
        copyResource("config.yml", configFile);

        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        PluginConfig fullConfig = configManager.config().withEffects(effectsConfig);
        configManager.snapshotReference().set(new RuntimeSnapshot(fullConfig, configManager.snapshot().messages()));
        snapshot = configManager.snapshot();

        World mockWorld = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (p, method, args) -> {
                    String mName = method.getName();
                    if ("equals".equals(mName) && method.getParameterCount() == 1) return p == args[0];
                    if ("hashCode".equals(mName) && method.getParameterCount() == 0) return System.identityHashCode(p);
                    if ("toString".equals(mName) && method.getParameterCount() == 0) return "MockWorld";
                    if ("getUID".equals(mName)) return UUID.randomUUID();
                    if ("getName".equals(mName)) return "world";
                    return defaultValue(method.getReturnType());
                }
        );

        targetPlayer = new MockPlayer(UUID.randomUUID(), "TargetPlayer", mockWorld);
        observingPlayer = new MockPlayer(UUID.randomUUID(), "ObservingPlayer", mockWorld);
    }

    private void copyResource(String resourceName, File destination) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(in).isNotNull();
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    @DisplayName("DoD 1 / T-071 / SB-041: Whisper effect reaches only the affected player, observing player receives nothing")
    void whisperReachesOnlyTargetPlayer() {
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, null);
        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService
        );

        messageRegistry.clearCalls();
        dispatcher.dispatch(targetPlayer.proxy, AmbientEffectType.WHISPER, effectsConfig, snapshot);

        // Affected player receives the whisper message. The expected text comes
        // from the same snapshot the dispatcher was handed, not from a literal:
        // the whispers are translated, and a pinned English string would only
        // pass while the fixture happened to be English.
        assertThat(targetPlayer.receivedMessages).hasSize(1);
        String msg = ColorParser.serialize(targetPlayer.receivedMessages.get(0));
        java.util.List<String> expectedWhispers = java.util.stream.IntStream.rangeClosed(1, 3)
                .mapToObj(i -> snapshot.messages().resolveRaw("effects.whisper-" + i, new java.util.HashSet<>(), null))
                .map(raw -> ColorParser.serialize(ColorParser.parse(raw)))
                .toList();
        assertThat(msg).isIn(expectedWhispers);

        // Observing player receives NOTHING
        assertThat(observingPlayer.receivedMessages).isEmpty();
        assertThat(observingPlayer.playedSounds).isEmpty();
        assertThat(observingPlayer.shownEntities).isEmpty();
    }

    @Test
    @DisplayName("T-100 Preflight Finding 5: Whisper renders strictly from the snapshot handed to dispatch, not construction-time registry")
    void whisperRendersFromDispatchSnapshot() {
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, null);
        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService
        );

        Map<String, String> spanishMessages = Map.of(
                "effects.whisper-1", "&#202020... (es)",
                "effects.whisper-2", "&#202020¿Escuchaste eso?",
                "effects.whisper-3", "&#202020Mira detrás de ti."
        );
        com.dasannn.socialblueprint.config.MessagesSnapshot esMessagesSnapshot = new com.dasannn.socialblueprint.config.MessagesSnapshot(
                "es", "en", spanishMessages, Map.of(), Map.of(), Map.of()
        );
        RuntimeSnapshot spanishSnapshot = new RuntimeSnapshot(snapshot.config(), esMessagesSnapshot);

        targetPlayer.receivedMessages.clear();
        dispatcher.dispatch(targetPlayer.proxy, AmbientEffectType.WHISPER, effectsConfig, spanishSnapshot);

        assertThat(targetPlayer.receivedMessages).hasSize(1);
        String received = ColorParser.serialize(targetPlayer.receivedMessages.get(0));
        assertThat(
                received.contains("(es)") ||
                received.contains("¿Escuchaste eso?") ||
                received.contains("Mira detrás de ti.")
        ).isTrue();

        assertThat(received).doesNotContain("Did you hear that?");
        assertThat(received).doesNotContain("Look behind you.");
    }

    @Test
    @DisplayName("DoD 1 / T-071 / SB-041: Creeper sound plays privately to affected player, observing player receives nothing")
    void creeperSoundReachesOnlyTargetPlayer() {
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, null);
        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService
        );

        dispatcher.dispatch(targetPlayer.proxy, AmbientEffectType.CREEPER_SOUND, effectsConfig, snapshot);

        // Affected player receives private playSound
        assertThat(targetPlayer.playedSounds).hasSize(1);
        MockPlayer.PlayedSoundRecord sound = targetPlayer.playedSounds.get(0);
        assertThat(sound.sound()).isEqualTo("entity.creeper.primed");
        assertThat(sound.category()).isEqualTo(SoundCategory.HOSTILE);

        // Observing player receives NO sound
        assertThat(observingPlayer.playedSounds).isEmpty();
        assertThat(observingPlayer.receivedMessages).isEmpty();
        assertThat(observingPlayer.shownEntities).isEmpty();
    }

    @Test
    @DisplayName("DoD 1 / T-071 / T-074 / SB-041: Fake announcement sends private message to affected player only with substitution")
    void fakeAnnouncementReachesOnlyTargetPlayer() {
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        FakeSilverfishService silverfishService = new FakeSilverfishService(null, registry, null);
        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(
                null, messageRegistry, configManager, silverfishService
        );

        messageRegistry.clearCalls();
        dispatcher.dispatch(targetPlayer.proxy, AmbientEffectType.FAKE_ANNOUNCEMENT, effectsConfig, snapshot);

        assertThat(targetPlayer.receivedMessages).hasSize(1);
        assertThat(messageRegistry.renderedCalls).singleElement().satisfies(call -> {
            assertThat(call.key()).isIn("effects.fake-connection.join", "effects.fake-connection.leave");
            assertThat(call.placeholders()).containsExactlyEntriesOf(Map.of("player", targetPlayer.name));
        });

        // Observing player receives NOTHING
        assertThat(observingPlayer.receivedMessages).isEmpty();
        assertThat(observingPlayer.playedSounds).isEmpty();
        assertThat(observingPlayer.shownEntities).isEmpty();
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        if (returnType == float.class) return 0.0f;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == char.class) return '\0';
        return null;
    }
}
