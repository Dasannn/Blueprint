package com.dasannn.socialblueprint.feature.effects;

public enum AmbientEffectType {
    SILVERFISH, WHISPER, CREEPER_SOUND, FAKE_ANNOUNCEMENT,
    SKY, PARTICLES, SCREEN_FLASH, SOURCE_LESS_SOUNDS, ADVANCEMENT_TOAST, BOSS_BAR, FALSE_DEATH;

    public String configId() { if (this == WHISPER) return "private-chat"; if (this == FAKE_ANNOUNCEMENT) return "fake-connection"; return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'); }
}
