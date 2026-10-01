package com.dasannn.socialblueprint.feature.effects;

public enum AmbientEffectType {
    SILVERFISH, WHISPER, CREEPER_SOUND, FAKE_ANNOUNCEMENT,
    SKY, PARTICLES, SCREEN_FLASH, SOURCE_LESS_SOUNDS;

    public String configId() { return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'); }
}
