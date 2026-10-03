package com.dasannn.socialblueprint.feature.effects;

public enum AmbientEffectType {
    SILVERFISH, WHISPER, CREEPER_SOUND, FAKE_ANNOUNCEMENT,
    SKY, PARTICLES, SCREEN_FLASH, SOURCE_LESS_SOUNDS,
    BLOCK_CHANGE, SIGN, HURT_FLASH, VICTIM_GHOST,
    ADVANCEMENT_TOAST, BOSS_BAR, FALSE_DEATH,
    FOOTSTEPS, WATCHER, NEARBY_NOISES, TORCH_FLICKER, SUBLIMINAL, RED_VIGNETTE, FAKE_LIGHTNING;

    public com.dasannn.socialblueprint.domain.PsychosisLevel floor() {
        return switch (this) {
            case FAKE_LIGHTNING -> com.dasannn.socialblueprint.domain.PsychosisLevel.EXTREME;
            case WATCHER, RED_VIGNETTE, SKY, BLOCK_CHANGE, SIGN, HURT_FLASH, VICTIM_GHOST, FALSE_DEATH, SILVERFISH ->
                    com.dasannn.socialblueprint.domain.PsychosisLevel.HIGH;
            case FOOTSTEPS, NEARBY_NOISES, PARTICLES, SCREEN_FLASH, SOURCE_LESS_SOUNDS, FAKE_ANNOUNCEMENT, BOSS_BAR, WHISPER ->
                    com.dasannn.socialblueprint.domain.PsychosisLevel.LOW;
            default -> com.dasannn.socialblueprint.domain.PsychosisLevel.MEDIUM;
        };
    }

    public String configId() { if (this == CREEPER_SOUND) return "creeper"; if (this == WHISPER) return "private-chat"; if (this == FAKE_ANNOUNCEMENT) return "fake-connection"; return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'); }
}
