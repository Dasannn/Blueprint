package com.dasannn.socialblueprint.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One world's current night, kept only in memory. Samples never invent missed time. */
public final class MindNight {
    private static final class Presence {
        double activeMillis;
        boolean slept;
        boolean deep;
    }
    public record Credit(PlayerId player, MindInput kind) {}
    private final Map<PlayerId, Presence> players = new HashMap<>();
    private final long startTick;
    private final long endTick;
    private Long night;

    public MindNight(long startTick, long endTick) {
        this.startTick = startTick; this.endTick = endTick;
    }
    public boolean isNight(long time) { return Math.floorMod(time, 24000) >= startTick && Math.floorMod(time, 24000) < endTick; }
    public List<Credit> time(long time) {
        long day = Math.floorDiv(time, 24000);
        List<Credit> result = List.of();
        if (night != null && (!isNight(time) || night != day)) result = finish();
        if (night == null && isNight(time)) night = day;
        return result;
    }
    public void presence(PlayerId player, double activeMillis, boolean sleeping, boolean deep, boolean eligible) {
        if (night == null) return;
        Presence p = players.computeIfAbsent(player, ignored -> new Presence());
        p.activeMillis += Math.max(0, activeMillis);
        if (sleeping) p.slept = true;
        if (!sleeping) p.deep = false;
        else if (deep && eligible) p.deep = true;
    }
    public void wake(PlayerId player) {
        Presence p = players.get(player);
        if (p != null) p.deep = false;
    }
    public List<Credit> finish() {
        List<Credit> credits = new java.util.ArrayList<>();
        double halfNightMillis = (endTick - startTick) * 50d / 2;
        players.forEach((player, p) -> {
            if (p.deep) credits.add(new Credit(player, MindInput.SLEEP));
            else if (!p.slept && p.activeMillis >= halfNightMillis) credits.add(new Credit(player, MindInput.SLEEPLESS_NIGHT));
        });
        players.clear(); night = null;
        return List.copyOf(credits);
    }
}
