package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HonorRevocationStorageTest {
    @Test void lastChoosesLatestNonRevokedRatingWithIdTieBreakAndTargetIsolation() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var rep = new ReputationRepository(engine, new StatusCache());
            var audits = new AuditRepository(engine); var actor = PlayerId.CONSOLE;
            var target = PlayerId.of(UUID.randomUUID()); var other = PlayerId.of(UUID.randomUUID());
            var now = Instant.now();
            var first = rep.save(new ReputationEvent(actor, target, 1, HonorKind.ADMIN_GIVE, 0, "First", now));
            var last = rep.save(new ReputationEvent(actor, target, -1, HonorKind.ADMIN_TAKE, 0, "Last", now));
            rep.save(new ReputationEvent(actor, other, 1, HonorKind.ADMIN_GIVE, 0, "Other", now.plusSeconds(1)));
            rep.save(new ReputationEvent(actor, target, 0, HonorKind.ADMIN_RESET, 0, "Reset", now.plusSeconds(2)));
            assertThat(rep.revokeAsync(actor, "Owner", target, -1, now, audits).join()).isTrue();
            assertThat(rep.findByTarget(target).getLast().revokedRatingId()).isEqualTo(last.id());
            assertThat(rep.revokeAsync(actor, "Owner", target, -1, now, audits).join()).isTrue();
            assertThat(rep.findByTarget(target).getLast().revokedRatingId()).isEqualTo(first.id());
            assertThat(rep.revokeAsync(actor, "Owner", target, -1, now, audits).join()).isFalse();
            assertThat(rep.findByTarget(other).getFirst().canRevoke()).isTrue();
            assertThat(audits.findByTarget(target)).hasSize(2);
        }
    }

    @Test void mindSetAndAuditAreAtomicAndOnlySuccessfulWritesInvalidate() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var mind = new MindRepository(engine); var target = PlayerId.of(UUID.randomUUID());
            var invalidated = new java.util.ArrayList<PlayerId>(); mind.addInvalidationListener(invalidated::add);
            mind.setAsync(target, -100, PlayerId.CONSOLE, "Owner", Instant.now()).join();
            assertThat(mind.value(target)).isEqualTo(-100);
            assertThat(invalidated).containsExactly(target);
            StorageTestSupport.setFailAuditTrigger(engine);
            assertThatThrownBy(() -> mind.setAsync(target, 100, PlayerId.CONSOLE, "Owner", Instant.now()).join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class);
            assertThat(mind.value(target)).isEqualTo(-100);
            assertThat(mind.events(target)).hasSize(1);
            assertThat(invalidated).hasSize(1);
        }
    }

    @Test void onceOnlyGlobalIdAuditedAndNeverRefundsOrRestoresAllowance() {
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
                assertThat(repository.revokeAsync(actor, "Admin", PlayerId.of(UUID.randomUUID()), rating.id(), now, audits).join()).isTrue();
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

    @Test void globalIdResultDistinguishesMissingAndNotRevocableAndInvalidatesActualTarget() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations();
            var rep = new ReputationRepository(engine, new StatusCache());
            var audits = new AuditRepository(engine);
            var target = PlayerId.of(UUID.randomUUID());
            var now = Instant.now();
            var rating = rep.save(new ReputationEvent(PlayerId.CONSOLE, target, 1,
                    HonorKind.ADMIN_GIVE, 0, "Admin", now));
            var invalidated = new java.util.ArrayList<PlayerId>();
            rep.addInvalidationListener(invalidated::add);
            var missing = rep.revokeRatingAsync(PlayerId.CONSOLE, "Owner", null, Long.MAX_VALUE, now, audits).join();
            assertThat(missing.rating()).isNull();
            assertThat(missing.revoked()).isFalse();
            var success = rep.revokeRatingAsync(PlayerId.CONSOLE, "Owner", null, rating.id(), now, audits).join();
            assertThat(success.rating()).isEqualTo(rating);
            assertThat(success.revoked()).isTrue();
            var retry = rep.revokeRatingAsync(PlayerId.CONSOLE, "Owner", null, rating.id(), now, audits).join();
            assertThat(retry.rating().id()).isEqualTo(rating.id());
            assertThat(retry.revoked()).isFalse();
            var revocation = rep.findByTarget(target).getLast();
            var rejected = rep.revokeRatingAsync(PlayerId.CONSOLE, "Owner", null, revocation.id(), now, audits).join();
            assertThat(rejected.rating().kind()).isEqualTo(HonorKind.REVOCATION);
            assertThat(rejected.revoked()).isFalse();
            assertThat(invalidated).containsExactly(target);
            assertThat(audits.findByTarget(target)).hasSize(1);
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
        engine.runMigrations();
    }
}
