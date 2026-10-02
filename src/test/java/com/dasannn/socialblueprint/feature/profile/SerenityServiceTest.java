package com.dasannn.socialblueprint.feature.profile;

import com.dasannn.socialblueprint.config.*;
import com.dasannn.socialblueprint.domain.*;
import com.dasannn.socialblueprint.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.assertj.core.api.Assertions.*;

class SerenityServiceTest {
    @TempDir Path folder;
    private static final Instant NOW=Instant.parse("2026-10-01T00:00:00Z");
    private ConfigManager manager() {
        var messages=new MessageRegistry(folder.toFile(),"en",null);
        var manager=new ConfigManager(folder.resolve("config.yml").toFile(),messages,Runnable::run,null);
        manager.initialize();return manager;
    }
    @Test void activitySurvivesReloadButNeverCreditsMindAndOfflineTimeEarnsNothing() {
        var manager=manager();var time=new AtomicLong();
        try(var storage=StorageEngine.inMemory()) {
            storage.runMigrations();var repository=new PsychosisRepository(storage);var mind=new MindRepository(storage);
            var service=new SerenityService(storage,repository,manager,Clock.fixed(NOW,ZoneOffset.UTC),time::get,Logger.getAnonymousLogger());
            PlayerId player=PlayerId.of(UUID.randomUUID());service.join(player).join();
            time.set(1000);service.tick();assertThat(service.creditMillis(player,0)).isZero();
            service.activity(player);time.set(2000);service.tick();assertThat(service.creditMillis(player,0)).isEqualTo(1000);
            service.join(player).join();service.setAfk(player,true);service.activity(player);time.set(3000);service.tick();
            assertThat(service.creditMillis(player,0)).isEqualTo(1000);
            manager.set("psychosis.serenity.idle-timeout-seconds","100");
            assertThat(service.creditMillis(player,0)).isEqualTo(1000);
            service.flush().join();assertThat(mind.value(player)).isZero();assertThat(mind.events(player)).isEmpty();
            assertThat(repository.loadStreak(player).activeMillis()).isZero();
            service.leave(player).join();time.set(1_000_000);service.join(player).join();time.set(1_001_000);service.tick();
            assertThat(service.creditMillis(player,0)).isZero();assertThat(mind.value(player)).isZero();
            service.shutdown();
        }
    }
    @Test void blockedStorageDoesNotBlockActivityAndKillOnlyAppliesAfterCommit() throws Exception {
        var manager=manager();var time=new AtomicLong();
        try(var storage=StorageEngine.inMemory()) {
            storage.runMigrations();var repository=new PsychosisRepository(storage);var mind=new MindRepository(storage);
            var service=new SerenityService(storage,repository,manager,Clock.fixed(NOW,ZoneOffset.UTC),time::get,Logger.getAnonymousLogger());
            PlayerId player=PlayerId.of(UUID.randomUUID()),victim=PlayerId.of(UUID.randomUUID());
            mind.applyAsync(player,MindInput.SLEEP,new MindInputConfig(true,30,1,2),"test",NOW).join();
            service.join(player).join();service.activity(player);time.set(1000);service.tick();
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            storage.submitAsync(() -> {entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Barrier timed out");}
                catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}});
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            CompletableFuture<PsychosisEvent> open;
            try {
                time.set(2000);service.tick();assertThat(service.creditMillis(player,0)).isEqualTo(2000);
                assertThat(service.flush().isDone()).isTrue();
                var duel=repository.saveAsync(new PsychosisEvent(player,victim,CombatContext.DUEL,NOW));
                open=repository.saveAsync(new PsychosisEvent(player,victim,CombatContext.OPEN,NOW));
                assertThat(duel.isDone()).isFalse();assertThat(open.isDone()).isFalse();
                assertThat(service.creditMillis(player,0)).isEqualTo(2000);
            } finally {release.countDown();}
            open.join();assertThat(mind.value(player)).isEqualTo(5);assertThat(mind.events(player)).hasSize(2);
            service.shutdown();
        }
    }
}
