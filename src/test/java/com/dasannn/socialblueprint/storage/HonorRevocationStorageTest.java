package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HonorRevocationStorageTest {
    @Test void onceOnlyTargetBoundAuditedAndNeverRefundsOrRestoresAllowance() {
        try (StorageEngine engine = StorageEngine.inMemory()) {
            prepareSchema(engine);
            var repository = new ReputationRepository(engine, new StatusCache());
            var audits = new AuditRepository(engine);
            PlayerId actor = PlayerId.of(UUID.randomUUID());
            PlayerId target = PlayerId.of(UUID.randomUUID());
            Instant now = Instant.parse("2026-10-02T12:00:00Z");
            for (HonorKind kind : List.of(HonorKind.POSITIVE, HonorKind.NEGATIVE, HonorKind.SYSTEM_KILL)) {
                var rating = repository.save(new ReputationEvent(kind == HonorKind.SYSTEM_KILL ? null : actor,
                        target, kind.isPositive() ? 1 : -1, kind, kind.isPlayerHonor() ? 500 : 0, "idiot &a original", now));
                assertThat(repository.revokeAsync(actor, "Admin", PlayerId.of(UUID.randomUUID()), rating.id(), now, audits).join()).isFalse();
                assertThat(repository.revokeAsync(actor, "Admin", target, rating.id(), now, audits).join()).isTrue();
                assertThat(repository.revokeAsync(actor, "Admin", target, rating.id(), now, audits).join()).isFalse();
                var events = repository.findByTarget(target);
                var original = events.stream().filter(e -> e.id() == rating.id()).findFirst().orElseThrow();
                var revoke = events.getLast();
                assertThat(original.reason()).isEqualTo("idiot &a original");
                assertThat(original.cost()).isEqualTo(rating.cost());
                if (kind.isPlayerHonor()) {
                    var tracker = new HonorAllowanceTracker(HonorAllowanceConfig.defaults());
                    assertThat(tracker.countInWindow(actor, target, kind, events, now)).isEqualTo(1);
                    var costs = new HonorCostCalculator(HonorCostConfig.defaults());
                    assertThat(costs.countActorRatingsInWindow(actor, events, now)).isGreaterThanOrEqualTo(1);
                }
                assertThat(original.revokedBy()).isEqualTo("Admin");
                assertThat(revoke.revokedRatingId()).isEqualTo(rating.id());
                assertThat(revoke.cost()).isZero();
                assertThat(revoke.delta()).isZero();
                assertThat(repository.revokeAsync(actor, "Admin", target, revoke.id(), now, audits).join()).isFalse();
                assertThat(Status.fromEvents(events)).isEqualTo(Status.ZERO);
            }
            assertThat(audits.findByTarget(target)).hasSize(3);
            assertThat(audits.findByTarget(target).getFirst().before()).contains("idiot &a original");
            assertThat(repository.findByTarget(target).stream().filter(e -> e.kind().isPlayerHonor()).count()).isEqualTo(2);
        }
    }

    @Test void failedAuditRollsBackReferenceAndLeavesRatingRevocable() {
        try (StorageEngine engine = StorageEngine.inMemory()) {
            prepareSchema(engine);
            var repository = new ReputationRepository(engine, new StatusCache());
            var audits = new AuditRepository(engine);
            var actor = PlayerId.of(UUID.randomUUID());
            var target = PlayerId.of(UUID.randomUUID());
            var rating = repository.save(new ReputationEvent(actor, target, 1, HonorKind.POSITIVE, 500, "Helpful", Instant.now()));
            StorageTestSupport.setFailAuditTrigger(engine);
            assertThatThrownBy(() -> repository.revokeAsync(actor, "Admin", target, rating.id(), Instant.now(), audits).join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class);
            assertThat(repository.findByTarget(target)).hasSize(1);
            assertThat(repository.findByTarget(target).getFirst().canRevoke()).isTrue();
            StorageTestSupport.dropFailAuditTrigger(engine);
            assertThat(repository.revokeAsync(actor, "Admin", target, rating.id(), Instant.now(), audits).join()).isTrue();
        }
    }

    private static void prepareSchema(StorageEngine engine) {
        engine.runMigrations(new MigrationRunner(List.of(new Migration_1_InitialSchema(),
                new Migration_2_RaterReveal(), new Migration_3_KillPenaltyClaim(),
                new Migration_4_PendingCompensation(), new Migration_5_Serenity())));
        // T-200 supplies migration 6 in parallel. Exercise 7 directly here,
        // without inventing a migration 6 or advancing schema_version past it.
        engine.execute(conn -> {
            boolean present = false;
            try (var stmt = conn.createStatement(); var columns = stmt.executeQuery("PRAGMA table_info(reputation_event)")) {
                while (columns.next()) if ("revoked_rating_id".equals(columns.getString("name"))) present = true;
            }
            if (!present) new Migration_7_HonorRevocation().apply(conn);
            return null;
        });
    }
}
