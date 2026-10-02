package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.PsychosisLevel;
import com.dasannn.socialblueprint.feature.effects.AmbientEffectType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PresentationConfigTest {
    @TempDir Path folder;

    private YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8));
    }

    @Test void particleBudgetsRejectOversizedLoadsAndLiveEdits() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        for (String key : List.of("effects.particles.count", "effects.serenity.particles.count")) {
            for (int invalid : List.of(PresentationConfig.MAX_PARTICLE_COUNT + 1, Integer.MAX_VALUE)) {
                YamlConfiguration yaml = shipped();
                yaml.set(key, invalid);
                assertThatThrownBy(() -> PluginConfig.load(yaml)).hasMessageContaining(key);
                RuntimeSnapshot before = manager.snapshot();
                String disk = Files.readString(folder.resolve("config.yml"));
                assertThatThrownBy(() -> manager.set(key, Integer.toString(invalid))).hasMessageContaining(key);
                assertThat(manager.snapshot()).isSameAs(before);
                assertThat(Files.readString(folder.resolve("config.yml"))).isEqualTo(disk);
            }
            manager.set(key, Integer.toString(PresentationConfig.MAX_PARTICLE_COUNT));
        }
        PresentationConfig.Particles particles = manager.snapshot().config().effects().serenity().particles();
        assertThat(particles.fitsAudience(8)).isTrue();
        assertThat(particles.fitsAudience(9)).isFalse();
        assertThat(particles.fitsAudience(Integer.MAX_VALUE)).isFalse();
        assertThat(particles.fitsAudience(0)).isFalse();
    }

    @Test void invalidPresentationValuesNameTheirPath() {
        Map<String, Object> invalid = Map.ofEntries(
                Map.entry("episodes.duration-scale", "bad"), Map.entry("episodes.duration-scale.unknown", 1),
                Map.entry("sky.mode", "day"), Map.entry("sky.duration-ticks", 201),
                Map.entry("sky.cooldown-ticks", 0), Map.entry("sky.session-cap", -1),
                Map.entry("sky.enabled", "yes"), Map.entry("sky.unknown", 1),
                Map.entry("particles.count", 0), Map.entry("particles.radius-blocks", Double.NaN),
                Map.entry("particles.placement", "far"), Map.entry("particles.type", "unknown"),
                Map.entry("screen-flash.channel", "chat"), Map.entry("screen-flash.fade-in-ticks", -1),
                Map.entry("screen-flash.duration-ticks", 101),
                Map.entry("source-less-sounds.offset.forward-blocks", Double.POSITIVE_INFINITY),
                Map.entry("source-less-sounds.playback-ticks", 0),
                Map.entry("block-change.block-data", "minecraft:air"), Map.entry("block-change.range-blocks", 0),
                Map.entry("block-change.duration-ticks", 101), Map.entry("sign.block-data", "minecraft:stone"),
                Map.entry("sign.range-blocks", Double.POSITIVE_INFINITY), Map.entry("sign.duration-ticks", 0),
                Map.entry("hurt-flash.playback-ticks", 101), Map.entry("victim-ghost.range-blocks", Double.NaN),
                Map.entry("victim-ghost.duration-ticks", 101), Map.entry("victim-ghost.skin", "victim"),
                Map.entry("episodes.quiet-ticks", 0), Map.entry("episodes.medium.interval-ticks", 0));
        for (Map.Entry<String, Object> entry : invalid.entrySet()) {
            YamlConfiguration yaml = shipped();
            yaml.set("effects." + entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> PresentationConfig.load(yaml)).isInstanceOf(ConfigValidationException.class)
                    .hasMessageContaining("effects." + entry.getKey());
        }
        YamlConfiguration yaml = shipped();
        yaml.set("effects.sign.block-data", "minecraft:oak_wall_sign[rotation=0,waterlogged=false]");
        assertThatThrownBy(() -> PresentationConfig.load(yaml)).hasMessageContaining("effects.sign.block-data");
        yaml.set("effects.sign.block-data", "minecraft:oak_sign[rotation=0,waterlogged=false]");
        yaml.set("effects.screen-flash.duration-ticks", 100);
        assertThatThrownBy(() -> PresentationConfig.load(yaml)).hasMessageContaining("effects.screen-flash.duration-ticks");
        yaml.set("effects.screen-flash.duration-ticks", 30);
        yaml.set("effects.episodes.quiet-ticks", 601);
        assertThatThrownBy(() -> PresentationConfig.load(yaml)).hasMessageContaining("effects.episodes.quiet-ticks");
        yaml.set("effects.episodes.quiet-ticks", 20);
        yaml.set("effects.episodes.high.interval-ticks", 6000);
        assertThatThrownBy(() -> PresentationConfig.load(yaml)).hasMessageContaining("effects.episodes");
    }

    @Test void tuningDefaultsScaleEveryTimedVisualAndKeepCadenceAndCaps() {
        PluginConfig config = PluginConfig.load(shipped());
        EffectsConfigSection effects = config.effects();
        assertThat(config.psychosis().chat().minLetters()).isEqualTo(6);
        assertThat(effects.checkInterval()).isEqualTo(java.time.Duration.ofSeconds(1));
        int index = 0;
        for (PsychosisLevel level : List.of(PsychosisLevel.MEDIUM, PsychosisLevel.HIGH, PsychosisLevel.EXTREME)) {
            assertThat(effects.presentation().episodes().intervalTicks(level)).isEqualTo(new long[]{2400, 1200, 400}[index]);
            assertThat(effects.quietInterval(level)).isEqualTo(java.time.Duration.ofSeconds(new int[]{120, 60, 20}[index]));
            PresentationConfig scaled = effects.scaled(level).presentation();
            for (var type : List.of(AmbientEffectType.SKY, AmbientEffectType.PARTICLES, AmbientEffectType.SCREEN_FLASH,
                    AmbientEffectType.BLOCK_CHANGE, AmbientEffectType.SIGN, AmbientEffectType.VICTIM_GHOST,
                    AmbientEffectType.ADVANCEMENT_TOAST, AmbientEffectType.BOSS_BAR, AmbientEffectType.SILVERFISH)) {
                long base = type == AmbientEffectType.PARTICLES ? effects.presentation().particles().durationTicks()
                        : effects.presentation().durationTicks(type, config.sounds());
                long expected = Math.min(type == AmbientEffectType.SKY ? 200 : 100, (long) Math.ceil(base * new double[]{1, 1.5, 2}[index]));
                if (type == AmbientEffectType.PARTICLES) expected = Math.max(expected, 41); // Fixed client tail is not shortened.
                assertThat(scaled.durationTicks(type, config.sounds())).as(type + " at " + level).isEqualTo(expected);
            }
            assertThat(scaled.sky().durationTicks()).isEqualTo(new int[]{100, 150, 200}[index]);
            assertThat(effects.quietInterval(level).toMillis()).isGreaterThan(scaled.sky().durationTicks() * 50L);
            assertThat(effects.presentation().episodes().intervalTicks(level)).isGreaterThan(scaled.sky().durationTicks());
            assertThat(scaled.flash().totalTicks()).isEqualTo(new int[]{40, 60, 80}[index]);
            assertThat(scaled.particles().count()).isEqualTo(effects.presentation().particles().count());
            assertThat(scaled.particles().emissionDelay(scaled.particles().count() - 1) + 41)
                    .isEqualTo(scaled.particles().totalTicks());
            index++;
        }
        for (AmbientEffectType type : AmbientEffectType.values()) assertThat(effects.getEffect(type).sessionCap()).isEqualTo(6);
        assertThat(effects.presentation().phantom().mobs()).containsExactly("minecraft:silverfish", "minecraft:zombie",
                "minecraft:skeleton", "minecraft:spider", "minecraft:creeper", "minecraft:enderman");
        assertThat(effects.presentation().phantom().distance()).isEqualTo(8);
        assertThat(effects.presentation().phantom().durationTicks()).isEqualTo(20);
    }

    @Test void skyAloneAllowsTwoHundredTicksAndStillCapsScaling() {
        YamlConfiguration yaml = shipped();
        yaml.set("effects.sky.duration-ticks", 200);
        var config = PresentationConfig.load(yaml).scaled(PsychosisLevel.EXTREME);
        assertThat(config.sky().durationTicks()).isEqualTo(200);
        assertThat(config.flash().totalTicks()).isLessThanOrEqualTo(100);
        yaml.set("effects.particles.duration-ticks", 101);
        assertThatThrownBy(() -> PresentationConfig.load(yaml)).hasMessageContaining("effects.particles.duration-ticks");
        yaml.set("effects.particles.duration-ticks", 40);
        yaml.set("effects.sky.duration-ticks", 0);
        assertThatThrownBy(() -> PresentationConfig.load(yaml)).hasMessageContaining("effects.sky.duration-ticks");
    }

    @Test void tuningLeavesValidateOnLoadAndLiveEditWithoutChangingSnapshotOrDisk() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        Map<String, List<Object>> invalid = Map.of(
                "psychosis.chat.min-letters", List.of(0, -1, 2.5),
                "effects.episodes.duration-scale.medium", List.of(0.9, Double.NaN, Double.POSITIVE_INFINITY, "bad"),
                "effects.episodes.duration-scale.high", List.of(0.5, Double.NaN, Double.NEGATIVE_INFINITY, "bad"),
                "effects.episodes.duration-scale.extreme", List.of(1, Double.NaN, Double.POSITIVE_INFINITY, "bad"),
                "effects.silverfish.duration-ticks", List.of(0, 101, 2.5),
                "effects.silverfish.distance-blocks", List.of(0, Double.NaN, Double.POSITIVE_INFINITY),
                "effects.silverfish.mobs", List.of(List.of(), List.of("unknown"), List.of("item"), List.of("cow"),
                        List.of("armor_stand"), List.of("text_display"), List.of("minecraft:zombie", 1)));
        for (var entry : invalid.entrySet()) {
            assertThat(manager.isEditableKey(entry.getKey())).isTrue();
            for (Object value : entry.getValue()) {
                YamlConfiguration yaml = shipped();
                yaml.set(entry.getKey(), value);
                assertThatThrownBy(() -> PluginConfig.load(yaml)).hasMessageContaining(entry.getKey());
                RuntimeSnapshot before = manager.snapshot();
                String disk = Files.readString(folder.resolve("config.yml"));
                assertThatThrownBy(() -> manager.set(entry.getKey(), value.toString())).hasMessageContaining(entry.getKey());
                assertThat(manager.snapshot()).isSameAs(before);
                assertThat(Files.readString(folder.resolve("config.yml"))).isEqualTo(disk);
            }
        }
        YamlConfiguration scalarList = shipped();
        scalarList.set("effects.silverfish.mobs", "zombie");
        assertThatThrownBy(() -> PluginConfig.load(scalarList)).hasMessageContaining("effects.silverfish.mobs");
        manager.set("psychosis.chat.min-letters", "4");
        manager.set("effects.episodes.duration-scale.extreme", "2.5");
        manager.set("effects.episodes.duration-scale.high", "2.25");
        manager.set("effects.episodes.duration-scale.medium", "1.25");
        manager.set("effects.silverfish.mobs", "[minecraft:zombie, skeleton]");
        manager.set("effects.silverfish.duration-ticks", "37");
        manager.set("effects.silverfish.distance-blocks", "9.5");
        manager.reload();
        assertThat(manager.config().psychosis().chat().minLetters()).isEqualTo(4);
        assertThat(manager.config().effects().presentation().durationScale()).isEqualTo(new PresentationConfig.DurationScale(1.25, 2.25, 2.5));
        assertThat(manager.config().effects().presentation().phantom().mobs()).containsExactly("minecraft:zombie", "minecraft:skeleton");
        assertThat(manager.config().effects().presentation().phantom().durationTicks()).isEqualTo(37);
        assertThat(manager.config().effects().presentation().phantom().distance()).isEqualTo(9.5);
        var huge = new PresentationConfig.DurationScale(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
        assertThat(huge.ticks(100, PsychosisLevel.EXTREME)).isEqualTo(100);
    }

    private ConfigManager manager() throws Exception {
        for (String resource : List.of("config.yml", "messages_en.yml", "messages_es.yml")) {
            try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
                Files.copy(in, folder.resolve(resource));
            }
        }
        MessageRegistry messages = new MessageRegistry(folder.toFile(), "en", null);
        return new ConfigManager(folder.resolve("config.yml").toFile(), messages, Runnable::run, null);
    }

    @Test void liveEditsUseTheSameValidationAndMessageListFallback() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        for (String id : List.of("sky", "particles", "screen-flash", "source-less-sounds", "block-change", "sign", "hurt-flash", "victim-ghost")) {
            assertThat(manager.isEditableKey("effects." + id + ".minimum-level")).isTrue();
            assertThat(manager.isEditableKey("effects." + id + ".session-cap")).isTrue();
        }
        manager.set("effects.sky.duration-ticks", "80");
        manager.set("effects.source-less-sounds.offset.forward-blocks", "-3.5");
        assertThat(manager.snapshot().config().effects().presentation().sky().durationTicks()).isEqualTo(80);
        assertThat(manager.snapshot().config().effects().presentation().sounds().forward()).isEqualTo(-3.5);
        RuntimeSnapshot before = manager.snapshot();
        assertThatThrownBy(() -> manager.set("effects.sky.minimum-level", "medium"))
                .hasMessageContaining("effects.sky.minimum-level");
        assertThat(manager.snapshot()).isSameAs(before);
        assertThatThrownBy(() -> manager.set("effects.source-less-sounds.sound-slot", "missing"))
                .hasMessageContaining("effects.source-less-sounds.sound-slot");
        manager.set("effects.block-change.block-data", "minecraft:granite");
        manager.set("effects.sign.duration-ticks", "80");
        manager.set("effects.victim-ghost.range-blocks", "4.5");
        assertThat(manager.snapshot().config().effects().presentation().block().data()).isEqualTo("minecraft:granite");
        assertThat(manager.snapshot().config().effects().presentation().sign().durationTicks()).isEqualTo(80);
        assertThat(manager.snapshot().config().effects().presentation().ghost().range()).isEqualTo(4.5);
        RuntimeSnapshot beforeHurt = manager.snapshot();
        assertThatThrownBy(() -> manager.set("effects.hurt-flash.sound-slot", "missing"))
                .hasMessageContaining("effects.hurt-flash.sound-slot");
        assertThat(manager.snapshot()).isSameAs(beforeHurt);
        manager.set("effects.sign.lines", "['&7X', '&8Y']");
        assertThat(manager.snapshot().messages().lineKeys("effects.sign.lines")).hasSize(2);
        assertThatThrownBy(() -> manager.set("effects.sign.lines", "['" + "x".repeat(81) + "']"))
                .hasMessageContaining("effects.sign.lines");
        RuntimeSnapshot beforeLabel = manager.snapshot();
        assertThatThrownBy(() -> manager.set("effects.victim-ghost.label", "{victim}"))
                .hasMessageContaining("effects.victim-ghost.label");
        assertThat(manager.snapshot()).isSameAs(beforeLabel);

        assertThat(manager.isEditableKey(ScreenLines.KEY)).isTrue();
        manager.set(ScreenLines.KEY, "['&8X, Y', '&7Z']");
        assertThat(manager.snapshot().messages().lineKeys(ScreenLines.KEY))
                .containsExactly(ScreenLines.KEY + ".0", ScreenLines.KEY + ".1");
        assertThatThrownBy(() -> manager.set(ScreenLines.KEY, "['" + "x".repeat(161) + "']"))
                .hasMessageContaining(ScreenLines.KEY);
        manager.reload();
        assertThat(manager.snapshot().messages().lineKeys(ScreenLines.KEY)).hasSize(2);
        assertThat(manager.snapshot().config().effects().presentation().sky().durationTicks()).isEqualTo(80);
        MessagesSnapshot fallback = new MessagesSnapshot("en", "es", Map.of(),
                Map.of(ScreenLines.KEY, "x\ny"), Map.of(), Map.of());
        assertThat(fallback.lineKeys(ScreenLines.KEY)).containsExactly(ScreenLines.KEY + ".0", ScreenLines.KEY + ".1");
    }

    @Test void messageListValidationAlsoRunsOnDiskReload() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        RuntimeSnapshot before = manager.snapshot();
        File messageFile = folder.resolve("messages_es.yml").toFile();
        YamlFileUpdater.updateLeafAndSave(messageFile, ScreenLines.KEY, "['" + "x".repeat(161) + "']");
        assertThatThrownBy(manager::reload).hasMessageContaining(ScreenLines.KEY);
        assertThat(manager.snapshot()).isSameAs(before);
        assertThatThrownBy(() -> ScreenLines.validate(List.of("x\ny"))).hasMessageContaining(ScreenLines.KEY);
    }

    @Test void customListsRejectBeforePersistenceAndLogKeyAndIndex() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        java.util.List<String> logs = new java.util.ArrayList<>();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(ConfigManager.class.getName());
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { logs.add(record.getMessage()); }
            public void flush() {}
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            manager.set(CatalogueLines.CUSTOM, "['&8...']");
            RuntimeSnapshot before = manager.snapshot();
            String disk = Files.readString(folder.resolve("messages_en.yml"));
            assertThatThrownBy(() -> manager.set(CatalogueLines.CUSTOM, "['&8...', 'Permission granted']"))
                    .hasMessageContaining(CatalogueLines.CUSTOM + "[1]");
            assertThat(logs).anySatisfy(log -> assertThat(log).contains(CatalogueLines.CUSTOM + "[1]"));
            assertThat(manager.snapshot()).isSameAs(before);
            assertThat(Files.readString(folder.resolve("messages_en.yml"))).isEqualTo(disk);
            manager.set(CatalogueLines.CUSTOM, "[]");
            assertThat(manager.snapshot().messages().lineKeys(CatalogueLines.CUSTOM)).isEmpty();
            YamlFileUpdater.updateLeafAndSave(folder.resolve("messages_es.yml").toFile(), CatalogueLines.CUSTOM,
                    "['Advancement earned']");
            assertThatThrownBy(manager::reload).hasMessageContaining(CatalogueLines.CUSTOM + "[0]");
        } finally { logger.removeHandler(handler); }
    }

    @Test void upgradeAdoptsPrivateTextOwnerOverrides() throws Exception {
        ConfigManager manager = manager();
        File file = folder.resolve("config.yml").toFile();
        for (String id : List.of("private-chat", "fake-connection")) YamlFileUpdater.removeLeafAndSave(file, "effects." + id);
        YamlConfiguration legacy = YamlConfiguration.loadConfiguration(file);
        legacy.set("effects.whisper.cooldown", "7m");
        legacy.set("effects.whisper.session-cap", 9);
        legacy.set("effects.fake-announcement.cooldown", "17m");
        legacy.set("effects.fake-announcement.session-cap", 8);
        legacy.save(file);
        File messages = folder.resolve("messages_en.yml").toFile();
        YamlFileUpdater.removeLeafAndSave(messages, "effects.private-chat");
        YamlFileUpdater.removeLeafAndSave(messages, "effects.fake-connection");
        YamlFileUpdater.updateLeafAndSave(messages, "effects.whisper-1", "'&8...' ");
        manager.initialize();
        EffectsConfigSection config = manager.snapshot().config().effects();
        assertThat(config.getEffect(AmbientEffectType.WHISPER).cooldown()).isEqualTo(java.time.Duration.ofMinutes(7));
        assertThat(config.getEffect(AmbientEffectType.WHISPER).sessionCap()).isEqualTo(9);
        assertThat(config.getEffect(AmbientEffectType.FAKE_ANNOUNCEMENT).cooldown()).isEqualTo(java.time.Duration.ofMinutes(17));
        assertThat(config.getEffect(AmbientEffectType.FAKE_ANNOUNCEMENT).sessionCap()).isEqualTo(8);
        assertThat(manager.snapshot().messages().resolveRaw("effects.private-chat.lines.0", new java.util.HashSet<>(), null))
                .isEqualTo(manager.snapshot().messages().resolveRaw("effects.whisper-1", new java.util.HashSet<>(), null));
        manager.reload();
        assertThat(manager.snapshot().config().effects().getEffect(AmbientEffectType.WHISPER)).isEqualTo(config.getEffect(AmbientEffectType.WHISPER));
        YamlConfiguration retired = YamlConfiguration.loadConfiguration(file);
        for (String id : List.of("whisper", "fake-announcement")) {
            for (String leaf : List.of("cooldown", "session-cap")) {
                String key = "effects." + id + "." + leaf;
                assertThat(shipped().contains(key)).isFalse();
                assertThat(retired.contains(key)).isFalse();
                assertThat(manager.isEditableKey(key)).isFalse();
                assertThatThrownBy(() -> manager.get(key)).isInstanceOf(ConfigValidationException.class).hasMessageContaining(key);
                assertThatThrownBy(() -> manager.set(key, "0")).isInstanceOf(ConfigValidationException.class);
            }
        }
        manager.set("effects.private-chat.session-cap", "0");
        assertThat(manager.snapshot().config().effects().getEffect(AmbientEffectType.WHISPER).sessionCap()).isZero();
        manager.reload();
        assertThat(manager.snapshot().config().effects().getEffect(AmbientEffectType.WHISPER).sessionCap()).isZero();
    }

    @Test void upgradeAdoptsOwnerCadenceWithoutResettingExistingLimits() throws Exception {
        ConfigManager manager = manager();
        File configFile = folder.resolve("config.yml").toFile();
        YamlFileUpdater.removeLeafAndSave(configFile, "effects.episodes");
        YamlFileUpdater.updateLeafAndSave(configFile, "effects.quiet-interval.medium", "8m");
        YamlFileUpdater.updateLeafAndSave(configFile, "effects.quiet-interval.high", "3m");
        YamlFileUpdater.updateLeafAndSave(configFile, "effects.quiet-interval.extreme", "45s");
        YamlFileUpdater.updateLeafAndSave(configFile, "effects.sky.session-cap", "7");
        manager.initialize();
        EffectsConfigSection config = manager.snapshot().config().effects();
        assertThat(config.quietInterval(PsychosisLevel.MEDIUM)).isEqualTo(java.time.Duration.ofMinutes(8));
        assertThat(config.quietInterval(PsychosisLevel.HIGH)).isEqualTo(java.time.Duration.ofMinutes(3));
        assertThat(config.quietInterval(PsychosisLevel.EXTREME)).isEqualTo(java.time.Duration.ofSeconds(45));
        assertThat(config.getEffect(AmbientEffectType.SKY).sessionCap()).isEqualTo(7);
        manager.reload();
        assertThat(manager.snapshot().config().effects().presentation().episodes()).isEqualTo(config.presentation().episodes());
    }
}
