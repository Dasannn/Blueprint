package com.dasannn.socialblueprint.feature.effects;

import java.util.concurrent.atomic.AtomicBoolean;

/** Outgoing packet observer changes only plain flags, never accesses a Player. */
public record ScreenOwnership(AtomicBoolean owned, AtomicBoolean ended) {
    public ScreenOwnership() { this(new AtomicBoolean(false), new AtomicBoolean()); }
    public void claimed() { owned.set(true); }
    public void replaced() { owned.set(false); }
    public boolean mayClear() { return owned.get(); }
}
