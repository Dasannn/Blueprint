package com.dasannn.socialblueprint.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Session-only baselines, initialized from the first loaded value. */
public final class MindNotices {
    private double psychosis;
    private double serenity;
    public MindNotices(double value) { psychosis = Math.max(0, -value); serenity = Math.max(0, value); }
    public record Notice(String key, Map<String, String> values) {}
    public List<Notice> update(double value, double step, boolean enabled, boolean rises, boolean falls) {
        List<Notice> notices = new ArrayList<>();
        psychosis = check("psychosis", psychosis, Math.max(0, -value), step, enabled, rises, falls, notices);
        serenity = check("serenity", serenity, Math.max(0, value), step, enabled, rises, falls, notices);
        return List.copyOf(notices);
    }
    private static double check(String half, double baseline, double current, double step, boolean enabled, boolean rises, boolean falls, List<Notice> notices) {
        double delta = current - baseline;
        if (!enabled || delta > 0 && !rises || delta < 0 && !falls) return current;
        if (Math.abs(delta) >= step) {
            notices.add(new Notice("mind-notices." + half + (delta > 0 ? "-rose" : "-fell"),
                    Map.of("change", String.format(Locale.ROOT, "%.1f", Math.abs(delta)),
                           "now", String.format(Locale.ROOT, "%.1f", current))));
            return current;
        }
        return baseline;
    }
}
