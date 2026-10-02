package com.dasannn.socialblueprint.domain;

import java.time.Instant;
import java.time.Duration;

/** Plain active/idle accounting, not a mental-state value. A new session has no qualifying activity. */
public final class SerenitySession {
    private double creditedMillis;
    private long checkedAt;
    private Long lastAction;
    private Instant lastKill;
    private long killId;
    private boolean afk;

    public SerenitySession(double credit, Instant lastKill, long killId, long now) {
        this.creditedMillis = credit;
        this.lastKill = lastKill;
        this.killId = killId;
        this.checkedAt = now;
    }

    public void advance(long now, Instant wallNow, Duration window, SerenityConfig config) {
        long elapsed = Math.max(0, now - checkedAt);
        // ponytail: cap heartbeat gaps at one second; add finer sampling if lag under-credit matters.
        long start = now - Math.min(elapsed, 1000);
        if (!afk && lastAction != null) {
            double end = Math.min(now, lastAction + config.idleTimeoutSeconds() * 1000);
            creditedMillis += Math.max(0, end - start);
        }
        checkedAt = now;
    }

    public void activity(long now) { if (!afk) lastAction = now; }
    public void afk(boolean value) { afk = value; if (value) lastAction = null; }
    public void kill(Instant when, long id) {
        creditedMillis = 0;
        if (lastKill == null || when.isAfter(lastKill)) lastKill = when;
        killId = Math.max(killId, id);
    }
    public double creditedMillis() { return creditedMillis; }
    public long killId() { return killId; }
}
