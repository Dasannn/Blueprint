package com.dasannn.socialblueprint.domain;

import java.util.Objects;

/**
 * Immutable view of a player's complete social metrics per Constitution §2.3 and SB-001.
 * - Social status (signed integer)
 * - Tier (resolved via TierLadder)
 * - Reputation Confidence (ConfidenceLevel)
 * - Killing Psychosis (PsychosisLevel)
 * - Contributors (distinct actors count)
 *
 * All three metrics stay strictly separate (Constitution §2.3).
 */
public record PlayerSocialView(
        PlayerId playerId,
        String name,
        int status,
        Tier tier,
        ConfidenceLevel confidence,
        PsychosisLevel psychosis,
        int contributors
) {
    public PlayerSocialView {
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(tier, "tier must not be null");
        Objects.requireNonNull(confidence, "confidence must not be null");
        Objects.requireNonNull(psychosis, "psychosis must not be null");
    }

    /**
     * Creates a neutral profile view for a player with no record per SB-005.
     * Status 0, Confidence Unknown, Psychosis Low, 0 contributors.
     * Never negative or suspect.
     */
    public static PlayerSocialView neutral(PlayerId playerId, String name, TierLadder ladder) {
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(ladder, "ladder must not be null");
        String safeName = (name != null && !name.isBlank()) ? name : playerId.toString();
        return new PlayerSocialView(
                playerId,
                safeName,
                0,
                ladder.resolve(0),
                ConfidenceLevel.UNKNOWN,
                PsychosisLevel.LOW,
                0
        );
    }
}
