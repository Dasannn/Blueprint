package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.*;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class PresentationEffectsTest {
    private YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8));
    }

    @Test void levelFloorsAndRaisedFloorsAreEnforced() {
        YamlConfiguration yaml = shipped();
        PresentationConfig config = PresentationConfig.load(yaml);
        for (AmbientEffectType type : config.rules().keySet()) {
            PresentationConfig.Rule rule = config.rules().get(type);
            assertThat(rule.allows(PsychosisLevel.LOW)).isFalse();
            assertThat(rule.allows(PsychosisLevel.MEDIUM)).isEqualTo(type.floor() == PsychosisLevel.MEDIUM);
            assertThat(rule.allows(PsychosisLevel.HIGH)).isTrue();
            assertThat(rule.allows(PsychosisLevel.EXTREME)).isTrue();
            yaml.set("effects." + type.configId() + ".minimum-level", "extreme");
            PresentationConfig.Rule raised = PresentationConfig.load(yaml).rules().get(type);
            assertThat(raised.allows(PsychosisLevel.HIGH)).isFalse();
            assertThat(raised.allows(PsychosisLevel.EXTREME)).isTrue();
            yaml.set("effects." + type.configId() + ".minimum-level", "low");
            assertThatThrownBy(() -> PresentationConfig.load(yaml)).isInstanceOf(ConfigValidationException.class)
                    .hasMessageContaining(type.configId() + ".minimum-level");
            yaml.set("effects." + type.configId() + ".minimum-level", rule.minimumLevel().name());
        }
        yaml.set("effects.sky.minimum-level", "medium");
        assertThatThrownBy(() -> PresentationConfig.load(yaml)).hasMessageContaining("effects.sky.minimum-level");
    }

    record Scheduled(Runnable action, long ticks, boolean[] cancelled) {}

    private AmbientEffectDispatcher dispatcher(AmbientEntityRegistry registry, List<Scheduled> scheduled) {
        MessageRegistry messages = MessageRegistry.fromMaps(Map.of(), Map.of(), "en", null);
        return new AmbientEffectDispatcher(null, messages, null, new FakeSilverfishService(null, registry, null),
                (action, ticks) -> {
                    boolean[] cancelled = {false};
                    scheduled.add(new Scheduled(action, ticks, cancelled));
                    return () -> cancelled[0] = true;
                });
    }

    @Test void everyStartSchedulesRestorationAndAllLifecyclePathsRestoreExactlyOnce() {
        PresentationConfig config = PresentationConfig.load(shipped());
        SoundsConfigSection sounds = SoundsConfigSection.load(shipped());
        UUID id = UUID.randomUUID();
        for (AmbientEffectType type : config.rules().keySet()) {
            for (String end : List.of("expiry", "quit", "world-change", "disable", "interruption")) {
                AmbientEntityRegistry registry = new AmbientEntityRegistry();
                List<Scheduled> scheduled = new ArrayList<>();
                AmbientEffectDispatcher dispatcher = dispatcher(registry, scheduled);
                AtomicInteger restored = new AtomicInteger();
                long ticks = config.durationTicks(type, sounds);
                ActivePresentationEntry entry = dispatcher.startPresentation(id, type, ticks, restored::incrementAndGet);
                assertThat(entry).isNotNull();
                assertThat(entry.type()).isEqualTo(type);
                assertThat(entry.durationTicks()).isEqualTo(ticks);
                assertThat(registry.presentationsFor(id)).containsExactly(entry);
                assertThat(scheduled).singleElement().satisfies(task -> assertThat(task.ticks()).isEqualTo(ticks));
                switch (end) {
                    case "expiry" -> scheduled.getFirst().action().run();
                    case "quit" -> new AmbientEffectsListener(registry, null, dispatcher).cleanupPlayer(id, true);
                    case "interruption" -> dispatcher.cancelPending(id);
                    case "world-change" -> new AmbientEffectsListener(registry, null, dispatcher).cleanupPlayer(id, false);
                    case "disable" -> dispatcher.cancelAllPending();
                    default -> throw new AssertionError(end);
                }
                assertThat(restored).hasValue(1);
                assertThat(registry.presentationsFor(id)).isEmpty();
                registry.cleanAll();
                scheduled.getFirst().action().run();
                assertThat(restored).hasValue(1);
                if (!end.equals("expiry"))
                    assertThat(scheduled.getFirst().cancelled()[0]).isTrue();
            }
        }
    }

    @Test void noDeliveryMayStartWithoutARestorationTask() {
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        MessageRegistry messages = MessageRegistry.fromMaps(Map.of(), Map.of(), "en", null);
        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(null, messages, null,
                new FakeSilverfishService(null, registry, null), (action, ticks) -> null);
        UUID id = UUID.randomUUID();
        assertThat(dispatcher.startPresentation(id, AmbientEffectType.SKY, 40, () -> {})).isNull();
        assertThat(registry.presentationsFor(id)).isEmpty();
    }

    @Test void delayedSoundTailAndFullTitleFadesPrecedeQuietAtEveryLevel() {
        YamlConfiguration yaml = shipped();
        yaml.set("effects.check-interval", "1ms");
        yaml.set("effects.episodes.medium.interval-ticks", 3);
        yaml.set("effects.episodes.high.interval-ticks", 2);
        yaml.set("effects.episodes.extreme.interval-ticks", 1);
        yaml.set("effects.episodes.quiet-ticks", 1);
        EffectsConfigSection effects = EffectsConfigSection.load(yaml);
        SoundSlotConfig slot = new SoundSlotConfig(List.of(
                new SoundLayerConfig("minecraft:block.stone.step", 1, 1, SoundCategory.AMBIENT, 0),
                new SoundLayerConfig("minecraft:ambient.cave", 1, 1, SoundCategory.AMBIENT, 80)));
        SoundsConfigSection sounds = new SoundsConfigSection(Map.of("source-less", slot));
        effects.presentation().validateSounds(sounds);
        assertThat(effects.presentation().durationTicks(AmbientEffectType.SOURCE_LESS_SOUNDS, sounds)).isEqualTo(100);
        PresentationConfig.Flash flash = effects.presentation().flash();
        assertThat(effects.presentation().durationTicks(AmbientEffectType.SCREEN_FLASH, sounds))
                .isEqualTo(flash.fadeInTicks() + flash.durationTicks() + flash.fadeOutTicks());
        for (PsychosisLevel level : List.of(PsychosisLevel.MEDIUM, PsychosisLevel.HIGH, PsychosisLevel.EXTREME)) {
            long total = 100 * 50L + effects.quietInterval(level).toMillis();
            PlayerEffectState state = new PlayerEffectState();
            state.recordEpisode(1000, total);
            assertThat(total).isGreaterThan(100 * 50L);
            assertThat(state.canStartEpisode(1000 + 80 * 50L)).isFalse();
            assertThat(state.canStartEpisode(1000 + 100 * 50L)).isFalse();
            assertThat(state.canStartEpisode(1000 + total)).isTrue();
        }
        SoundsConfigSection overflow = new SoundsConfigSection(Map.of("source-less", new SoundSlotConfig(List.of(
                new SoundLayerConfig("minecraft:ambient.cave", 1, 1, SoundCategory.AMBIENT, Long.MAX_VALUE)))));
        assertThatThrownBy(() -> effects.presentation().validateSounds(overflow))
                .hasMessageContaining("effects.source-less-sounds.playback-ticks");
    }

    @Test void skyAndScreenCleanupRetainNewerExternalOverrides() {
        SkyPresentation night = new SkyPresentation(1200, true, "CLEAR", 18000, false, null, true, false);
        assertThat(night.ownsTime(18000, false)).isTrue();
        assertThat(night.ownsTime(18001, false)).isFalse();
        assertThat(night.ownsTime(18000, true)).isFalse();
        assertThat(night.previousTimeOffset()).isEqualTo(1200);
        assertThat(night.previousTimeRelative()).isTrue();
        assertThat(night.resetTime(true)).isFalse();
        assertThat(night.resetTime(false)).isTrue();
        assertThat(night.resetWeather(true)).isFalse();
        assertThat(night.resetWeather(false)).isTrue();
        SkyPresentation storm = new SkyPresentation(0, true, null, 0, true, "DOWNFALL", false, true);
        assertThat(storm.ownsWeather("DOWNFALL")).isTrue();
        assertThat(storm.ownsWeather("CLEAR")).isFalse();
        assertThat(storm.ownsWeather(null)).isFalse();
        ScreenOwnership screen = new ScreenOwnership();
        assertThat(screen.mayClear()).isFalse(); // a skipped effect must never clear pre-existing UI
        screen.claimed();
        assertThat(screen.mayClear()).isTrue();
        screen.replaced();
        assertThat(screen.mayClear()).isFalse();
    }

    @Test void particlePositionsNeverExceedConfiguredRadius() {
        assertThat(new PresentationConfig.Particles("smoke", "around", 8, 1, 1).totalTicks()).isEqualTo(41);
        assertThat(new PresentationConfig.Particles("end_rod", "around", 8, 1, 40).totalTicks()).isEqualTo(72);
        for (String placement : List.of("around", "beneath")) {
            PresentationConfig.Particles config = new PresentationConfig.Particles("smoke", placement, 8, 2, 40);
            for (int i = 0; i < config.count(); i++) {
                ParticlePoint point = ParticlePoint.at(config, i);
                assertThat(Math.hypot(point.x(), point.z())).isLessThanOrEqualTo(2.00000001);
                assertThat(point.y()).isEqualTo(placement.equals("beneath") ? 0.05 : 1.0);
            }
        }
    }

    @Test void presentationPathsHaveNoMetricMoneyOrWorldMutationCalls() throws Exception {
        for (String file : List.of("AmbientEffectDispatcher.java", "PrivateScreen.java", "ActivePresentationEntry.java")) {
            String source = Files.readString(Path.of("src/main/java/com/dasannn/socialblueprint/feature/effects", file));
            assertThat(source).doesNotContain("ReputationRepository", "PsychosisRepository", "Confidence", "Economy",
                    "depositPlayer", "withdrawPlayer", ".setTime(", ".setFullTime(", ".setStorm(", ".setThundering(");
        }
    }
}
