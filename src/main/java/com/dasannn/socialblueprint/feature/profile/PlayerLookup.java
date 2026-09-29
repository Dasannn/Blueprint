package com.dasannn.socialblueprint.feature.profile;

import com.dasannn.socialblueprint.domain.PlayerId;

import java.util.Objects;
import java.util.Optional;

/**
 * Strategy interface to resolve players from username or UUID string.
 * Decouples platform-specific player lookups (e.g. Bukkit.getOfflinePlayer) from business logic.
 */
@FunctionalInterface
public interface PlayerLookup {

    record KnownPlayer(PlayerId id, String name, boolean isOnline) {
        public KnownPlayer {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    Optional<KnownPlayer> lookup(String nameOrUuid);
}
