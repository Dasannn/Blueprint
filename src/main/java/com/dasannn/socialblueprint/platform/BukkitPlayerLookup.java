package com.dasannn.socialblueprint.platform;

import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bukkit-backed player lookup per T-045 and SB-065.
 * Resolves players by UUID or username, checking online players and falling back to Bukkit offline players.
 */
public final class BukkitPlayerLookup implements PlayerLookup {

    private final Server server;

    public BukkitPlayerLookup(Server server) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
    }

    @Override
    public Optional<KnownPlayer> lookup(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) {
            return Optional.empty();
        }

        String trimmed = nameOrUuid.trim();

        // 1. Try parsing UUID
        try {
            UUID uuid = UUID.fromString(trimmed);
            Player online = server.getPlayer(uuid);
            if (online != null) {
                return Optional.of(new KnownPlayer(PlayerId.of(uuid), online.getName(), true));
            }
            OfflinePlayer offline = server.getOfflinePlayer(uuid);
            String name = offline.getName() != null ? offline.getName() : uuid.toString();
            return Optional.of(new KnownPlayer(PlayerId.of(uuid), name, false));
        } catch (IllegalArgumentException ignored) {
            // Not a UUID, treat as username
        }

        // 2. Check online players by username
        Player online = server.getPlayerExact(trimmed);
        if (online != null) {
            return Optional.of(new KnownPlayer(PlayerId.of(online.getUniqueId()), online.getName(), true));
        }

        // 3. Fallback to Bukkit's offline lookup
        OfflinePlayer offline = server.getOfflinePlayer(trimmed);
        if (offline != null && (offline.hasPlayedBefore() || offline.getName() != null)) {
            String name = offline.getName() != null ? offline.getName() : trimmed;
            return Optional.of(new KnownPlayer(PlayerId.of(offline.getUniqueId()), name, false));
        }

        return Optional.empty();
    }
}
