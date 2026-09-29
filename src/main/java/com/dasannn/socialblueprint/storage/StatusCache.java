package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Status;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * In-memory status cache per T-019 and ARCHITECTURE.md §4.
 * - Invalidated on write.
 * - Rebuildable from reputation events at any time via {@link Status#fromEvents(Iterable)}.
 * - It is strictly a cache: nothing depends on it being present.
 */
public final class StatusCache {

    private final ConcurrentMap<PlayerId, Status> cache = new ConcurrentHashMap<>();

    public Optional<Status> get(PlayerId id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(cache.get(id));
    }

    public void put(PlayerId id, Status status) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        cache.put(id, status);
    }

    public void invalidate(PlayerId id) {
        if (id != null) {
            cache.remove(id);
        }
    }

    public void invalidateAll() {
        cache.clear();
    }

    public boolean isCached(PlayerId id) {
        return id != null && cache.containsKey(id);
    }

    public int size() {
        return cache.size();
    }

    /**
     * Returns the cached status if present, otherwise rebuilds it from the event supplier
     * using {@link Status#fromEvents(Iterable)} and caches the result.
     */
    public Status getOrRebuild(PlayerId id, Supplier<List<ReputationEvent>> eventSupplier) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(eventSupplier, "Event supplier must not be null");

        Status existing = cache.get(id);
        if (existing != null) {
            return existing;
        }

        List<ReputationEvent> events = eventSupplier.get();
        Status derived = Status.fromEvents(events);
        cache.put(id, derived);
        return derived;
    }
}
