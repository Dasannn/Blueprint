package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.duel.DuelParticipant;
import com.dasannn.socialblueprint.domain.duel.DuelRecord;
import com.dasannn.socialblueprint.domain.duel.DuelState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class DuelRepositoryTest {

    private StorageEngine storage;
    private DuelRepository repository;
    private final Instant baseTime = Instant.parse("2026-09-30T12:00:00Z");

    @BeforeEach
    void setUp() {
        storage = StorageEngine.inMemory();
        storage.runMigrations();
        repository = new DuelRepository(storage);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    @DisplayName("T-018: DuelRepository persists and retrieves duel record with participants")
    void saveAndFindById() {
        PlayerId p1 = PlayerId.of(UUID.randomUUID());
        PlayerId p2 = PlayerId.of(UUID.randomUUID());
        List<DuelParticipant> participants = List.of(
                new DuelParticipant(p1, "team_1"),
                new DuelParticipant(p2, "team_2")
        );

        DuelRecord record = new DuelRecord("duel-abc", DuelState.ACTIVE, baseTime, participants);
        DuelRecord saved = repository.save(record);
        assertThat(saved).isEqualTo(record);

        Optional<DuelRecord> loaded = repository.findById("duel-abc");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().id()).isEqualTo("duel-abc");
        assertThat(loaded.get().state()).isEqualTo(DuelState.ACTIVE);
        assertThat(loaded.get().createdAt()).isEqualTo(baseTime);
        assertThat(loaded.get().endedAt()).isNull();
        assertThat(loaded.get().participants()).containsExactlyInAnyOrderElementsOf(participants);
    }

    @Test
    @DisplayName("T-018: DuelRepository updates duel state and ended_at timestamp")
    void updateStateAndEndedAt() {
        PlayerId p1 = PlayerId.of(UUID.randomUUID());
        PlayerId p2 = PlayerId.of(UUID.randomUUID());
        DuelRecord record = new DuelRecord("duel-state-test", DuelState.ACTIVE, baseTime, List.of(
                new DuelParticipant(p1, "side_1"),
                new DuelParticipant(p2, "side_2")
        ));
        repository.save(record);

        Instant endTime = baseTime.plusSeconds(120);
        repository.updateState("duel-state-test", DuelState.ENDED, endTime);

        Optional<DuelRecord> loaded = repository.findById("duel-state-test");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().state()).isEqualTo(DuelState.ENDED);
        assertThat(loaded.get().endedAt()).isEqualTo(endTime);
    }

    @Test
    @DisplayName("T-018: DuelRepository findActiveDuels returns only active duels")
    void findActiveDuels() {
        PlayerId p1 = PlayerId.of(UUID.randomUUID());
        PlayerId p2 = PlayerId.of(UUID.randomUUID());

        DuelRecord active1 = new DuelRecord("active-1", DuelState.ACTIVE, baseTime, List.of(
                new DuelParticipant(p1, "side_1"),
                new DuelParticipant(p2, "side_2")
        ));
        DuelRecord active2 = new DuelRecord("active-2", DuelState.ACTIVE, baseTime.plusSeconds(10), List.of(
                new DuelParticipant(p1, "side_1"),
                new DuelParticipant(p2, "side_2")
        ));
        DuelRecord ended = new DuelRecord("ended-1", DuelState.ENDED, baseTime, baseTime.plusSeconds(30), List.of(
                new DuelParticipant(p1, "side_1"),
                new DuelParticipant(p2, "side_2")
        ));

        repository.save(active1);
        repository.save(active2);
        repository.save(ended);

        List<DuelRecord> activeDuels = repository.findActiveDuels();
        assertThat(activeDuels).hasSize(2);
        assertThat(activeDuels).extracting(DuelRecord::id).containsExactlyInAnyOrder("active-1", "active-2");
    }

    @Test
    @DisplayName("DoD 4 / T-060: cleanupStaleDuelsOnStartup marks all lingering active duels as ended, preventing state leak")
    void cleanupStaleDuelsOnStartupPreventsLeak() {
        PlayerId p1 = PlayerId.of(UUID.randomUUID());
        PlayerId p2 = PlayerId.of(UUID.randomUUID());

        // Simulate 2 duels left active when server abruptly crashed or restarted
        DuelRecord leaked1 = new DuelRecord("leaked-1", DuelState.ACTIVE, baseTime.minusSeconds(300), List.of(
                new DuelParticipant(p1, "s1"),
                new DuelParticipant(p2, "s2")
        ));
        DuelRecord leaked2 = new DuelRecord("leaked-2", DuelState.ACTIVE, baseTime.minusSeconds(200), List.of(
                new DuelParticipant(p1, "s1"),
                new DuelParticipant(p2, "s2")
        ));
        // And one duel that was legitimately ended
        Instant legitimateEnd = baseTime.minusSeconds(100);
        DuelRecord normalEnded = new DuelRecord("normal-ended", DuelState.ENDED, baseTime.minusSeconds(500), legitimateEnd, List.of(
                new DuelParticipant(p1, "s1"),
                new DuelParticipant(p2, "s2")
        ));

        repository.save(leaked1);
        repository.save(leaked2);
        repository.save(normalEnded);

        // Server startup initiates cleanup
        Instant startupTime = baseTime;
        int cleanedCount = repository.cleanupStaleDuelsOnStartup(startupTime);

        assertThat(cleanedCount).isEqualTo(2);

        // Assert no active duels remain
        assertThat(repository.findActiveDuels()).isEmpty();

        // Assert leaked duels are now ended with ended_at set to startupTime
        Optional<DuelRecord> r1 = repository.findById("leaked-1");
        assertThat(r1).isPresent();
        assertThat(r1.get().state()).isEqualTo(DuelState.ENDED);
        assertThat(r1.get().endedAt()).isEqualTo(startupTime);

        Optional<DuelRecord> r2 = repository.findById("leaked-2");
        assertThat(r2).isPresent();
        assertThat(r2.get().state()).isEqualTo(DuelState.ENDED);
        assertThat(r2.get().endedAt()).isEqualTo(startupTime);

        // Assert previously ended duel was NOT overwritten
        Optional<DuelRecord> rNormal = repository.findById("normal-ended");
        assertThat(rNormal).isPresent();
        assertThat(rNormal.get().state()).isEqualTo(DuelState.ENDED);
        assertThat(rNormal.get().endedAt()).isEqualTo(legitimateEnd);
    }

    @Test
    @DisplayName("P2: cleanupStaleDuelsOnStartupAsync cleans duels asynchronously without blocking")
    void cleanupStaleDuelsOnStartupAsyncCleansDuels() throws Exception {
        PlayerId p1 = PlayerId.of(UUID.randomUUID());
        PlayerId p2 = PlayerId.of(UUID.randomUUID());

        DuelRecord leaked = new DuelRecord("leaked-async", DuelState.ACTIVE, baseTime.minusSeconds(300), List.of(
                new DuelParticipant(p1, "s1"),
                new DuelParticipant(p2, "s2")
        ));
        repository.save(leaked);

        CompletableFuture<Integer> future = repository.cleanupStaleDuelsOnStartupAsync(baseTime);
        int cleaned = future.get(5, TimeUnit.SECONDS);

        assertThat(cleaned).isEqualTo(1);
        assertThat(repository.findActiveDuels()).isEmpty();

        Optional<DuelRecord> loaded = repository.findById("leaked-async");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().state()).isEqualTo(DuelState.ENDED);
        assertThat(loaded.get().endedAt()).isEqualTo(baseTime);
    }
}
