package com.dasannn.socialblueprint.domain.duel;

import com.dasannn.socialblueprint.domain.PlayerId;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tracks a pending duel challenge before all sides have consented.
 * Per SB-030 and T-060:
 * - A challenge nobody accepts expires on a configurable timer and leaves no trace.
 * - Group duels require EVERY member's consent: every invited participant on each side
 *   must explicitly consent before the duel transitions to active.
 */
public final class DuelChallenge {

    private final String id;
    private final PlayerId challenger;
    private final Map<String, Set<PlayerId>> sides; // side name -> set of players
    private final Set<PlayerId> acceptedPlayers;
    private final Instant createdAt;
    private final Instant expiresAt;

    public DuelChallenge(
            String id,
            PlayerId challenger,
            Map<String, Set<PlayerId>> sides,
            Instant createdAt,
            Instant expiresAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.challenger = Objects.requireNonNull(challenger, "challenger must not be null");
        Objects.requireNonNull(sides, "sides must not be null");
        if (sides.size() < 2) {
            throw new IllegalArgumentException("Duel requires at least two sides");
        }

        Map<String, Set<PlayerId>> copy = new HashMap<>();
        for (Map.Entry<String, Set<PlayerId>> entry : sides.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                throw new IllegalArgumentException("Side cannot be empty: " + entry.getKey());
            }
            copy.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<>(entry.getValue())));
        }
        this.sides = Collections.unmodifiableMap(copy);
        this.acceptedPlayers = new HashSet<>();
        // Challenger automatically consents to their own challenge upon creation
        this.acceptedPlayers.add(challenger);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public String id() {
        return id;
    }

    public PlayerId challenger() {
        return challenger;
    }

    public Map<String, Set<PlayerId>> sides() {
        return sides;
    }

    public Set<PlayerId> acceptedPlayers() {
        return Collections.unmodifiableSet(acceptedPlayers);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Set<PlayerId> allParticipants() {
        Set<PlayerId> all = new HashSet<>();
        for (Set<PlayerId> set : sides.values()) {
            all.addAll(set);
        }
        return all;
    }

    public boolean isParticipant(PlayerId player) {
        return allParticipants().contains(player);
    }

    public boolean accept(PlayerId player) {
        if (!isParticipant(player)) {
            return false;
        }
        return acceptedPlayers.add(player);
    }

    public boolean hasExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    /**
     * Group duel consent policy (T-060):
     * A group duel requires EVERY individual member's explicit consent before starting.
     * Per SB-030 and Constitution §2.1, combat is only exempt from status and psychosis
     * tracking when it is truly consensual for all participants. SocialBlueprint has no
     * guild/party hierarchy, so no single player or "leader" may consent on behalf of
     * another player. If any invited participant denies or fails to accept before expiry,
     * the challenge is cancelled/expired and no duel begins.
     *
     * @return true if every participant on every side has explicitly accepted
     */
    public boolean hasEveryMemberConsented() {
        return acceptedPlayers.containsAll(allParticipants());
    }

    public boolean is1v1() {
        return allParticipants().size() == 2 && sides.size() == 2;
    }
}
