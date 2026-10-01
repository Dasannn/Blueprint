package com.dasannn.socialblueprint.feature.effects;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plain lifecycle record shared by expiry, interruption, quit, world change and stop. */
public record ActivePresentationEntry(UUID playerId, AmbientEffectType type, long durationTicks,
                                      Runnable restore, AtomicBoolean ended) {
    public ActivePresentationEntry(UUID playerId, AmbientEffectType type, long durationTicks, Runnable restore) {
        this(playerId, type, durationTicks, restore, new AtomicBoolean());
    }
    public void cleanup() {
        if (ended.compareAndSet(false, true)) {
            try { restore.run(); }
            catch (RuntimeException ignored) {} // one failed renderer must not prevent other managed cleanup
        }
    }
}
