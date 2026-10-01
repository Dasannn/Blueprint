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

    @Test void invalidPresentationValuesNameTheirPath() {
        Map<String, Object> invalid = Map.ofEntries(
                Map.entry("sky.mode", "day"), Map.entry("sky.duration-ticks", 0),
                Map.entry("sky.cooldown-ticks", 0), Map.entry("sky.session-cap", -1),
                Map.entry("sky.enabled", "yes"), Map.entry("sky.unknown", 1),
                Map.entry("particles.count", 0), Map.entry("particles.radius-blocks", Double.NaN),
                Map.entry("particles.placement", "far"), Map.entry("particles.type", "unknown"),
                Map.entry("screen-flash.channel", "chat"), Map.entry("screen-flash.fade-in-ticks", -1),
                Map.entry("screen-flash.duration-ticks", 101),
                Map.entry("source-less-sounds.offset.forward-blocks", Double.POSITIVE_INFINITY),
                Map.entry("source-less-sounds.playback-ticks", 0),
                Map.entry("episodes.quiet-ticks", 0), Map.entry("episodes.medium.interval-ticks", 0));
        for (Map.Entry<String, Object> entry : invalid.entrySet()) {
            YamlConfiguration yaml = shipped();
            yaml.set("effects." + entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> PresentationConfig.load(yaml)).isInstanceOf(ConfigValidationException.class)
                    .hasMessageContaining("effects." + entry.getKey());
        }
        YamlConfiguration yaml = shipped();
        yaml.set("effects.screen-flash.duration-ticks", 100);
        assertThatThrownBy(() -> PresentationConfig.load(yaml)).hasMessageContaining("effects.screen-flash.duration-ticks");
        yaml.set("effects.screen-flash.duration-ticks", 30);
        yaml.set("effects.episodes.quiet-ticks", 601);
        assertThatThrownBy(() -> PresentationConfig.load(yaml)).hasMessageContaining("effects.episodes.quiet-ticks");
        yaml.set("effects.episodes.quiet-ticks", 20);
        yaml.set("effects.episodes.high.interval-ticks", 6000);
        assertThatThrownBy(() -> PresentationConfig.load(yaml)).hasMessageContaining("effects.episodes");
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
        for (String id : List.of("sky", "particles", "screen-flash", "source-less-sounds")) {
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
        YamlFileUpdater.updateLeafAndSave(file, "effects.whisper.cooldown", "7m");
        YamlFileUpdater.updateLeafAndSave(file, "effects.whisper.session-cap", "9");
        YamlFileUpdater.updateLeafAndSave(file, "effects.fake-announcement.cooldown", "17m");
        YamlFileUpdater.updateLeafAndSave(file, "effects.fake-announcement.session-cap", "8");
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
