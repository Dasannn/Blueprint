package com.dasannn.socialblueprint.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RaterRevealRepositoryTest {

    private StorageEngine storageEngine;
    private RaterRevealRepository repository;

    @BeforeEach
    void setUp() {
        storageEngine = StorageEngine.inMemory();
        storageEngine.runMigrations();
        repository = new RaterRevealRepository(storageEngine);
    }

    @AfterEach
    void tearDown() {
        if (storageEngine != null) {
            storageEngine.close();
        }
    }

    @Test
    @DisplayName("T-124: Viewer starts with empty set of revealed ratings")
    void viewerStartsWithNoReveals() {
        UUID viewer = UUID.randomUUID();
        Set<Long> reveals = repository.findRevealedEventsByViewerAsync(viewer).join();

        assertThat(reveals).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("T-124: Saving a reveal persists and is queryable for that viewer")
    void saveAndQueryReveal() {
        UUID viewer = UUID.randomUUID();
        UUID rater = UUID.randomUUID();
        long eventId = 42L;
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        repository.saveRevealAsync(viewer, eventId, rater, 100.0, now).join();

        Set<Long> reveals = repository.findRevealedEventsByViewerAsync(viewer).join();
        assertThat(reveals).containsExactly(eventId);
    }

    @Test
    @DisplayName("T-124: Reveals are remembered per viewer (viewer A does not reveal for viewer B)")
    void revealsArePerViewer() {
        UUID viewerA = UUID.randomUUID();
        UUID viewerB = UUID.randomUUID();
        UUID rater = UUID.randomUUID();
        long eventId = 101L;
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        repository.saveRevealAsync(viewerA, eventId, rater, 100.0, now).join();

        Set<Long> revealsA = repository.findRevealedEventsByViewerAsync(viewerA).join();
        Set<Long> revealsB = repository.findRevealedEventsByViewerAsync(viewerB).join();

        assertThat(revealsA).containsExactly(eventId);
        assertThat(revealsB).isEmpty();
    }

    @Test
    @DisplayName("T-124: Saving same reveal multiple times is idempotent")
    void savingDuplicateRevealIsIdempotent() {
        UUID viewer = UUID.randomUUID();
        UUID rater = UUID.randomUUID();
        long eventId = 55L;
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        repository.saveRevealAsync(viewer, eventId, rater, 100.0, now).join();
        repository.saveRevealAsync(viewer, eventId, rater, 100.0, now.plusSeconds(60)).join();

        Set<Long> reveals = repository.findRevealedEventsByViewerAsync(viewer).join();
        assertThat(reveals).containsExactly(eventId);
    }

    @Test
    @DisplayName("T-124: Multiple events revealed by same viewer are all remembered")
    void multipleEventsRevealedBySameViewer() {
        UUID viewer = UUID.randomUUID();
        Instant now = Instant.now();

        repository.saveRevealAsync(viewer, 1L, UUID.randomUUID(), 100.0, now).join();
        repository.saveRevealAsync(viewer, 2L, UUID.randomUUID(), 100.0, now).join();
        repository.saveRevealAsync(viewer, 3L, UUID.randomUUID(), 100.0, now).join();

        Set<Long> reveals = repository.findRevealedEventsByViewerAsync(viewer).join();
        assertThat(reveals).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @DisplayName("T-124: Persisted across repository instances sharing database")
    void persistedAcrossRepositoryInstances() {
        UUID viewer = UUID.randomUUID();
        long eventId = 999L;
        Instant now = Instant.now();

        repository.saveRevealAsync(viewer, eventId, UUID.randomUUID(), 100.0, now).join();

        // Create a new repository instance pointing to the same storage engine
        RaterRevealRepository freshRepo = new RaterRevealRepository(storageEngine);
        Set<Long> reveals = freshRepo.findRevealedEventsByViewerAsync(viewer).join();

        assertThat(reveals).containsExactly(eventId);
    }
}
