package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.domain.ReputationEvent;

/**
 * Result of an atomic kill penalty decision and persistence transaction per Finding 1.
 */
public record KillPenaltyResult(
        int penaltyApplied,
        ReputationEvent reputationEvent,
        PsychosisEvent psychosisEvent
) {
    public boolean wasPenaltyCharged() {
        return penaltyApplied < 0;
    }
}
