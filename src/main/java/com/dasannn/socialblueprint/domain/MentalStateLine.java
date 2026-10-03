package com.dasannn.socialblueprint.domain;

import java.util.Locale;
import java.util.Map;

/** Plain-data display decision for the single mental-state metric (SB-138). */
public record MentalStateLine(String key, String levelKey, Map<String, String> placeholders) {
    public static MentalStateLine of(PsychosisLevel level, double magnitude, String prefix, boolean detail) {
        return switch (level) {
            case NEUTRAL -> new MentalStateLine(prefix + "-neutral", null, Map.of());
            case SERENITY -> new MentalStateLine(prefix + "-serenity", null,
                    Map.of("value", MindNumbers.format(magnitude)));
            default -> new MentalStateLine(prefix + (detail ? "-psychosis-detail" : "-psychosis"),
                    "psychosis." + level.name().toLowerCase(Locale.ROOT),
                    Map.of("value", MindNumbers.format(magnitude)));
        };
    }
}
