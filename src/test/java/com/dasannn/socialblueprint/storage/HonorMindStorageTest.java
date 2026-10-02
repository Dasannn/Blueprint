package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;

@Timeout(10)
class HonorMindStorageTest {
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static PlayerId player() { return PlayerId.of(UUID.randomUUID()); }
    private static ReputationEvent rating(PlayerId target, HonorKind kind, Instant now) {
        return new ReputationEvent(player(), target, kind == HonorKind.POSITIVE ? 1 : -1, kind, 500, "Reason", now);
    }
    private static ReputationEvent commit(ReputationRepository rep, PlayerId target, HonorKind kind, Instant now) {
        return rep.commitPlayerHonorAsync(rating(target, kind, now), null, null).join();
    }

    @Test void ownerExampleSharesCapAcrossDirectionsAndTargetsOnlyTheReceiver() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var rep = new ReputationRepository(engine, new StatusCache());
            var mind = new MindRepository(engine); var target = player();
            var first = commit(rep, target, HonorKind.POSITIVE, NOW);
            assertThat(mind.value(target)).isEqualTo(2);
            assertThat(mind.value(first.actor())).isZero();
            commit(rep, target, HonorKind.NEGATIVE, NOW);
            assertThat(mind.value(target)).isZero();
            commit(rep, target, HonorKind.NEGATIVE, NOW);
            assertThat(mind.value(target)).isEqualTo(-2);
            var fourth = commit(rep, target, HonorKind.POSITIVE, NOW);
            assertThat(mind.value(target)).isEqualTo(-2);
            assertThat(rep.findByTarget(target)).hasSize(4);
            assertThat(mind.events(target)).hasSize(3);
            assertThat(mind.events(target).getFirst().source()).isEqualTo(Long.toString(first.id()));
            assertThat(mind.events(target).stream().map(MindEvent::source)).doesNotContain(Long.toString(fourth.id()));
            commit(rep, target, HonorKind.NEGATIVE, NOW.plus(Duration.ofHours(24)).minusNanos(1));
            assertThat(mind.events(target)).hasSize(3);
            commit(rep, target, HonorKind.NEGATIVE, NOW.plus(Duration.ofHours(24)));
            assertThat(mind.value(target)).isEqualTo(-4);
            assertThat(mind.events(target)).hasSize(4);
            var other = player(); commit(rep, other, HonorKind.POSITIVE, NOW);
            assertThat(mind.value(other)).isEqualTo(2);
        }
    }

    @Test void disabledZeroCapAdminAndSystemEventsConsumeNoMindCap() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var rep = new ReputationRepository(engine, new StatusCache());
            var mind = new MindRepository(engine); var target = player();
            for (HonorKind kind : List.of(HonorKind.POSITIVE, HonorKind.NEGATIVE)) {
                rep.commitPlayerHonorAsync(rating(target, kind, NOW), null, null, new MindInputConfig(false, 2, 2, 3)).join();
                rep.commitPlayerHonorAsync(rating(target, kind, NOW), null, null, new MindInputConfig(true, 2, 2, 0)).join();
            }
            for (HonorKind kind : List.of(HonorKind.ADMIN_GIVE, HonorKind.ADMIN_TAKE, HonorKind.ADMIN_RESET, HonorKind.SYSTEM_KILL)) {
                var event = new ReputationEvent(player(), target, kind == HonorKind.ADMIN_GIVE ? 1 : -1, kind, 0, "Admin", NOW);
                rep.commitPlayerHonorAsync(event, null, null).join();
            }
            assertThat(mind.events(target)).isEmpty();
            assertThat(mind.value(target)).isZero();
            for (int i = 0; i < 3; i++) commit(rep, target, HonorKind.POSITIVE, NOW);
            assertThat(mind.events(target)).hasSize(3);
            assertThat(mind.value(target)).isEqualTo(6);
        }
    }

    @Test void revocationUsesAppliedDeltaCrossesNeutralAndDoesNotRefundCap() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var rep = new ReputationRepository(engine, new StatusCache());
            var mind = new MindRepository(engine); var audits = new AuditRepository(engine); var target = player();
            mind.applyAsync(target, MindInput.SLEEP, new MindInputConfig(true, 0.5, 1, 2), "setup", NOW).join();
            var negative = commit(rep, target, HonorKind.NEGATIVE, NOW);
            assertThat(mind.events(target).getLast().appliedDelta()).isEqualTo(-0.5);
            var positive = commit(rep, target, HonorKind.POSITIVE, NOW);
            commit(rep, target, HonorKind.NEGATIVE, NOW);
            assertThat(mind.value(target)).isZero();
            assertThat(rep.revokeAsync(PlayerId.CONSOLE, "Admin", target, negative.id(), NOW, audits).join()).isTrue();
            assertThat(mind.value(target)).isEqualTo(0.5);
            assertThat(mind.events(target).getLast().requestedDelta()).isEqualTo(0.5);
            assertThat(rep.revokeAsync(PlayerId.CONSOLE, "Admin", target, positive.id(), NOW, audits).join()).isTrue();
            assertThat(mind.value(target)).isEqualTo(-1.5);
            var undo = mind.events(target).getLast();
            assertThat(undo.kind()).isEqualTo("honor-revoke");
            assertThat(undo.source()).isEqualTo(Long.toString(positive.id()));
            assertThat(undo.actor()).isEqualTo(PlayerId.CONSOLE);
            assertThat(undo.appliedDelta()).isEqualTo(-2);
            assertThat(rep.revokeAsync(PlayerId.CONSOLE, "Admin", target, positive.id(), NOW, audits).join()).isFalse();
            int count = mind.events(target).size();
            var capped = commit(rep, target, HonorKind.POSITIVE, NOW);
            rep.revokeAsync(PlayerId.CONSOLE, "Admin", target, capped.id(), NOW, audits).join();
            assertThat(mind.events(target)).hasSize(count);
            assertThat(mind.value(target)).isEqualTo(-1.5);
        }
    }

    @Test void noAppliedDeltaMeansNoReversalEvenAfterStateChanges() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var rep = new ReputationRepository(engine, new StatusCache());
            var mind = new MindRepository(engine); var audits = new AuditRepository(engine); var target = player();
            mind.applyAsync(target, MindInput.SLEEP, new MindInputConfig(true, 100, 1, 2), "setup", NOW).join();
            var full = commit(rep, target, HonorKind.POSITIVE, NOW);
            assertThat(mind.events(target).getLast().appliedDelta()).isZero();
            var disabled = rep.commitPlayerHonorAsync(rating(target, HonorKind.NEGATIVE, NOW), null, null,
                    new MindInputConfig(false, 2, 2, 3)).join();
            mind.resetAsync(target, PlayerId.CONSOLE, NOW).join();
            int count = mind.events(target).size();
            for (var rating : List.of(full, disabled))
                assertThat(rep.revokeAsync(PlayerId.CONSOLE, "Admin", target, rating.id(), NOW, audits).join()).isTrue();
            assertThat(mind.events(target)).hasSize(count);
            assertThat(mind.value(target)).isZero();
        }
    }

    @Test void reversalClampsAtEitherExtremeAndAuditFailureRollsItBack() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var rep = new ReputationRepository(engine, new StatusCache());
            var mind = new MindRepository(engine); var audits = new AuditRepository(engine);
            for (HonorKind kind : List.of(HonorKind.POSITIVE, HonorKind.NEGATIVE)) {
                var target = player(); var rating = commit(rep, target, kind, NOW);
                boolean positive = kind == HonorKind.POSITIVE;
                mind.applyAsync(target, positive ? MindInput.DEATH : MindInput.SLEEP,
                        new MindInputConfig(true, 100, 100, 2), "setup", NOW).join();
                if (positive) mind.applyAsync(target, MindInput.DEATH, new MindInputConfig(true, 100, 100, 0), "setup", NOW).join();
                else mind.applyAsync(target, MindInput.SLEEP, new MindInputConfig(true, 100, 100, 2), "setup", NOW).join();
                double before = positive ? -100 : 100;
                int count = mind.events(target).size();
                StorageTestSupport.setFailAuditTrigger(engine);
                assertThatThrownBy(() -> rep.revokeAsync(PlayerId.CONSOLE, "Admin", target, rating.id(), NOW, audits).join())
                        .isInstanceOf(java.util.concurrent.CompletionException.class);
                assertThat(mind.events(target)).hasSize(count);
                assertThat(mind.value(target)).isEqualTo(before);
                assertThat(rep.findByTarget(target).getFirst().canRevoke()).isTrue();
                StorageTestSupport.dropFailAuditTrigger(engine);
                rep.revokeAsync(PlayerId.CONSOLE, "Admin", target, rating.id(), NOW, audits).join();
                assertThat(mind.value(target)).isEqualTo(before);
                var undo = mind.events(target).getLast();
                assertThat(undo.requestedDelta()).isEqualTo(positive ? -2 : 2);
                assertThat(undo.appliedDelta()).isZero();
            }
        }
    }

    @Test void queuedMixedRatingsCannotExceedCap() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var rep = new ReputationRepository(engine, new StatusCache());
            var mind = new MindRepository(engine); var target = player();
            List<CompletableFuture<ReputationEvent>> commits = new ArrayList<>();
            for (int i = 0; i < 30; i++) commits.add(rep.commitPlayerHonorAsync(
                    rating(target, i % 2 == 0 ? HonorKind.POSITIVE : HonorKind.NEGATIVE, NOW), null, null));
            CompletableFuture.allOf(commits.toArray(CompletableFuture[]::new)).join();
            assertThat(mind.events(target)).hasSize(3);
            assertThat(rep.findByTarget(target)).hasSize(30);
            var first = commits.getFirst().join();
            rep.revokeAsync(PlayerId.CONSOLE, "Admin", target, first.id(), NOW, new AuditRepository(engine)).join();
            assertThat(mind.events(target)).hasSize(4);
            assertThat(mind.value(target)).isZero();
        }
    }

    @Test void failedRatingCommitWritesNoMindEventAndFailedMindReversalRollsBackRevocation() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var rep = new ReputationRepository(engine, new StatusCache());
            var mind = new MindRepository(engine); var target = player(); var audits = new AuditRepository(engine);
            engine.execute(conn -> {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("CREATE TRIGGER fail_rating BEFORE INSERT ON reputation_event BEGIN SELECT RAISE(ABORT, 'rating failure'); END");
                }
                return null;
            });
            assertThatThrownBy(() -> commit(rep, target, HonorKind.POSITIVE, NOW))
                    .isInstanceOf(java.util.concurrent.CompletionException.class);
            assertThat(rep.findByTarget(target)).isEmpty();
            assertThat(mind.events(target)).isEmpty();
            engine.execute(conn -> {
                try (var stmt = conn.createStatement()) { stmt.execute("DROP TRIGGER fail_rating"); }
                return null;
            });
            var saved = commit(rep, target, HonorKind.POSITIVE, NOW);
            engine.execute(conn -> {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("CREATE TRIGGER fail_reversal BEFORE INSERT ON mind_event WHEN NEW.kind = 'honor-revoke' BEGIN SELECT RAISE(ABORT, 'reversal failure'); END");
                }
                return null;
            });
            assertThatThrownBy(() -> rep.revokeAsync(PlayerId.CONSOLE, "Admin", target, saved.id(), NOW, audits).join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class);
            assertThat(rep.findByTarget(target)).containsExactly(saved);
            assertThat(audits.findByTarget(target)).isEmpty();
            assertThat(mind.events(target)).hasSize(1);
            assertThat(mind.value(target)).isEqualTo(2);
        }
    }

    @Test void mindWriteFailureKeepsDurableRatingAndRollsBackMindValue() {
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations(); var rep = new ReputationRepository(engine, new StatusCache());
            var mind = new MindRepository(engine); var target = player();
            engine.execute(conn -> {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("CREATE TRIGGER fail_honor_mind BEFORE INSERT ON mind_event BEGIN SELECT RAISE(ABORT, 'mind failure'); END");
                }
                return null;
            });
            var rating = commit(rep, target, HonorKind.POSITIVE, NOW);
            assertThat(rep.findByTarget(target)).containsExactly(rating);
            assertThat(mind.events(target)).isEmpty();
            assertThat(mind.value(target)).isZero();
        }
    }
}
