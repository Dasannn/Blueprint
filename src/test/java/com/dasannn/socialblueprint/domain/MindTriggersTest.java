package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class MindTriggersTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private static PlayerId player() { return PlayerId.of(UUID.randomUUID()); }

    @Test void everyTriggerHonorsItsSwitchDuelExemptionAndActiveRequirement() {
        for (MindInput kind : MindInput.values()) {
            assertThat(MindTriggers.eligible(kind.defaults().enabled(), false, true)).isTrue();
            assertThat(MindTriggers.eligible(false, false, true)).isFalse();
            assertThat(MindTriggers.eligible(true, true, true)).isFalse();
            assertThat(MindTriggers.eligible(true, false, false)).isFalse();
        }
    }
    @Test void nearDeathRequiresCrossingAndRearmsOnlyAboveTheThreshold() {
        var trigger = new MindTriggers.NearDeath();
        assertThat(trigger.damage(20, 4.01, 4)).isFalse();
        assertThat(trigger.damage(4.01, 4, 4)).isTrue();
        assertThat(trigger.damage(4, 3, 4)).isFalse();
        trigger.observe(4, 4);
        assertThat(trigger.damage(4, 1, 4)).isFalse();
        trigger.observe(4.01, 4);
        assertThat(trigger.damage(4.01, 0.1, 4)).isTrue();
        assertThat(trigger.damage(20, 0, 4)).isFalse();
        assertThat(trigger.damage(20, -1, 4)).isFalse();
        assertThat(trigger.damage(3, 2, 4)).isFalse();
        assertThat(trigger.damage(20, 8, 8)).isTrue();
    }
    @Test void cleanDayRequiresBothFullRealDayAndEnoughActiveMinutes() {
        assertThat(MindTriggers.cleanDay(NOW, NOW.plus(Duration.ofHours(24)).minusNanos(1), 1_800_000, 30)).isFalse();
        assertThat(MindTriggers.cleanDay(NOW, NOW.plus(Duration.ofHours(24)), 1_799_999, 30)).isFalse();
        assertThat(MindTriggers.cleanDay(NOW, NOW.plus(Duration.ofHours(24)), 1_800_000, 30)).isTrue();
        assertThat(MindTriggers.cleanDay(NOW, NOW.plus(Duration.ofDays(100)), 0, 30)).isFalse();
        assertThat(MindTriggers.cleanDay(NOW, NOW.plus(Duration.ofHours(24)), 0, 0)).isTrue();
    }
    @Test void fishingRequiresFishAndRecentIndependentActivityWithoutAfk() {
        assertThat(MindTriggers.fishing(true, true, false)).isTrue();
        assertThat(MindTriggers.fishing(true, false, false)).isFalse();
        assertThat(MindTriggers.fishing(true, true, true)).isFalse();
        assertThat(MindTriggers.fishing(false, true, false)).isFalse();
        for (String fish : java.util.List.of("COD", "SALMON", "PUFFERFISH", "TROPICAL_FISH")) assertThat(MindTriggers.fish(fish)).isTrue();
        assertThat(MindTriggers.fish("ENCHANTED_BOOK")).isFalse();
        var session = new SerenitySession(0, null, 0, 0);
        assertThat(session.active(0, 300)).isFalse();
        session.activity(0);
        assertThat(session.active(299_999, 300)).isTrue();
        assertThat(session.active(300_000, 300)).isFalse();
        session.afk(true); session.activity(300_000);
        assertThat(session.active(300_000, 300)).isFalse();
    }
    @Test void cropsMustBeOnFarmlandAndHarvestsMustBeMature() {
        for (String crop : java.util.List.of("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "TORCHFLOWER_CROP", "PITCHER_CROP")) {
            assertThat(MindTriggers.planting(crop, true)).isTrue();
            assertThat(MindTriggers.planting(crop, false)).isFalse();
            assertThat(MindTriggers.harvesting(crop, 7, 7)).isTrue();
            assertThat(MindTriggers.harvesting(crop, 6, 7)).isFalse();
        }
        assertThat(MindTriggers.planting("STONE", true)).isFalse();
        assertThat(MindTriggers.harvesting("OAK_SAPLING", 1, 1)).isFalse();
        assertThat(MindTriggers.terminalCrop("TORCHFLOWER", true)).isTrue();
        assertThat(MindTriggers.terminalCrop("PITCHER_PLANT", true)).isTrue();
        assertThat(MindTriggers.terminalCrop("TORCHFLOWER", false)).isFalse();
        assertThat(MindTriggers.harvesting("COCOA", 2, 2)).isTrue();
        assertThat(MindTriggers.harvesting("NETHER_WART", 3, 3)).isTrue();
    }
    @Test void babyFeedingExcludesAdultsAndNaturalAgeing() {
        assertThat(MindTriggers.feeding(-1000, -900, true)).isTrue();
        assertThat(MindTriggers.feeding(-1, 0, true)).isTrue();
        assertThat(MindTriggers.feeding(-1000, -999, false)).isFalse();
        assertThat(MindTriggers.feeding(-1000, -1000, true)).isFalse();
        assertThat(MindTriggers.feeding(0, 100, true)).isFalse();
    }
    @Test void halfNightIsPerPlayerAndCannotBeInventedByASkip() {
        var night = new MindNight(13000, 23000);
        PlayerId half = player(), shortNight = player(), idle = player();
        night.time(13000);
        night.presence(half, 250_000, false, false, true);
        night.presence(shortNight, 249_999, false, false, true);
        night.presence(idle, 0, false, false, false);
        assertThat(night.time(23000)).containsExactly(new MindNight.Credit(half, MindInput.SLEEPLESS_NIGHT));
        assertThat(night.time(24000)).isEmpty();
        night.time(37000); // New night has no carry-over.
        night.presence(half, 0, false, false, true);
        assertThat(night.time(48000)).isEmpty();
        assertThat(night.time(72000)).isEmpty(); // Fully skipped nights invent nothing.
    }
    @Test void sleepRequiresDeepSleepAtNightEndAndNoManualWakeOrLogout() {
        var night = new MindNight(13000, 23000);
        PlayerId deep = player(), shallow = player(), woke = player(), afk = player();
        night.time(13000);
        night.presence(deep, 0, true, true, true);
        night.presence(shallow, 300_000, true, false, true);
        night.presence(woke, 0, true, true, true); night.wake(woke);
        night.presence(afk, 0, true, true, false);
        assertThat(night.time(18000)).isEmpty();
        assertThat(night.time(24000)).containsExactly(new MindNight.Credit(deep, MindInput.SLEEP));
    }
}
