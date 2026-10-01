package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.*;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import org.bukkit.SoundCategory;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SerenityEffectsTest {
    @Test void onlySerenityAtTheConfiguredMagnitudeCanDeliver() {
        var rule = new SerenityEffectsConfig.Rule(true, 25, 10, 2);
        for (PsychosisLevel level : PsychosisLevel.values()) {
            assertThat(SereneEpisode.allows(level, 25, rule)).isEqualTo(level == PsychosisLevel.SERENITY);
        }
        assertThat(SereneEpisode.allows(PsychosisLevel.SERENITY, 24.999, rule)).isFalse();
        assertThat(SereneEpisode.allows(PsychosisLevel.SERENITY, Double.NaN, rule)).isFalse();
        assertThat(SereneEpisode.allows(PsychosisLevel.SERENITY, 100,
                new SerenityEffectsConfig.Rule(false, 1, 10, 2))).isFalse();
        assertThat(SereneEpisode.allows(PsychosisLevel.SERENITY, 100,
                new SerenityEffectsConfig.Rule(true, 1, 10, 0))).isFalse();
        for (var ruleMad : PresentationConfig.defaults().rules().values()) {
            assertThat(ruleMad.allows(PsychosisLevel.SERENITY)).isFalse();
            assertThat(ruleMad.allows(PsychosisLevel.NEUTRAL)).isFalse();
        }
    }

    private SereneEpisode.Candidate candidate(UUID id, boolean online, boolean world, boolean visible, boolean vanished, double distance) {
        return new SereneEpisode.Candidate(id, online, world, visible, vanished, distance);
    }

    @Test void dawnIsPrivateAndAllOtherEffectsShareOnlyVisibleLocalObservers() {
        UUID subject = UUID.randomUUID(), visible = UUID.randomUUID();
        List<SereneEpisode.Candidate> candidates = List.of(
                candidate(visible, true, true, true, false, 256),
                candidate(UUID.randomUUID(), false, true, true, false, 1),
                candidate(UUID.randomUUID(), true, false, true, false, 1),
                candidate(UUID.randomUUID(), true, true, false, false, 1),
                candidate(UUID.randomUUID(), true, true, true, true, 1),
                candidate(UUID.randomUUID(), true, true, true, false, 256.01),
                candidate(UUID.randomUUID(), true, true, true, false, Double.NaN));
        assertThat(SereneEpisode.audience(subject, true, candidates, 16)).containsExactly(subject);
        assertThat(SereneEpisode.audience(subject, false, candidates, 16)).containsExactlyInAnyOrder(subject, visible);
        for (var departed : List.of(candidate(visible, true, true, true, false, 257),
                candidate(visible, true, false, true, false, 1), candidate(visible, true, true, false, false, 1),
                candidate(visible, true, true, true, true, 1), candidate(visible, false, true, true, false, 1))) {
            Set<UUID> current = SereneEpisode.audience(subject, false, List.of(departed), 16);
            assertThat(SereneEpisode.departed(Set.of(subject, visible), current)).containsExactly(visible);
        }
    }

    @Test void selectableAnimalModelsCannotEnterInteractionOrMovementSpace() {
        var animal = new SereneEpisode.Bounds(2.7, 0, -.3, 3.3, .85, .3);
        assertThat(animal.outsideReach(0, 1.62, 0, 4)).isFalse();
        assertThat(animal.outsideReach(-5, 1.62, 0, 4)).isTrue();
        assertThat(animal.outsideReach(-5, 1.62, 0, Double.NaN)).isFalse();
        assertThat(animal.separatedFrom(new SereneEpisode.Bounds(2.6, 0, -.3, 3.2, 1.8, .3))).isFalse();
        assertThat(animal.separatedFrom(new SereneEpisode.Bounds(-.3, 0, -.3, .3, 1.8, .3))).isTrue();
        var face = new SereneEpisode.Bounds(4, 0, 0, 5, 1, 1);
        assertThat(face.outsideReach(0, 0, 0, 4)).isFalse(); // exact boundary is unsafe
        assertThat(face.outsideReach(0, 0, 0, 3.99)).isTrue();
    }

    @Test void subjectLimitsSurviveObserverChurnReloadAndDirectionChanges() {
        var config = SerenityEffectsConfig.defaults();
        var rule = new SerenityEffectsConfig.Rule(true, 1, 100, 1);
        var state = new PlayerEffectState();
        long now = 10_000;
        long reservation = SereneEpisode.reservationTicks("dawn", config, SoundsConfigSection.defaults());
        assertThat(state.canFireSerene("dawn", rule, now)).isTrue();
        state.recordSerene("dawn", now, reservation);
        assertThat(state.changeDirection(PsychosisLevel.SERENITY)).isTrue();
        assertThat(state.changeDirection(PsychosisLevel.HIGH)).isTrue();
        assertThat(state.changeDirection(PsychosisLevel.EXTREME)).isFalse();
        assertThat(state.changeDirection(PsychosisLevel.NEUTRAL)).isTrue();
        assertThat(state.changeDirection(PsychosisLevel.SERENITY)).isTrue();
        var reloaded = new SerenityEffectsConfig.Rule(true, 1, 100, 1);
        assertThat(state.canFireSerene("dawn", reloaded, now + reservation * 50)).isFalse();
        assertThat(state.canFireSerene("particles", reloaded, now)).isTrue();
        assertThat(state.canStartEpisode(now + reservation * 50 - 1)).isFalse();
        assertThat(state.canStartEpisode(now + reservation * 50)).isTrue();
        var raisedCap = new SerenityEffectsConfig.Rule(true, 1, 100, 2);
        assertThat(state.canFireSerene("dawn", raisedCap, now + 4_999)).isFalse();
        assertThat(state.canFireSerene("dawn", raisedCap, now + 5_000)).isTrue();
    }

    @Test void silenceStartsAfterRestorationParticleLifetimeAndLastAudibleTail() {
        var config = SerenityEffectsConfig.defaults();
        var slot = new SoundSlotConfig(List.of(
                new SoundLayerConfig("minecraft:block.bell.use", 1, 1, SoundCategory.AMBIENT, 40)));
        var sounds = new SoundsConfigSection(Map.of(config.sounds().slot(), slot));
        assertThat(SereneEpisode.durationTicks("source-less-sounds", config, sounds)).isEqualTo(100);
        assertThat(SereneEpisode.durationTicks("particles", config, sounds)).isEqualTo(72);
        for (String effect : SerenityEffectsConfig.EFFECTS) {
            long duration = SereneEpisode.durationTicks(effect, config, sounds);
            long reservation = SereneEpisode.reservationTicks(effect, config, sounds);
            var state = new PlayerEffectState();
            state.recordSerene(effect, 0, reservation);
            assertThat(state.canStartEpisode(duration * 50)).isFalse();
            assertThat(reservation - duration).isEqualTo(Math.max(config.quietTicks(), config.intervalTicks()));
        }
    }

    record Scheduled(Runnable action, boolean[] cancelled) {}

    @Test void departedViewerCleanupLeavesSubjectDeliveryAndBudgetIntact() {
        UUID subject = UUID.randomUUID(), observer = UUID.randomUUID();
        var registry = new AmbientEntityRegistry();
        List<Scheduled> scheduled = new ArrayList<>();
        var dispatcher = new AmbientEffectDispatcher(null,
                MessageRegistry.fromMaps(Map.of(), Map.of(), "en", null), null,
                new FakeSilverfishService(null, registry, null), (action, delay) -> {
                    boolean[] cancelled = {false};
                    scheduled.add(new Scheduled(action, cancelled));
                    return () -> cancelled[0] = true;
                });
        var state = new PlayerEffectState();
        var rule = new SerenityEffectsConfig.Rule(true, 1, 20, 1);
        state.recordSerene("apparition", 10_000, 200);
        AtomicInteger ownCleanup = new AtomicInteger(), observerCleanup = new AtomicInteger();
        Map<UUID, Runnable> viewers = new HashMap<>();
        viewers.put(subject, ownCleanup::incrementAndGet);
        viewers.put(observer, observerCleanup::incrementAndGet);
        assertThat(dispatcher.startSereneAudience(subject, viewers, 60)).isTrue();
        dispatcher.removeSereneViewer(observer);
        assertThat(observerCleanup).hasValue(1);
        assertThat(ownCleanup).hasValue(0);
        assertThat(viewers).containsOnlyKeys(subject);
        assertThat(state.canFireSerene("apparition", rule, 50_000)).isFalse();
        assertThat(state.canStartEpisode(19_999)).isFalse();
        dispatcher.cancelPending(subject);
        assertThat(ownCleanup).hasValue(1);
        scheduled.getFirst().action().run();
        assertThat(ownCleanup).hasValue(1);
        assertThat(observerCleanup).hasValue(1);
    }

    @Test void directionChangesCancelPendingDeliveryAndRestoreWithoutResettingLimits() {
        UUID subject = UUID.randomUUID();
        var registry = new AmbientEntityRegistry();
        List<Scheduled> scheduled = new ArrayList<>();
        var dispatcher = new AmbientEffectDispatcher(null,
                MessageRegistry.fromMaps(Map.of(), Map.of(), "en", null), null,
                new FakeSilverfishService(null, registry, null), (action, delay) -> {
                    boolean[] cancelled = {false};
                    scheduled.add(new Scheduled(action, cancelled));
                    return () -> cancelled[0] = true;
                });
        AtomicInteger restored = new AtomicInteger();
        var entry = dispatcher.startPresentation(subject, AmbientEffectType.SKY, 60, restored::incrementAndGet);
        dispatcher.reserveEpisode(subject, 200);
        AtomicBoolean eligible = new AtomicBoolean(true);
        assertThat(dispatcher.guardDirection(subject, eligible::get, 60)).isTrue();
        eligible.set(false);
        // Even a delivery/restoration callback queued before the direction monitor must cancel first.
        scheduled.getFirst().action().run();
        assertThat(restored).hasValue(1);
        assertThat(entry.ended()).isTrue();
        assertThat(dispatcher.hasPending(subject)).isFalse();
        assertThat(scheduled).allSatisfy(task -> assertThat(task.cancelled()[0]).isTrue());
        registry.cleanAll();
        assertThat(restored).hasValue(1);
    }

    @Test void serenePathsHaveNoUiMetricMoneyOrMechanicalWrites() throws Exception {
        String dispatcher = Files.readString(Path.of("src/main/java/com/dasannn/socialblueprint/feature/effects/AmbientEffectDispatcher.java"));
        String serene = dispatcher.substring(dispatcher.indexOf("public boolean dispatchSerene"), dispatcher.indexOf("ActivePresentationEntry startPresentation"));
        assertThat(serene).doesNotContain("sendMessage", "sendTitle", "showTitle", "sendActionBar", "dispatchScreen", "dispatchBar");
        for (String file : List.of("SereneEpisode.java", "AmbientEffectDispatcher.java", "PrivateGhost.java")) {
            String source = Files.readString(Path.of("src/main/java/com/dasannn/socialblueprint/feature/effects", file));
            assertThat(source).doesNotContain("ReputationRepository", "PsychosisRepository", "Confidence", "Economy",
                    "depositPlayer", "withdrawPlayer", ".setHealth(", ".setAbsorptionAmount(", ".addPotionEffect(",
                    ".setVelocity(", ".setWalkSpeed(", ".spawnEntity(", ".addFreshEntity(", ".setTime(", ".setStorm(");
        }
    }
}
