package com.dasannn.socialblueprint.domain.duel;

import com.dasannn.socialblueprint.domain.PlayerId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Tracks an ongoing active duel session in memory.
 * Pure Java domain class, no Bukkit dependencies (ARCHITECTURE.md §3).
 */
public final class ActiveDuelSession {

    private final String id;
    private final Map<String, Set<PlayerId>> sides; // side name -> original players
    private final Set<PlayerId> remainingPlayers; // players still active/alive in the duel
    private final Map<PlayerId, Instant> disconnectedPlayers; // players disconnected and when
    private final Instant createdAt;

    public ActiveDuelSession(
            String id,
            Map<String, Set<PlayerId>> sides,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sides, "sides must not be null");
        Map<String, Set<PlayerId>> copy = new HashMap<>();
        Set<PlayerId> all = new HashSet<>();
        for (Map.Entry<String, Set<PlayerId>> entry : sides.entrySet()) {
            Set<PlayerId> s = Collections.unmodifiableSet(new HashSet<>(entry.getValue()));
            for (PlayerId member : s) {
                if (!all.add(member)) {
                    throw new IllegalArgumentException("Player cannot be on multiple sides: " + member);
                }
            }
            copy.put(entry.getKey(), s);
        }
        this.sides = Collections.unmodifiableMap(copy);
        this.remainingPlayers = new HashSet<>(all);
        this.disconnectedPlayers = new HashMap<>();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public String id() {
        return id;
    }

    public Map<String, Set<PlayerId>> sides() {
        return sides;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Set<PlayerId> allParticipants() {
        Set<PlayerId> all = new HashSet<>();
        for (Set<PlayerId> set : sides.values()) {
            all.addAll(set);
        }
        return all;
    }

    public Set<PlayerId> remainingPlayers() {
        return Collections.unmodifiableSet(remainingPlayers);
    }

    public boolean isParticipant(PlayerId player) {
        return allParticipants().contains(player);
    }

    public boolean isRemaining(PlayerId player) {
        return remainingPlayers.contains(player);
    }

    public String getSide(PlayerId player) {
        for (Map.Entry<String, Set<PlayerId>> entry : sides.entrySet()) {
            if (entry.getValue().contains(player)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public boolean areOpponents(PlayerId p1, PlayerId p2) {
        String s1 = getSide(p1);
        String s2 = getSide(p2);
        return s1 != null && s2 != null && !s1.equals(s2);
    }

    /**
     * Eliminates a player from the duel (death, surrender/leave, or combat log).
     * Returns true if the duel has concluded as a result of this elimination.
     */
    public boolean eliminate(PlayerId player) {
        remainingPlayers.remove(player);
        disconnectedPlayers.remove(player);
        return isConcluded();
    }

    /**
     * Marks a player as disconnected during the duel.
     */
    public void markDisconnected(PlayerId player, Instant quitTime) {
        if (remainingPlayers.contains(player)) {
            disconnectedPlayers.put(player, quitTime);
        }
    }

    /**
     * Marks a player as reconnected.
     */
    public void markReconnected(PlayerId player) {
        disconnectedPlayers.remove(player);
    }

    public boolean isDisconnected(PlayerId player) {
        return disconnectedPlayers.containsKey(player);
    }

    public Optional<Instant> getDisconnectTime(PlayerId player) {
        return Optional.ofNullable(disconnectedPlayers.get(player));
    }

    /**
     * A duel is concluded if at most one side has remaining players.
     */
    public boolean isConcluded() {
        int activeSidesCount = 0;
        for (Map.Entry<String, Set<PlayerId>> entry : sides.entrySet()) {
            boolean sideHasRemaining = false;
            for (PlayerId member : entry.getValue()) {
                if (remainingPlayers.contains(member)) {
                    sideHasRemaining = true;
                    break;
                }
            }
            if (sideHasRemaining) {
                activeSidesCount++;
            }
        }
        return activeSidesCount <= 1;
    }

    /**
     * Returns the winning side, if exactly one side remains.
     */
    public Optional<String> winningSide() {
        String winner = null;
        for (Map.Entry<String, Set<PlayerId>> entry : sides.entrySet()) {
            for (PlayerId member : entry.getValue()) {
                if (remainingPlayers.contains(member)) {
                    if (winner != null && !winner.equals(entry.getKey())) {
                        return Optional.empty(); // Multiple sides still active
                    }
                    winner = entry.getKey();
                    break;
                }
            }
        }
        return Optional.ofNullable(winner);
    }

    /**
     * Returns the winning players.
     */
    public Set<PlayerId> winningPlayers() {
        Optional<String> side = winningSide();
        if (side.isEmpty()) {
            return Collections.emptySet();
        }
        Set<PlayerId> sideMembers = sides.get(side.get());
        if (sideMembers == null) {
            return Collections.emptySet();
        }
        Set<PlayerId> winners = new HashSet<>();
        for (PlayerId member : sideMembers) {
            if (remainingPlayers.contains(member)) {
                winners.add(member);
            }
        }
        return winners;
    }

    public List<DuelParticipant> toParticipantList() {
        List<DuelParticipant> list = new ArrayList<>();
        for (Map.Entry<String, Set<PlayerId>> entry : sides.entrySet()) {
            for (PlayerId player : entry.getValue()) {
                list.add(new DuelParticipant(player, entry.getKey()));
            }
        }
        return list;
    }
}
