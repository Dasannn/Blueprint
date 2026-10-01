package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class SerenityStorageTest {
    @TempDir Path folder;
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Test void upgradeStartsAtZeroAndRestartPreservesOnlyCredit() {
        PlayerId id = PlayerId.of(UUID.randomUUID());
        String url = "jdbc:sqlite:" + folder.resolve("serenity.db");
        try (StorageEngine engine = StorageEngine.open(url)) {
            engine.runMigrations(new MigrationRunner(List.of(new Migration_1_InitialSchema(), new Migration_2_RaterReveal(),
                    new Migration_3_KillPenaltyClaim(), new Migration_4_PendingCompensation())));
            new ProfileRepository(engine).save(PlayerProfile.create(id, "Peaceful", NOW.minus(Duration.ofDays(365))));
            assertThat(engine.runMigrations()).isEqualTo(5);
            var repository = new PsychosisRepository(engine);
            assertThat(repository.loadStreak(id).activeMillis()).isZero();
            repository.saveStreakAsync(id, 25 * 3_600_000d, 0).join();
            assertThat(new ReputationRepository(engine, new StatusCache()).findByTarget(id)).isEmpty();
        }
        try (StorageEngine engine = StorageEngine.open(url)) {
            engine.runMigrations();
            var repository = new PsychosisRepository(engine);
            var streak = repository.loadStreak(id);
            assertThat(streak.activeMillis()).isEqualTo(25 * 3_600_000d);
            assertThat(SerenityConfig.DEFAULT.magnitude(streak.activeMillis())).isEqualTo(43.75);
            repository.clampStreaksAsync(10 * 3_600_000d).join();
            assertThat(repository.loadStreak(id).activeMillis()).isEqualTo(10 * 3_600_000d);
            repository.clampStreaksAsync(100 * 3_600_000d).join();
            assertThat(repository.loadStreak(id).activeMillis()).isEqualTo(10 * 3_600_000d);
        }
    }

    @Test void duelPreservesCreditOpenKillAndStaleFlushCannotRestoreIt() {
        try (StorageEngine engine = StorageEngine.inMemory()) {
            engine.runMigrations();
            var repository = new PsychosisRepository(engine);
            PlayerId id = PlayerId.of(UUID.randomUUID()), victim = PlayerId.of(UUID.randomUUID());
            repository.saveStreakAsync(id, 1000, 0).join();
            repository.saveAsync(new PsychosisEvent(id, victim, CombatContext.DUEL, NOW)).join();
            assertThat(repository.loadStreak(id).activeMillis()).isEqualTo(1000);
            repository.saveAsync(new PsychosisEvent(id, victim, CombatContext.OPEN, NOW)).join();
            repository.saveStreakAsync(id, 1000, 0).join();
            assertThat(repository.loadStreak(id).activeMillis()).isZero();
            assertThat(repository.loadStreak(id).lastKillAt()).isEqualTo(NOW);
        }
    }

    @Test void suppressedIndependentPenaltyStillResetsCreditWithoutWritingStatusOrConfidence() {
        try (StorageEngine engine = StorageEngine.inMemory()) {
            engine.runMigrations();
            var psychosis = new PsychosisRepository(engine);
            var reputation = new ReputationRepository(engine, new StatusCache());
            PlayerId id = PlayerId.of(UUID.randomUUID()), victim = PlayerId.of(UUID.randomUUID());
            var enabled = new KillPenaltySettings(true, -1, Duration.ofHours(1), Duration.ofDays(7), 1, Set.of());
            var first = reputation.executeKillPenaltyAsync(id, victim, "world", NOW, enabled, psychosis).join();
            assertThat(first.wasPenaltyCharged()).isTrue();
            for (var settings : List.of(enabled,
                    new KillPenaltySettings(true, -1, Duration.ZERO, Duration.ofDays(7), 1, Set.of()),
                    new KillPenaltySettings(true, 0, Duration.ZERO, Duration.ofDays(7), 10, Set.of()),
                    new KillPenaltySettings(false, -1, Duration.ZERO, Duration.ofDays(7), 10, Set.of()),
                    new KillPenaltySettings(true, -1, Duration.ZERO, Duration.ofDays(7), 10, Set.of("world")))) {
                var before = reputation.findByTarget(id);
                var streak = psychosis.loadStreak(id);
                psychosis.saveStreakAsync(id, 1000, streak.lastKillId()).join();
                var result = reputation.executeKillPenaltyAsync(id, victim, "world", NOW.plusSeconds(1), settings, psychosis).join();
                assertThat(result.wasPenaltyCharged()).isFalse();
                assertThat(psychosis.loadStreak(id).activeMillis()).isZero();
                assertThat(reputation.findByTarget(id)).isEqualTo(before);
                assertThat(new ConfidenceCalculator(ConfidenceConfig.defaults()).calculate(reputation.findByTarget(id), NOW.plusSeconds(1)))
                        .isEqualTo(new ConfidenceCalculator(ConfidenceConfig.defaults()).calculate(before, NOW.plusSeconds(1)));
                assertThat(before).allMatch(event -> event.actor() == null && event.cost() == 0);
            }
        }
    }
}
