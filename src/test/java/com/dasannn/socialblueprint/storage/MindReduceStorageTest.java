package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class MindReduceStorageTest {
    @Test void offlineReductionIsLoggedAuditedAndInvalidatesAfterCommit() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations();
            var mind = new MindRepository(engine);
            var target = PlayerId.of(UUID.randomUUID());
            var now = Instant.parse("2026-10-03T12:00:00Z");
            mind.setAsync(target, -50, PlayerId.CONSOLE, "Console", now).join();
            var invalidations = new AtomicInteger();
            mind.addInvalidationListener(id -> {
                assertThat(id).isEqualTo(target);
                assertThat(mind.value(id)).isEqualTo(-30);
                invalidations.incrementAndGet();
            });
            var result = mind.reduceAsync(target, 40, PlayerId.CONSOLE, "Potion", now.plusSeconds(1)).join();
            assertThat(result.before()).isEqualTo(-50);
            assertThat(result.after()).isEqualTo(-30);
            var event = mind.events(target).getLast();
            assertThat(event.kind()).isEqualTo("admin-reduce");
            assertThat(event.actor()).isEqualTo(PlayerId.CONSOLE);
            assertThat(event.source()).isEqualTo("Potion");
            assertThat(event.requestedDelta()).isEqualTo(20);
            assertThat(event.appliedDelta()).isEqualTo(20);
            assertThat(event.before()).isEqualTo(-50);
            assertThat(event.after()).isEqualTo(-30);
            assertThat(new AuditRepository(engine).findByTarget(target)).hasSize(2);
            assertThat(invalidations.get()).isEqualTo(1);
        }
    }
    @Test void neutralAndSerenityDoNotWriteReductionEvents() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var mind = new MindRepository(engine);
            var target = PlayerId.of(UUID.randomUUID()); var now = Instant.parse("2026-10-03T12:00:00Z");
            assertThat(mind.reduceAsync(target, 100, PlayerId.CONSOLE, "Console", now).join().enabled()).isFalse();
            assertThat(mind.events(target)).isEmpty();
            mind.setAsync(target, 50, PlayerId.CONSOLE, "Console", now).join();
            assertThat(mind.reduceAsync(target, 40, PlayerId.CONSOLE, "Console", now).join().enabled()).isFalse();
            assertThat(mind.value(target)).isEqualTo(50);
            assertThat(mind.events(target)).hasSize(1);
            mind.setAsync(target, -50, PlayerId.CONSOLE, "Console", now).join();
            mind.reduceAsync(target, 100, PlayerId.CONSOLE, "Console", now).join();
            assertThat(mind.value(target)).isZero();
        }
    }
}
