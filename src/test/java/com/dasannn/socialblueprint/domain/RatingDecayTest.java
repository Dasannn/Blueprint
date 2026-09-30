package com.dasannn.socialblueprint.domain;

import com.dasannn.socialblueprint.config.DecayConfigSection;
import com.dasannn.socialblueprint.config.TiersConfig;
import com.dasannn.socialblueprint.storage.StatusCache;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class RatingDecayTest {

    static class TestClock extends Clock {
        private Instant instant;

        TestClock(Instant initial) {
            this.instant = initial;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    // =========================================================================
    // T-110: The Curve & Status.fromEvents overload
    // =========================================================================

    @Test
    @DisplayName("T-110 / SB-006: Exponential decay halves rating contribution every half-life")
    void exponentialCurveHalvesEveryHalfLife() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());
        Instant baseTime = Instant.parse("2026-09-01T00:00:00Z");

        ReputationEvent event = new ReputationEvent(0L, actor, target, 100, HonorKind.POSITIVE, 500.0, null, baseTime);
        List<ReputationEvent> events = List.of(event);
        DecayConfig decay = new DecayConfig(true, Duration.ofDays(30), 0.0);

        // Age 0: weight 1.0 -> Status 100
        Status s0 = Status.fromEvents(events, decay, baseTime);
        assertThat(s0.value()).isEqualTo(100);

        // Age 30d (1 half-life): weight 0.5 -> Status 50
        Status s1 = Status.fromEvents(events, decay, baseTime.plus(Duration.ofDays(30)));
        assertThat(s1.value()).isEqualTo(50);

        // Age 60d (2 half-lives): weight 0.25 -> Status 25
        Status s2 = Status.fromEvents(events, decay, baseTime.plus(Duration.ofDays(60)));
        assertThat(s2.value()).isEqualTo(25);

        // Age 90d (3 half-lives): weight 0.125 -> 100 * 0.125 = 12.5 -> rounds half away from zero to 13
        Status s3 = Status.fromEvents(events, decay, baseTime.plus(Duration.ofDays(90)));
        assertThat(s3.value()).isEqualTo(13);
    }

    @Test
    @DisplayName("T-110: Clock skew with future event timestamp is clamped to weight 1.0")
    void futureEventTimestampClampedToOne() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        // Event timestamp 2 hours in the future relative to 'now'
        ReputationEvent futureEvent = new ReputationEvent(0L, actor, target, 20, HonorKind.POSITIVE, 500.0, null, now.plus(Duration.ofHours(2)));
        DecayConfig decay = new DecayConfig(true, Duration.ofDays(30), 0.0);

        Status status = Status.fromEvents(List.of(futureEvent), decay, now);
        assertThat(status.value()).isEqualTo(20);
    }

    @Test
    @DisplayName("T-110: Events with weight below floor contribute nothing")
    void floorExcludesEventsBelowThreshold() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        // Event 1: 30 days old -> weight 0.5 (above floor 0.3) -> 100 * 0.5 = 50
        ReputationEvent event1 = new ReputationEvent(0L, actor, target, 100, HonorKind.POSITIVE, 500.0, null, now.minus(Duration.ofDays(30)));
        // Event 2: 60 days old -> weight 0.25 (below floor 0.3) -> 0
        ReputationEvent event2 = new ReputationEvent(0L, actor, target, 100, HonorKind.POSITIVE, 500.0, null, now.minus(Duration.ofDays(60)));

        DecayConfig decayWithFloor = new DecayConfig(true, Duration.ofDays(30), 0.3);
        Status status = Status.fromEvents(List.of(event1, event2), decayWithFloor, now);

        assertThat(status.value()).isEqualTo(50);
    }

    @Test
    @DisplayName("T-110: Rounding half away from zero ensures decayed -1 rounds to -1 at half-life and never rounds to 0 while above floor")
    void roundingHalfAwayFromZeroPreservesDecayedNegativeOne() {
        PlayerId actor = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        // A single slight (-1) at exactly 1 half-life (30 days) -> weight = 0.5
        // Weighted sum = -0.5.
        // Under Java's Math.round(-0.5), it would round to 0 (towards positive infinity).
        // Under symmetric round-half-away-from-zero, -0.5 rounds to -1.
        ReputationEvent slight = new ReputationEvent(0L, actor, target, -1, HonorKind.NEGATIVE, 500.0, "Dispute", now.minus(Duration.ofDays(30)));
        DecayConfig decay = new DecayConfig(true, Duration.ofDays(30), 0.0);

        Status statusNegative = Status.fromEvents(List.of(slight), decay, now);
        assertThat(statusNegative.value()).isEqualTo(-1);

        // Positive counterpart: +1 at 30 days -> weight 0.5 -> +0.5 rounds to +1
        ReputationEvent positive = new ReputationEvent(0L, actor, target, 1, HonorKind.POSITIVE, 500.0, null, now.minus(Duration.ofDays(30)));
        Status statusPositive = Status.fromEvents(List.of(positive), decay, now);
        assertThat(statusPositive.value()).isEqualTo(1);
    }

    @Test
    @DisplayName("T-110: enabled: false produces identical result to undecayed sum on same data")
    void disabledDecayMatchesUndecayedSumExactly() {
        PlayerId actor1 = PlayerId.of(UUID.randomUUID());
        PlayerId actor2 = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        List<ReputationEvent> events = List.of(
                new ReputationEvent(0L, actor1, target, 15, HonorKind.POSITIVE, 500.0, null, now.minus(Duration.ofDays(100))),
                new ReputationEvent(0L, actor2, target, -5, HonorKind.NEGATIVE, 500.0, "Stole", now.minus(Duration.ofDays(200))),
                new ReputationEvent(0L, actor1, target, 20, HonorKind.ADMIN_GIVE, 0.0, "Reward", now.minus(Duration.ofDays(50))),
                new ReputationEvent(0L, actor2, target, -10, HonorKind.ADMIN_TAKE, 0.0, "Fine", now.minus(Duration.ofDays(10))),
                new ReputationEvent(0L, null, target, 42, HonorKind.LEGACY_IMPORT, 0.0, "Legacy", now.minus(Duration.ofDays(400)))
        );

        DecayConfig disabledDecay = new DecayConfig(false, Duration.ofDays(30), 0.0);

        Status undecayed = Status.fromEvents(events);
        Status decayedDisabled = Status.fromEvents(events, disabledDecay, now);

        assertThat(decayedDisabled).isEqualTo(undecayed);
        assertThat(decayedDisabled.value()).isEqualTo(15 - 5 + 20 - 10 + 42);
    }

    @Test
    @DisplayName("T-110: Events with kind ADMIN_RESET and compensation events must not decay")
    void adminResetExemptFromDecay() {
        PlayerId admin = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        // Old positive event (300 days old, 10 half-lives): weight 2^(-10) = 1/1024 -> ~0
        ReputationEvent oldPositive = new ReputationEvent(0L, admin, target, 100, HonorKind.POSITIVE, 500.0, null, now.minus(Duration.ofDays(300)));
        // Admin reset compensating event: kind ADMIN_RESET, 300 days old
        ReputationEvent adminReset = new ReputationEvent(0L, admin, target, -100, HonorKind.ADMIN_RESET, 0.0, "Admin reset", now.minus(Duration.ofDays(300)));

        DecayConfig decay = new DecayConfig(true, Duration.ofDays(30), 0.0);

        // The admin reset retains weight 1.0 (-100), while the positive event has decayed to 0
        Status status = Status.fromEvents(List.of(oldPositive, adminReset), decay, now);

        // 100 * (1/1024) = 0.09765625. -100 * 1.0 = -100.
        // Sum = -99.90234375 -> rounded half away from zero = -100.
        assertThat(status.value()).isEqualTo(-100);
    }

    // =========================================================================
    // T-111: Independence from Confidence
    // =========================================================================

    @Test
    @DisplayName("T-111 / SB-006: Doubling decay.half-life changes status and leaves ConfidenceLevel untouched")
    void doublingDecayHalfLifeLeavesConfidenceLevelUntouched() {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        // 5 distinct actors, each rating +10, all exactly 30 days old
        List<ReputationEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            PlayerId actor = PlayerId.of(UUID.randomUUID());
            events.add(new ReputationEvent(0L, actor, target, 10, HonorKind.POSITIVE, 500.0, null, now.minus(Duration.ofDays(30))));
        }

        ConfidenceConfig confConfig = ConfidenceConfig.defaults(); // halfLife = 30d, low=1.0, established=5.0, high=15.0
        ConfidenceCalculator confCalc = new ConfidenceCalculator(confConfig);

        DecayConfig decay30 = new DecayConfig(true, Duration.ofDays(30), 0.0);
        DecayConfig decay60 = new DecayConfig(true, Duration.ofDays(60), 0.0); // Doubled half-life

        // Status calculations under the two decay half-lives
        Status status30 = Status.fromEvents(events, decay30, now);
        Status status60 = Status.fromEvents(events, decay60, now);

        // With 30d half-life, each event has weight 0.5 -> 5 * (10 * 0.5) = 25
        assertThat(status30.value()).isEqualTo(25);
        // With 60d half-life, each event has weight 2^(-30/60) = 2^(-0.5) ≈ 0.7071 -> 5 * (10 * 0.7071) ≈ 35.35 -> 35
        assertThat(status60.value()).isEqualTo(35);
        assertThat(status60).isNotEqualTo(status30);

        // Confidence calculation remains completely untouched by the decay half-life change
        ConfidenceLevel cl1 = confCalc.calculate(events, now);
        double score1 = confCalc.calculateScore(events, now);

        ConfidenceLevel cl2 = confCalc.calculate(events, now);
        double score2 = confCalc.calculateScore(events, now);

        assertThat(cl1).isEqualTo(ConfidenceLevel.LOW);
        assertThat(cl2).isEqualTo(ConfidenceLevel.LOW);
        assertThat(score1).isEqualTo(score2);
        assertThat(score1).isEqualTo(2.5); // 5 distinct actors * 0.5 = 2.5
    }

    @Test
    @DisplayName("T-111 / SB-006: Changing Confidence half-life leaves derived Status untouched")
    void changingConfidenceHalfLifeLeavesDerivedStatusUntouched() {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        Instant now = Instant.parse("2026-09-30T12:00:00Z");

        List<ReputationEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            PlayerId actor = PlayerId.of(UUID.randomUUID());
            events.add(new ReputationEvent(0L, actor, target, 10, HonorKind.POSITIVE, 500.0, null, now.minus(Duration.ofDays(30))));
        }

        DecayConfig decayConfig = new DecayConfig(true, Duration.ofDays(30), 0.0);

        // Confidence configs with different half-lives (30d vs 60d)
        ConfidenceConfig conf30 = new ConfidenceConfig(1.0, 5.0, 15.0, Duration.ofDays(30));
        ConfidenceConfig conf60 = new ConfidenceConfig(1.0, 5.0, 15.0, Duration.ofDays(60));

        ConfidenceCalculator calc30 = new ConfidenceCalculator(conf30);
        ConfidenceCalculator calc60 = new ConfidenceCalculator(conf60);

        double confScore30 = calc30.calculateScore(events, now);
        double confScore60 = calc60.calculateScore(events, now);

        // Confidence scores differ
        assertThat(confScore30).isEqualTo(2.5);
        assertThat(confScore60).isGreaterThan(3.5);

        // Derived status is computed with DecayConfig and is completely unaffected by ConfidenceConfig
        Status statusA = Status.fromEvents(events, decayConfig, now);
        Status statusB = Status.fromEvents(events, decayConfig, now);

        assertThat(statusA.value()).isEqualTo(25);
        assertThat(statusB.value()).isEqualTo(25);
        assertThat(statusA).isEqualTo(statusB);
    }

    // =========================================================================
    // T-112: Symmetric Tier Ladder & Migration Reporting
    // =========================================================================

    @Test
    @DisplayName("T-112 / SB-011a: Symmetric ladder requires identical movement magnitude to rise or fall across all tiers")
    void symmetricLadderMovementCostsIdentical() {
        TierLadder ladder = TierLadder.of(TiersConfig.DEFAULT_THRESHOLDS);

        // Neutral tier (Particular) at 0
        assertThat(ladder.resolve(0)).isEqualTo(Tier.PARTICULAR);
        assertThat(ladder.resolve(4)).isEqualTo(Tier.PARTICULAR);
        assertThat(ladder.resolve(-4)).isEqualTo(Tier.PARTICULAR);

        // Tier 1 / Tier -1: symmetric movement of 5 points from neutral
        assertThat(ladder.resolve(5)).isEqualTo(Tier.AFABLE);
        assertThat(ladder.resolve(-5)).isEqualTo(Tier.TEMERARIO);

        // Tier 2 / Tier -2: symmetric movement of 10 points from previous tier (5 -> 15 and -5 -> -15)
        assertThat(ladder.resolve(14)).isEqualTo(Tier.AFABLE);
        assertThat(ladder.resolve(15)).isEqualTo(Tier.HONORABLE);
        assertThat(ladder.resolve(-14)).isEqualTo(Tier.TEMERARIO);
        assertThat(ladder.resolve(-15)).isEqualTo(Tier.DELINCUENTE);

        // Tier 3 / Tier -3: symmetric movement of 15 points from previous tier (15 -> 30 and -15 -> -30)
        assertThat(ladder.resolve(29)).isEqualTo(Tier.HONORABLE);
        assertThat(ladder.resolve(30)).isEqualTo(Tier.INSIGNE);
        assertThat(ladder.resolve(-29)).isEqualTo(Tier.DELINCUENTE);
        assertThat(ladder.resolve(-30)).isEqualTo(Tier.FORAJIDO);

        // Tier 4 / Tier -4: symmetric movement of 20 points from previous tier (30 -> 50 and -30 -> -50)
        assertThat(ladder.resolve(49)).isEqualTo(Tier.INSIGNE);
        assertThat(ladder.resolve(50)).isEqualTo(Tier.ILUSTRE);
        assertThat(ladder.resolve(-49)).isEqualTo(Tier.FORAJIDO);
        assertThat(ladder.resolve(-50)).isEqualTo(Tier.CRIMINAL);
    }

    @Test
    @DisplayName("T-112: Migration reports differences without silently reclassifying; keeps stored negative thresholds")
    void migrationReportsDifferencesAndKeepsStoredThresholds() {
        String legacyYaml = """
                tiers:
                  tier-4: { prefix: '&7[&4||||&7]', threshold: -30 }
                  tier-3: { prefix: '&7[&c|||&7]', threshold: -20 }
                  tier-2: { prefix: '&7[&c||&7]', threshold: -10 }
                  tier-1: { prefix: '&7[&c|&7]', threshold: -1 }
                  tier0: { prefix: '&7[&f|&7]', threshold: 0 }
                  tier1: { prefix: '&7[&a|&7]', threshold: 5 }
                  tier2: { prefix: '&7[&a||&7]', threshold: 15 }
                  tier3: { prefix: '&7[&a|||&7]', threshold: 30 }
                  tier4: { prefix: '&7[&b||||&7]', threshold: 50 }
                """;

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new StringReader(legacyYaml));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<String> logMessages = new ArrayList<>();
        Logger testLogger = Logger.getLogger("MigrationTestLogger-" + UUID.randomUUID());
        testLogger.setUseParentHandlers(false);
        testLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logMessages.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });

        TiersConfig loaded = TiersConfig.load(yaml, testLogger);

        // 1. Kept stored thresholds (not silently rewritten to -50, -30, -15, -5)
        assertThat(loaded.get(Tier.CRIMINAL).threshold()).isEqualTo(-30);
        assertThat(loaded.get(Tier.FORAJIDO).threshold()).isEqualTo(-20);
        assertThat(loaded.get(Tier.DELINCUENTE).threshold()).isEqualTo(-10);
        assertThat(loaded.get(Tier.TEMERARIO).threshold()).isEqualTo(-1);

        // 2. Ladder resolves using stored thresholds: -2 is TEMERARIO under stored, but would be PARTICULAR under new
        assertThat(loaded.ladder().resolve(-2)).isEqualTo(Tier.TEMERARIO);

        // 3. Logged exactly one line per changed negative tier naming old and new threshold
        assertThat(logMessages).hasSize(4);
        assertThat(logMessages).anyMatch(msg -> msg.contains("tier-4") && msg.contains("-30") && msg.contains("-50"));
        assertThat(logMessages).anyMatch(msg -> msg.contains("tier-3") && msg.contains("-20") && msg.contains("-30"));
        assertThat(logMessages).anyMatch(msg -> msg.contains("tier-2") && msg.contains("-10") && msg.contains("-15"));
        assertThat(logMessages).anyMatch(msg -> msg.contains("tier-1") && msg.contains("-1") && msg.contains("-5"));
    }

    @Test
    @DisplayName("T-112: Migration logs nothing when stored configuration already matches symmetric defaults")
    void migrationLogsNothingWhenAlreadyMatchingDefaults() {
        String defaultYaml = """
                tiers:
                  tier-4: { prefix: '&7[&4||||&7]', threshold: -50 }
                  tier-3: { prefix: '&7[&c|||&7]', threshold: -30 }
                  tier-2: { prefix: '&7[&c||&7]', threshold: -15 }
                  tier-1: { prefix: '&7[&c|&7]', threshold: -5 }
                  tier0: { prefix: '&7[&f|&7]', threshold: 0 }
                  tier1: { prefix: '&7[&a|&7]', threshold: 5 }
                  tier2: { prefix: '&7[&a||&7]', threshold: 15 }
                  tier3: { prefix: '&7[&a|||&7]', threshold: 30 }
                  tier4: { prefix: '&7[&b||||&7]', threshold: 50 }
                """;

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new StringReader(defaultYaml));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<String> logMessages = new ArrayList<>();
        Logger testLogger = Logger.getLogger("MigrationTestLogger-" + UUID.randomUUID());
        testLogger.setUseParentHandlers(false);
        testLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logMessages.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });

        TiersConfig loaded = TiersConfig.load(yaml, testLogger);

        assertThat(loaded.get(Tier.CRIMINAL).threshold()).isEqualTo(-50);
        assertThat(logMessages).isEmpty();
    }

    // =========================================================================
    // Caching: StatusCache TTL & Refresh
    // =========================================================================

    @Test
    @DisplayName("Caching: StatusCache retains status within TTL and expires after TTL")
    void statusCacheTtlExpiration() {
        TestClock clock = new TestClock(Instant.parse("2026-09-30T12:00:00Z"));
        StatusCache cache = new StatusCache(Duration.ofSeconds(60), clock);
        PlayerId player = PlayerId.of(UUID.randomUUID());

        cache.put(player, Status.of(42));

        // Within TTL: cached
        assertThat(cache.isCached(player)).isTrue();
        assertThat(cache.get(player)).isPresent().contains(Status.of(42));
        assertThat(cache.size()).isEqualTo(1);

        // Advance clock by 30 seconds (still within 60s TTL)
        clock.advance(Duration.ofSeconds(30));
        assertThat(cache.isCached(player)).isTrue();
        assertThat(cache.get(player)).isPresent().contains(Status.of(42));

        // Advance clock by another 31 seconds (total 61s > 60s TTL)
        clock.advance(Duration.ofSeconds(31));
        assertThat(cache.isCached(player)).isFalse();
        assertThat(cache.get(player)).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("Caching: getOrRebuild recomputes from event supplier after TTL expires")
    void getOrRebuildRefreshesAfterExpiration() {
        TestClock clock = new TestClock(Instant.parse("2026-09-30T12:00:00Z"));
        StatusCache cache = new StatusCache(Duration.ofSeconds(60), clock);
        PlayerId player = PlayerId.of(UUID.randomUUID());
        PlayerId actor = PlayerId.of(UUID.randomUUID());

        List<ReputationEvent> eventsList = new ArrayList<>();
        eventsList.add(new ReputationEvent(0L, actor, player, 10, HonorKind.POSITIVE, 500.0, null, clock.instant()));

        // Initial rebuild at t0
        Status initial = cache.getOrRebuild(player, () -> eventsList);
        assertThat(initial.value()).isEqualTo(10);
        assertThat(cache.isCached(player)).isTrue();

        // Add a new event to the list
        eventsList.add(new ReputationEvent(0L, actor, player, 5, HonorKind.POSITIVE, 500.0, null, clock.instant()));

        // Within TTL: getOrRebuild returns the cached value (10), does not invoke supplier
        Status cached = cache.getOrRebuild(player, () -> eventsList);
        assertThat(cached.value()).isEqualTo(10);

        // Advance clock past TTL (61 seconds)
        clock.advance(Duration.ofSeconds(61));

        // After TTL: getOrRebuild detects expiration, invokes eventSupplier, and returns updated value (15)
        Status refreshed = cache.getOrRebuild(player, () -> eventsList);
        assertThat(refreshed.value()).isEqualTo(15);
        assertThat(cache.get(player)).isPresent().contains(Status.of(15));
    }
}
