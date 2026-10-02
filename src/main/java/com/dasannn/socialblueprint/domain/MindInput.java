package com.dasannn.socialblueprint.domain;

import java.util.Locale;

public enum MindInput {
    KILL(true, 25, 10, 0), DEATH(true, 10, 6, 0), NEAR_DEATH(true, 3, 2, 0),
    SLEEPLESS_NIGHT(true, 2, 2, 0), CLEAN_DAY(false, 1, 1, 1), SLEEP(false, 0.5, 1, 2),
    FISHING(false, 0.02, 0.02, 25), BREEDING(false, 0.02, 0.02, 25),
    FEEDING(false, 0.02, 0.02, 25), PLANTING(false, 0.02, 0.02, 25), HARVESTING(false, 0.02, 0.02, 25);

    private final boolean bad;
    private final MindInputConfig defaults;
    MindInput(boolean bad, double sereneAmount, double psychosisAmount, int cap) {
        this.bad = bad;
        this.defaults = new MindInputConfig(true, sereneAmount, psychosisAmount, cap);
    }
    public boolean bad() { return bad; }
    public String id() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    public MindInputConfig defaults() { return defaults; }
    public boolean peaceful() { return ordinal() >= FISHING.ordinal(); }
}
