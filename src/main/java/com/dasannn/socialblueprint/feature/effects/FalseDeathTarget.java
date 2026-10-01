package com.dasannn.socialblueprint.feature.effects;

import java.util.List;
import java.util.UUID;

/** Delivery-time facts only; no player references cross a thread boundary. */
public record FalseDeathTarget(UUID id, String name, boolean sameWorld, boolean visible,
                               boolean alive, boolean vanished, double distanceSquared) {
    public static List<FalseDeathTarget> eligible(UUID recipient, List<FalseDeathTarget> candidates, double range) {
        return candidates.stream().filter(c -> !c.id().equals(recipient) && c.sameWorld() && c.visible()
                && c.alive() && !c.vanished() && Double.isFinite(c.distanceSquared())
                && c.distanceSquared() >= 0 && Math.sqrt(c.distanceSquared()) <= range).toList();
    }
}
