package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.*;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class HorrorEffectsTest {
    @TempDir Path folder;
    private ConfigManager manager() {
        var manager = new ConfigManager(folder.resolve("config.yml").toFile(),
                new MessageRegistry(folder.toFile(), "en", null), Runnable::run, null);
        manager.initialize();
        return manager;
    }

    @Test void footstepsApproachFromBehindAndStopWithinThreeSeconds() {
        var config = HorrorConfig.defaults();
        var steps = HorrorDecision.footsteps(0, config);
        assertThat(steps).hasSize(7);
        assertThat(steps.getFirst().tick()).isZero();
        assertThat(steps.getLast().tick()).isEqualTo(50);
        assertThat(steps).extracting(HorrorDecision.Step::z).containsExactly(-6., -5.333333333333333,
                -4.666666666666667, -4., -3.3333333333333335, -2.6666666666666665, -2.);
        assertThat(steps).allSatisfy(step -> assertThat(step.x()).isZero());
        var rotated = HorrorDecision.footsteps(90, config);
        assertThat(rotated.getFirst().x()).isCloseTo(6, within(.0001));
        assertThat(rotated.getLast().x()).isCloseTo(2, within(.0001));
        assertThat(config.durationTicks(AmbientEffectType.FOOTSTEPS, SoundsConfigSection.defaults())).isEqualTo(60);
    }

    @Test void watcherRequiresLookingStraightAtFigureAndIgnoresVectorMagnitude() {
        assertThat(HorrorDecision.lookedAt(0, 0, 30, 0, 0, 1, .985)).isTrue();
        assertThat(HorrorDecision.lookedAt(8, 0, 30, 0, 0, 1, .985)).isFalse();
        assertThat(HorrorDecision.lookedAt(0, 0, -30, 0, 0, 1, .985)).isFalse();
        assertThat(HorrorDecision.lookedAt(0, 30, 30, 0, 1, 1, .985)).isTrue();
        assertThat(HorrorDecision.lookedAt(0, 0, 0, 0, 0, 1, .985)).isTrue();
    }

    @Test void watcherOffsetsStayInViewOffCentreAndLightningStaysAtConfiguredRange() {
        for (double yaw : List.of(0., 90., 180., 270.)) {
            var direction = HorrorDecision.offset(yaw, 1);
            for (double side : List.of(-30., -18., 18., 30.)) {
                for (double distance : List.of(20., 40.)) {
                    var at = HorrorDecision.offset(yaw + side, distance);
                    assertThat(Math.hypot(at.x(), at.z())).isCloseTo(distance, within(.0001));
                    double dot = (at.x() * direction.x() + at.z() * direction.z()) / distance;
                    assertThat(dot).isBetween(.86, .96);
                    assertThat(HorrorDecision.lookedAt(at.x(), 0, at.z(), direction.x(), 0, direction.z(), .985)).isFalse();
                }
            }
            var lightning = HorrorDecision.offset(yaw, HorrorConfig.defaults().lightningRange());
            assertThat(Math.hypot(lightning.x(), lightning.z())).isCloseTo(12, within(.0001));
        }
    }

    @Test void soundTailAndNumericBoundsCannotBypassValidation() {
        var root = new YamlConfiguration();
        root.set("effects.footsteps.volume", Double.NaN);
        assertThatThrownBy(() -> HorrorConfig.load(root)).isInstanceOf(ConfigValidationException.class);
        root.set("effects.footsteps.volume", null);
        root.set("effects.torch-flicker.duration-ticks", 6);
        root.set("effects.torch-flicker.flickers", 4);
        assertThatThrownBy(() -> HorrorConfig.load(root)).isInstanceOf(ConfigValidationException.class);
        var config = HorrorConfig.defaults();
        var tooLong = new SoundsConfigSection(Map.of("horror-thunder", new SoundSlotConfig(List.of(
                new SoundLayerConfig("minecraft:entity.lightning_bolt.thunder", .7f, 1, SoundCategory.WEATHER, 121)))));
        assertThatThrownBy(() -> config.validateSounds(tooLong)).isInstanceOf(ConfigValidationException.class);
    }

    @Test void noisesRequireRealSourceKindsAndLightAllowListExcludesGameplayBlocks() {
        assertThat(HorrorDecision.noiseKey("oak_door")).isEqualTo("minecraft:block.wooden_door.open");
        assertThat(HorrorDecision.noiseKey("iron_trapdoor")).isEqualTo("minecraft:block.iron_trapdoor.open");
        assertThat(HorrorDecision.noiseKey("waxed_exposed_copper_door")).isEqualTo("minecraft:block.copper_door.open");
        assertThat(HorrorDecision.noiseKey("chest")).isEqualTo("minecraft:block.chest.open");
        assertThat(HorrorDecision.noiseKey("ender_chest")).isEqualTo("minecraft:block.ender_chest.open");
        assertThat(HorrorDecision.noiseKey("waxed_oxidized_copper_chest")).isEqualTo("minecraft:block.copper_chest.open");
        assertThat(HorrorDecision.noiseKey("cherry_door")).isEqualTo("minecraft:block.cherry_wood.door.open");
        assertThat(HorrorDecision.noiseKey("bamboo_trapdoor")).isEqualTo("minecraft:block.bamboo_wood.trapdoor.open");
        assertThat(HorrorDecision.noiseKey("crimson_door")).isEqualTo("minecraft:block.nether_wood.door.open");
        assertThat(HorrorDecision.noiseKey("warped_trapdoor")).isEqualTo("minecraft:block.nether_wood.trapdoor.open");
        for (String material : List.of("stone", "air", "barrel", "oak_fence_gate"))
            assertThat(HorrorDecision.noiseKey(material)).isEmpty();
        for (String material : List.of("torch", "wall_torch", "soul_torch", "soul_wall_torch", "lantern", "soul_lantern"))
            assertThat(HorrorDecision.light(material)).isTrue();
        for (String material : List.of("redstone_torch", "campfire", "glowstone", "chest"))
            assertThat(HorrorDecision.light(material)).isFalse();
    }

    @Test void flickerTransitionsAlternateBeforeFinalRestore() {
        var config = HorrorConfig.defaults();
        assertThat(java.util.stream.IntStream.range(0, config.flickers() * 2)
                .mapToLong(i -> HorrorDecision.flickerTick(i, config)).toArray()).containsExactly(0, 6, 13, 20, 26, 33);
        assertThat(config.flickerTicks()).isEqualTo(40);
    }

    @Test void catalogueUsesRequestedFloorsSwitchesCapsAndQuietTimeIncludingThunderTail() {
        var manager = manager();
        var config = manager.config().effects();
        var state = new PlayerEffectState();
        long now = 1_000_000;
        for (var type : HorrorConfig.TYPES) {
            var rule = config.presentation().rules().get(type);
            assertThat(rule.minimumLevel()).isEqualTo(type.floor());
            assertThat(rule.enabled()).isTrue();
            assertThat(AmbientEffectScheduler.availableEffects(config, state, type.floor(), now, new Random(1))).contains(type);
            assertThat(rule.allows(PsychosisLevel.NEUTRAL)).isFalse();
            assertThat(rule.allows(PsychosisLevel.SERENITY)).isFalse();
            if (type.floor() != PsychosisLevel.LOW)
                assertThat(AmbientEffectScheduler.availableEffects(config, state, PsychosisLevel.LOW, now, new Random(1))).doesNotContain(type);
            String key = "effects." + type.configId() + ".enabled";
            manager.set(key, "false");
            assertThat(AmbientEffectScheduler.availableEffects(manager.config().effects(), state, PsychosisLevel.EXTREME, now, new Random(1)))
                    .doesNotContain(type);
            manager.set(key, "true");
            state.recordFired(type, now);
            assertThat(state.canFire(type, config.getEffect(type), now + 1)).isFalse();
            for (int i = 1; i < 6; i++) state.recordFired(type, now);
            assertThat(state.canFire(type, manager.config().effects().getEffect(type), now + 1_000_000)).isFalse();
        }
        var sounds = new SoundsConfigSection(Map.of("horror-thunder", new SoundSlotConfig(List.of(
                new SoundLayerConfig("minecraft:entity.lightning_bolt.thunder", .7f, 1, SoundCategory.WEATHER, 30)))));
        assertThat(config.presentation().durationTicks(AmbientEffectType.FAKE_LIGHTNING, sounds)).isEqualTo(110);
        // Fixed horror timing preserves the three-second approach and few-tick word even at Extreme.
        assertThat(config.scaled(PsychosisLevel.EXTREME).presentation().horror()).isEqualTo(config.presentation().horror());
    }

    @Test void malformedConfigurationAndWordListsAreRejectedBeforePublication() {
        var manager = manager();
        for (var entry : Map.of("effects.watcher.kinds", "[zombie]", "effects.watcher.look-dot", "0.3",
                "effects.watcher.min-distance-blocks", "19", "effects.watcher.max-distance-blocks", "41",
                "effects.footsteps.end-distance-blocks", "6", "effects.torch-flicker.flickers", "30",
                "effects.subliminal.duration-ticks", "11", "effects.fake-lightning.minimum-level", "high",
                "effects.fake-lightning.sound-slot", "missing").entrySet()) {
            var before = manager.snapshot();
            assertThatThrownBy(() -> manager.set(entry.getKey(), entry.getValue())).isInstanceOf(ConfigValidationException.class);
            assertThat(manager.snapshot()).isSameAs(before);
        }
        assertThatThrownBy(() -> manager.set("effects.subliminal.words", "['two words']"))
                .isInstanceOf(ConfigValidationException.class);
        manager.set("effects.watcher.kinds", "[enderman, wither_skeleton]");
        assertThat(manager.config().effects().presentation().horror().watcherKinds()).containsExactly("enderman", "wither_skeleton");
        for (String language : List.of("en", "es")) {
            var messages = MessageRegistry.loadMessagesSnapshot(folder.toFile(), language, null);
            assertThat(messages.lineKeys("effects.subliminal.words")).hasSize(3);
            assertThat(messages.resolveRaw("effects.subliminal.words.2", new HashSet<>(), null)).contains("{player}");
            for (var type : HorrorConfig.TYPES)
                assertThat(messages.isKnownKey("features.name.effects." + type.configId())).isTrue();
        }
    }

    @Test void legacyUpgradeAdoptsMissingBlocksAndPreservesOwnersExistingValues() throws Exception {
        var manager = manager();
        var path = folder.resolve("config.yml");
        var yaml = YamlConfiguration.loadConfiguration(path.toFile());
        for (var type : HorrorConfig.TYPES) yaml.set("effects." + type.configId(), null);
        yaml.set("effects.subliminal.enabled", false);
        yaml.set("effects.subliminal.duration-ticks", 5);
        yaml.save(path.toFile());
        var upgraded = new ConfigManager(path.toFile(), new MessageRegistry(folder.toFile(), "en", null), Runnable::run, null);
        upgraded.initialize();
        assertThat(upgraded.config().effects().presentation().horror().subliminalTicks()).isEqualTo(5);
        assertThat(upgraded.config().effects().presentation().rules().get(AmbientEffectType.SUBLIMINAL).enabled()).isFalse();
        for (var type : HorrorConfig.TYPES) {
            assertThat(upgraded.get("effects." + type.configId() + ".minimum-level"))
                    .isEqualTo(type.floor().name().toLowerCase(Locale.ROOT));
        }
    }

    @Test void everyHorrorPresentationRestoresExactlyOnceOnInterruptionOrExpiry() {
        for (var type : HorrorConfig.TYPES) {
            var registry = new AmbientEntityRegistry();
            var restored = new AtomicInteger();
            UUID player = UUID.randomUUID();
            var entry = new ActivePresentationEntry(player, type, 60, restored::incrementAndGet);
            registry.registerPresentation(entry);
            registry.cleanForPlayer(player);
            registry.cleanPresentation(entry);
            registry.cleanAll();
            assertThat(restored.get()).isEqualTo(1);
            assertThat(entry.ended().get()).isTrue();
        }
    }
}
