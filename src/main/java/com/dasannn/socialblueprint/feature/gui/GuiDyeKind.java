package com.dasannn.socialblueprint.feature.gui;

import com.dasannn.socialblueprint.domain.Tier;

/**
 * Plain-data descriptor for the dye color of a reputation tier in the GUI (Finding 8, T-120).
 * Decided in the service layout computation rather than in the Bukkit renderer.
 */
public enum GuiDyeKind {
    WHITE,
    LIME,
    LIGHT_BLUE,
    RED;

    public static GuiDyeKind fromTier(Tier tier) {
        if (tier == null || tier.isNeutral()) {
            return WHITE;
        }
        if (tier.isNegative()) {
            return RED;
        }
        if (tier == Tier.ILUSTRE) {
            return LIGHT_BLUE;
        }
        return LIME;
    }
}
