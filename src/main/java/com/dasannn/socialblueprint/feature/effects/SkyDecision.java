package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.domain.PsychosisLevel;
import java.util.Random;

/** Plain-data sky choice, rerolled once per eligible episode. */
public record SkyDecision(boolean night, boolean storm) {
    public static SkyDecision choose(PsychosisLevel level, String mode, Random random) {
        if (level != PsychosisLevel.HIGH && level != PsychosisLevel.EXTREME)
            return new SkyDecision(false, false);
        return switch (mode) {
            case "night" -> new SkyDecision(true, false);
            case "storm" -> new SkyDecision(false, true);
            case "both" -> new SkyDecision(true, true);
            case "escalating" -> {
                if (level == PsychosisLevel.EXTREME) yield new SkyDecision(true, true);
                boolean night = random.nextBoolean();
                yield new SkyDecision(night, !night);
            }
            default -> throw new IllegalArgumentException("Invalid sky mode: " + mode);
        };
    }
}
