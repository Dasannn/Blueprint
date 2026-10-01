package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.PresentationConfig;

/** Paper has no grant-free toast API. This description never changes advancement progress. */
public record ToastDecision(String messageKey, String icon, int durationTicks) {
    public static ToastDecision describe(String messageKey, PresentationConfig.Toast config) {
        return new ToastDecision(messageKey, config.icon(), config.durationTicks());
    }
    public boolean deliveryAvailable() { return false; }
}
