package com.dasannn.socialblueprint.feature.legacy;

import com.dasannn.socialblueprint.domain.PlayerId;

import java.util.Optional;

/**
 * Strategy interface to resolve legacy player names or UUID strings to {@link PlayerId} per T-090 and SB-060.
 * Off-thread I/O resolution: unresolvable players must be skipped and reported, never imported against a guessed identity.
 */
@FunctionalInterface
public interface LegacyPlayerResolver {
    Optional<PlayerId> resolve(String nameOrUuid);
}
