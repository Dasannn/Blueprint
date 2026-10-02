package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(10)
class MindInputsStorageTest {
    @TempDir Path folder;
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private static PlayerId player() { return PlayerId.of(UUID.randomUUID()); }
    private static void profile(StorageEngine engine, PlayerId player) {
        new ProfileRepository(engine).save(PlayerProfile.create(player, "Player", NOW));
    }
    @Test void eachRollingCapHasAnExclusiveLowerBoundaryAndResetDoesNotRefundIt() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var mind = new MindRepository(engine);
            for (MindInput kind : List.of(MindInput.CLEAN_DAY, MindInput.SLEEP)) {
                PlayerId player = player();
                for (int i = 0; i < kind.defaults().cap(); i++)
                    assertThat(mind.applyAsync(player, kind, kind.defaults(), "test", NOW).join().enabled()).isTrue();
                assertThat(mind.applyAsync(player, kind, kind.defaults(), "test", NOW).join().enabled()).isFalse();
                mind.resetAsync(player, PlayerId.CONSOLE, NOW.plusSeconds(1)).join();
                assertThat(mind.applyAsync(player, kind, kind.defaults(), "test", NOW.plus(Duration.ofHours(24)).minusNanos(1)).join().enabled()).isFalse();
                assertThat(mind.events(player)).hasSize(kind.defaults().cap() + 1);
                assertThat(mind.applyAsync(player, kind, kind.defaults(), "test", NOW.plus(Duration.ofHours(24))).join().enabled()).isTrue();
            }
        }
    }
    @Test void allFivePeacefulKindsShareOneCapButSleepAndCleanDayAreIndependent() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var mind = new MindRepository(engine); PlayerId player = player();
            var kinds = List.of(MindInput.FISHING, MindInput.BREEDING, MindInput.FEEDING, MindInput.PLANTING, MindInput.HARVESTING);
            for (int i = 0; i < 25; i++) {
                MindInput kind = kinds.get(i % kinds.size());
                assertThat(mind.applyAsync(player, kind, kind.defaults(), "test", NOW).join().enabled()).isTrue();
            }
            for (MindInput kind : kinds) assertThat(mind.applyAsync(player, kind, kind.defaults(), "test", NOW).join().enabled()).isFalse();
            assertThat(mind.events(player)).hasSize(25);
            assertThat(mind.applyAsync(player, MindInput.SLEEP, MindInput.SLEEP.defaults(), "test", NOW).join().enabled()).isTrue();
            assertThat(mind.applyAsync(player, MindInput.CLEAN_DAY, MindInput.CLEAN_DAY.defaults(), "test", NOW).join().enabled()).isTrue();
            mind.resetAllAsync(PlayerId.CONSOLE, NOW.plusSeconds(1)).join();
            assertThat(mind.applyAsync(player, MindInput.FISHING, MindInput.FISHING.defaults(), "test", NOW.plusSeconds(2)).join().enabled()).isFalse();
            assertThat(mind.applyAsync(player(), MindInput.FISHING, MindInput.FISHING.defaults(), "test", NOW).join().enabled()).isTrue();
        }
    }
    @Test void zeroCapDisabledInputAndFullMagnitudeDoNotRefundConsumedEvents() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var mind = new MindRepository(engine); PlayerId player = player();
            assertThat(mind.applyAsync(player, MindInput.FISHING, new MindInputConfig(true, 1, 1, 0), "test", NOW).join().enabled()).isFalse();
            assertThat(mind.applyAsync(player, MindInput.SLEEP, new MindInputConfig(false, 1, 1, 2), "test", NOW).join().enabled()).isFalse();
            assertThat(mind.events(player)).isEmpty();
            assertThat(mind.applyAsync(player, MindInput.SLEEP, new MindInputConfig(true, 100, 1, 2), "test", NOW).join().after()).isEqualTo(100);
            assertThat(mind.applyAsync(player, MindInput.SLEEP, MindInput.SLEEP.defaults(), "test", NOW).join().appliedDelta()).isZero();
            assertThat(mind.applyAsync(player, MindInput.SLEEP, MindInput.SLEEP.defaults(), "test", NOW).join().enabled()).isFalse();
            for (int i = 0; i < 3; i++) assertThat(mind.applyAsync(player, MindInput.DEATH, MindInput.DEATH.defaults(), "test", NOW).join().enabled()).isTrue();
        }
    }
    @Test void concurrentAppliesCannotExceedAnyCap() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var mind = new MindRepository(engine);
            ExecutorService callers = Executors.newFixedThreadPool(8);
            try {
                for (MindInput group : List.of(MindInput.CLEAN_DAY, MindInput.SLEEP, MindInput.FISHING)) {
                    PlayerId player = player();
                    var kinds = group.peaceful() ? List.of(MindInput.FISHING, MindInput.BREEDING, MindInput.FEEDING, MindInput.PLANTING, MindInput.HARVESTING) : List.of(group);
                    List<CompletableFuture<MindState.Result>> applies = new ArrayList<>();
                    for (int i = 0; i < 60; i++) {
                        MindInput kind = kinds.get(i % kinds.size());
                        applies.add(CompletableFuture.supplyAsync(() -> mind.applyAsync(player, kind, kind.defaults(), "race", NOW), callers).thenCompose(future -> future));
                    }
                    CompletableFuture.allOf(applies.toArray(CompletableFuture[]::new)).join();
                    assertThat(applies.stream().filter(future -> future.join().enabled()).count()).isEqualTo(group.defaults().cap());
                    assertThat(mind.events(player)).hasSize(group.defaults().cap());
                }
            } finally { callers.shutdownNow(); }
        }
    }
    @Test void activeMinutesPersistThroughRestartAndOfflineTimeAddsNothing() {
        PlayerId player = player(); String url = "jdbc:sqlite:" + folder.resolve("active.db");
        try (var engine = StorageEngine.open(url)) {
            engine.runMigrations(); profile(engine, player); var mind = new MindRepository(engine);
            assertThat(mind.accountActiveAsync(player, 900_000, 30, MindInput.CLEAN_DAY.defaults(), NOW.plusSeconds(900)).join().enabled()).isFalse();
        }
        try (var engine = StorageEngine.open(url)) {
            engine.runMigrations(); var mind = new MindRepository(engine);
            assertThat(mind.accountActiveAsync(player, 0, 30, MindInput.CLEAN_DAY.defaults(), NOW.plus(Duration.ofDays(3))).join().enabled()).isFalse();
            assertThat(mind.accountActiveAsync(player, 900_000, 30, MindInput.CLEAN_DAY.defaults(), NOW.plus(Duration.ofDays(3)).plusSeconds(900)).join().enabled()).isTrue();
            assertThat(mind.events(player)).hasSize(1);
            assertThat(mind.accountActiveAsync(player, 0, 30, MindInput.CLEAN_DAY.defaults(), NOW.plus(Duration.ofDays(4)).plusSeconds(900)).join().enabled()).isFalse();
        }
    }
    @Test void badActionRestartsBothClockAndMinutesAndClipsAnInterveningInterval() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); PlayerId player = player(); profile(engine, player); var mind = new MindRepository(engine);
            mind.accountActiveAsync(player, 1_800_000, 30, MindInput.CLEAN_DAY.defaults(), NOW.plusSeconds(1800)).join();
            mind.applyAsync(player, MindInput.DEATH, MindInput.DEATH.defaults(), "test", NOW.plusSeconds(2000)).join();
            mind.accountActiveAsync(player, 1000, 30, MindInput.CLEAN_DAY.defaults(), NOW.plusSeconds(2000).plusMillis(250)).join();
            double millis = engine.execute(conn -> {
                try (var ps = conn.prepareStatement("SELECT active_millis FROM mind_activity WHERE player_uuid = ?")) {
                    ps.setString(1, player.toString()); try (var rs = ps.executeQuery()) { return rs.next() ? rs.getDouble(1) : -1; }
                }
            });
            assertThat(millis).isEqualTo(250);
            assertThat(mind.accountActiveAsync(player, 0, 30, MindInput.CLEAN_DAY.defaults(), NOW.plusSeconds(2000).plus(Duration.ofHours(24))).join().enabled()).isFalse();
            assertThat(mind.accountActiveAsync(player, 1_799_750, 30, MindInput.CLEAN_DAY.defaults(), NOW.plusSeconds(2000).plus(Duration.ofHours(24))).join().enabled()).isTrue();
            assertThat(mind.events(player).stream().map(MindEvent::kind)).containsExactly("death", "clean-day");
        }
    }
    @Test void resetAndDisabledBadInputsHandleActiveCounterCorrectly() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); PlayerId player = player(); profile(engine, player); var mind = new MindRepository(engine);
            mind.accountActiveAsync(player, 1_800_000, 30, MindInput.CLEAN_DAY.defaults(), NOW.plusSeconds(1800)).join();
            mind.applyAsync(player, MindInput.DEATH, new MindInputConfig(false, 10, 6, 0), "test", NOW.plusSeconds(2000)).join();
            assertThat(mind.accountActiveAsync(player, 0, 30, MindInput.CLEAN_DAY.defaults(), NOW.plus(Duration.ofHours(24))).join().enabled()).isTrue();
            mind.accountActiveAsync(player, 1_800_000, 30, MindInput.CLEAN_DAY.defaults(), NOW.plus(Duration.ofHours(24)).plusSeconds(1800)).join();
            mind.resetAsync(player, PlayerId.CONSOLE, NOW.plus(Duration.ofHours(25))).join();
            assertThat(mind.accountActiveAsync(player, 0, 30, MindInput.CLEAN_DAY.defaults(), NOW.plus(Duration.ofHours(49))).join().enabled()).isFalse();
        }
    }
    @Test void activityThatExpiredBeforeABadActionIsNotMovedIntoTheNewSpan() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); PlayerId player = player(); profile(engine, player); var mind = new MindRepository(engine);
            mind.applyAsync(player, MindInput.DEATH, MindInput.DEATH.defaults(), "test", NOW.plusMillis(750)).join();
            mind.accountActiveAsync(player, 500, NOW.plusMillis(500), 30, MindInput.CLEAN_DAY.defaults(), NOW.plusMillis(1000)).join();
            double credit = engine.execute(conn -> {
                try (var ps = conn.prepareStatement("SELECT active_millis FROM mind_activity WHERE player_uuid = ?")) {
                    ps.setString(1, player.toString()); try (var rs = ps.executeQuery()) { return rs.next() ? rs.getDouble(1) : -1; }
                }
            });
            assertThat(credit).isZero();
        }
    }
}
