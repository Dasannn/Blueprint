package com.dasannn.socialblueprint.feature.gui;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Main-thread, plain-data confirmation state; expiry is exclusive. */
final class RevealConfirmation {
    record Arm(long eventId, Instant expiry) {}

    private final Clock clock;
    private final Map<UUID, Arm> arms = new HashMap<>();

    RevealConfirmation(Clock clock) {
        this.clock = clock;
    }

    Arm armed(UUID viewer) {
        Arm arm = arms.get(viewer);
        if (arm != null && !clock.instant().isBefore(arm.expiry())) {
            arms.remove(viewer);
            return null;
        }
        return arm;
    }

    boolean click(UUID viewer, long eventId, int seconds) {
        Arm arm = armed(viewer);
        if (arm != null && arm.eventId() == eventId) {
            arms.remove(viewer);
            return true;
        }
        arms.put(viewer, new Arm(eventId, clock.instant().plusSeconds(seconds)));
        return false;
    }

    void disarm(UUID viewer) {
        arms.remove(viewer);
    }
}
