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
