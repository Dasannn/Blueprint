package com.dasannn.socialblueprint.feature.effects;

public enum AmbientEffectType {
    SILVERFISH, WHISPER, CREEPER_SOUND, FAKE_ANNOUNCEMENT,
    SKY, PARTICLES, SCREEN_FLASH, SOURCE_LESS_SOUNDS,
    BLOCK_CHANGE, SIGN, HURT_FLASH, VICTIM_GHOST;

    public com.dasannn.socialblueprint.domain.PsychosisLevel floor() {
        return switch (this) {
            case SKY, BLOCK_CHANGE, SIGN, HURT_FLASH, VICTIM_GHOST, SILVERFISH ->
                    com.dasannn.socialblueprint.domain.PsychosisLevel.HIGH;
            default -> com.dasannn.socialblueprint.domain.PsychosisLevel.MEDIUM;
        };
    }

    public String configId() { return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'); }
}
