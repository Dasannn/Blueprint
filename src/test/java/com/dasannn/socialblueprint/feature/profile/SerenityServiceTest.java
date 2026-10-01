package com.dasannn.socialblueprint.feature.profile;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.domain.*;
import com.dasannn.socialblueprint.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.assertj.core.api.Assertions.assertThat;

class SerenityServiceTest {
    @TempDir Path folder;
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private ConfigManager manager() {
        var messages = new MessageRegistry(folder.toFile(), "en", null);
        var manager = new ConfigManager(folder.resolve("config.yml").toFile(), messages, Runnable::run, null);
        manager.initialize();
        return manager;
    }

    @Test void creditSurvivesReloadQuitAndRejoinWithoutOfflineCredit() {
        var manager = manager();
        AtomicLong time = new AtomicLong();
        try (StorageEngine storage = StorageEngine.inMemory()) {
            storage.runMigrations();
            var repository = new PsychosisRepository(storage);
            var service = new SerenityService(storage, repository, manager, Clock.fixed(NOW, ZoneOffset.UTC), time::get,
                    Logger.getAnonymousLogger());
            PlayerId id = PlayerId.of(UUID.randomUUID());
            service.join(id).join();
            time.set(1000); service.tick();
            assertThat(service.creditMillis(id, 0)).isZero();
            service.activity(id);
            time.set(2000); service.tick();
            assertThat(service.creditMillis(id, 0)).isEqualTo(1000);
            service.join(id).join(); // duplicate warm-up must not reset earned time
            service.setAfk(id, true);
            service.activity(id);
            time.set(3000); service.tick();
            assertThat(service.creditMillis(id, 0)).isEqualTo(1000);
            manager.set("psychosis.serenity.ceiling", "50");
            assertThat(service.creditMillis(id, 0)).isEqualTo(1000);
            service.leave(id).join();
            time.set(1_000_000);
            service.join(id).join();
            time.set(1_001_000); service.tick();
            assertThat(service.creditMillis(id, 0)).isEqualTo(1000);
            assertThat(repository.loadStreak(id).activeMillis()).isEqualTo(1000);
            manager.set("psychosis.serenity.active-hours-to-ceiling", Double.toString(0.5 / 3600));
            service.flush().join();
            assertThat(service.creditMillis(id, 0)).isEqualTo(500);
            assertThat(repository.loadStreak(id).activeMillis()).isEqualTo(500);
            manager.set("psychosis.serenity.active-hours-to-ceiling", "100");
            assertThat(service.creditMillis(id, 0)).isEqualTo(500);
            service.shutdown();
        }
    }

    @Test void blockedStorageDoesNotBlockActivityTickOrImmediateKillReset() throws Exception {
        var manager = manager();
        AtomicLong time = new AtomicLong();
        try (StorageEngine storage = StorageEngine.inMemory()) {
            storage.runMigrations();
            var repository = new PsychosisRepository(storage);
            var service = new SerenityService(storage, repository, manager, Clock.fixed(NOW, ZoneOffset.UTC), time::get,
                    Logger.getAnonymousLogger());
            PlayerId id = PlayerId.of(UUID.randomUUID()), victim = PlayerId.of(UUID.randomUUID());
            service.join(id).join(); service.activity(id);
            time.set(1000); service.tick();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            storage.submitAsync(() -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test barrier timed out"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                time.set(2000); service.tick();
                var pendingFlush = service.flush();
                assertThat(pendingFlush.isDone()).isFalse();
                var duel = repository.saveAsync(new PsychosisEvent(id, victim, CombatContext.DUEL, NOW));
                assertThat(service.creditMillis(id, 0)).isEqualTo(2000);
                var open = repository.saveAsync(new PsychosisEvent(id, victim, CombatContext.OPEN, NOW));
                assertThat(service.creditMillis(id, 0)).isZero();
                assertThat(duel.isDone()).isFalse(); assertThat(open.isDone()).isFalse();
            } finally { release.countDown(); }
            service.flush().join();
            assertThat(repository.loadStreak(id).activeMillis()).isZero();
            service.shutdown();
        }
    }
}
