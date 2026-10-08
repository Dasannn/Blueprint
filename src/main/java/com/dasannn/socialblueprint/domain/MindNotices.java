package com.dasannn.socialblueprint.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Session-only baselines, initialized from the first loaded value. */
public final class MindNotices {
    private double psychosis;
    private double serenity;
    private PsychosisLevel level;
    public MindNotices(double value) { psychosis = Math.max(0, -value); serenity = Math.max(0, value); }
    public MindNotices(double value, PsychosisLevel level) {
        this(value);
        this.level = level;
    }
    public String updateLevel(PsychosisLevel current, boolean enabled, boolean disabledWorld) {
        PsychosisLevel before = level;
        level = current;
        return before == null || !enabled || disabledWorld ? null : levelKey(before, current);
    }
    public static String levelKey(PsychosisLevel before, PsychosisLevel after) {
        if (before == after || after == PsychosisLevel.SERENITY
                || before == PsychosisLevel.SERENITY && after == PsychosisLevel.NEUTRAL) return null;
        return "mind-levels.psychosis-" + (after.ordinal() > before.ordinal() ? "rose." : "fell.")
                + after.name().toLowerCase(java.util.Locale.ROOT) + ".lines";
    }
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
                    Map.of("change", MindNumbers.format(Math.abs(delta)),
                           "now", MindNumbers.format(current))));
            return current;
        }
        return baseline;
    }
}
