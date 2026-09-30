package com.dasannn.socialblueprint.domain.duel;

/**
 * Classification of a player disconnect during an active duel per SB-033 and T-063.
 * - COMBAT_LOG: Disconnected within the configured window after taking combat damage.
 * - NORMAL_DISCONNECT: Disconnected outside the combat window or without combat damage.
 */
public enum DisconnectClassification {
    COMBAT_LOG,
    NORMAL_DISCONNECT
}
