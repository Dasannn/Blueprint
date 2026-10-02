package com.dasannn.socialblueprint.feature.gui;

/**
 * Own enum describing the kind of icon placed in a GUI slot.
 * Separates GUI layout decisions from Bukkit rendering per T-120 and T-121.
 */
public enum GuiIconKind {
    FILLER,
    REVOKE_CONFIRM,
    REVOKE_CANCEL,
    HONOR_CONFIRM,
    HONOR_CANCEL,
    SUBJECT_HEAD,
    TIER_DYE,
    GIVE_BANNER,
    TAKE_BANNER,
    RATER_HEAD,
    REASON_PAPER,
    PAGE_NEXT_STAR,
    PAGE_PREVIOUS_STAR,
    PAGE_INFO,
    DIRECTION_BANNER_POSITIVE,
    DIRECTION_BANNER_NEGATIVE
}
