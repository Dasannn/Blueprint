package com.dasannn.socialblueprint.feature.effects;

import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of active private ambient entities per T-073, SB-099, and ARCHITECTURE.md §5.
 * Strictly cleaned on:
 * 1. Disappearance
 * 2. Player quit
 * 3. Player world change
 * 4. Plugin disable
 * No entity survives any of them.
 */
public class AmbientEntityRegistry {

    private final Set<ActivePresentationEntry> presentations = ConcurrentHashMap.newKeySet();

    public void registerPresentation(ActivePresentationEntry entry) { presentations.add(entry); }

    public void cleanPresentation(ActivePresentationEntry entry) {
        if (presentations.remove(entry)) entry.cleanup();
    }

    public Set<ActivePresentationEntry> presentationsFor(UUID playerId) {
        return presentations.stream().filter(entry -> entry.playerId().equals(playerId))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private final Set<ActiveEntityEntry> activeEntries = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<ActiveEntityEntry>> playerEntries = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveEntityEntry> entityToEntry = new ConcurrentHashMap<>();

    public void register(ActiveEntityEntry entry) {
        Objects.requireNonNull(entry, "ActiveEntityEntry must not be null");
        activeEntries.add(entry);
        if (entry.targetPlayerId() != null) {
            playerEntries.computeIfAbsent(entry.targetPlayerId(), k -> ConcurrentHashMap.newKeySet()).add(entry);
        }
        if (entry.realEntity() != null && entry.realEntity().getUniqueId() != null) {
            entityToEntry.put(entry.realEntity().getUniqueId(), entry);
        }
    }

    public void unregister(ActiveEntityEntry entry) {
        if (entry == null) return;
        activeEntries.remove(entry);
        if (entry.targetPlayerId() != null) {
            Set<ActiveEntityEntry> set = playerEntries.get(entry.targetPlayerId());
            if (set != null) {
                set.remove(entry);
                if (set.isEmpty()) {
                    playerEntries.remove(entry.targetPlayerId());
                }
            }
        }
        if (entry.realEntity() != null && entry.realEntity().getUniqueId() != null) {
            entityToEntry.remove(entry.realEntity().getUniqueId());
        }
    }

    /**
     * Trigger 1: Cleaned on despawn timer.
     */
    public void cleanDespawn(ActiveEntityEntry entry) {
        if (entry == null || !activeEntries.contains(entry)) return;
        unregister(entry);
        entry.cleanup();
    }

    /**
     * Trigger 2: Cleaned on player quit.
     */
    public void cleanForPlayer(UUID playerId) {
        if (playerId == null) return;
        for (ActivePresentationEntry entry : presentationsFor(playerId)) cleanPresentation(entry);
        Set<ActiveEntityEntry> entries = playerEntries.remove(playerId);
        if (entries != null) {
            for (ActiveEntityEntry entry : entries) {
                activeEntries.remove(entry);
                if (entry.realEntity() != null && entry.realEntity().getUniqueId() != null) {
                    entityToEntry.remove(entry.realEntity().getUniqueId());
                }
                entry.cleanup();
            }
        }
    }

    /**
     * Trigger 3: Cleaned on player world change.
     */
    public void cleanForPlayerWorldChange(UUID playerId) {
        cleanForPlayer(playerId);
    }

    /**
     * Trigger 4: Cleaned on plugin disable.
     */
    public void cleanAll() {
        for (ActivePresentationEntry entry : Set.copyOf(presentations)) cleanPresentation(entry);
        List<ActiveEntityEntry> all = new ArrayList<>(activeEntries);
        activeEntries.clear();
        playerEntries.clear();
        entityToEntry.clear();
        for (ActiveEntityEntry entry : all) {
            entry.cleanup();
        }
    }

    public boolean isManaged(Entity entity) {
        return entity != null && entity.getUniqueId() != null && entityToEntry.containsKey(entity.getUniqueId());
    }

    public int getActiveCount() {
        return activeEntries.size();
    }

    public boolean hasActiveEntities(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        Set<ActiveEntityEntry> set = playerEntries.get(playerId);
        return set != null && !set.isEmpty();
    }

    public Set<ActiveEntityEntry> getEntriesForPlayer(UUID playerId) {
        if (playerId == null) {
            return Collections.emptySet();
        }
        Set<ActiveEntityEntry> set = playerEntries.get(playerId);
        return set != null ? Collections.unmodifiableSet(set) : Collections.emptySet();
    }
}
