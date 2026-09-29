package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Status;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * In-memory status cache per T-019 and ARCHITECTURE.md §4.
 * - Invalidated on write.
 * - Uses per-target and global generation tracking to prevent race conditions where a stale
 *   rebuild could overwrite a newer invalidation or write.
 * - Rebuildable from reputation events at any time via {@link Status#fromEvents(Iterable)}.
 * - It is strictly a cache: nothing depends on it being present.
 */
public final class StatusCache {

    private record Entry(Status status, long generation, long globalGeneration) {}

    private final ConcurrentMap<PlayerId, Entry> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<PlayerId, Long> generations = new ConcurrentHashMap<>();
    private final AtomicLong globalGeneration = new AtomicLong(0);

    public Optional<Status> get(PlayerId id) {
        if (id == null) return Optional.empty();
        Entry entry = cache.get(id);
        if (entry != null && entry.globalGeneration() == globalGeneration.get()) {
            return Optional.of(entry.status());
        }
        return Optional.empty();
    }

    public void put(PlayerId id, Status status) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        long currentGlobal = globalGeneration.get();
        long newGen = generations.compute(id, (k, v) -> (v == null ? 1L : v + 1));
        cache.put(id, new Entry(status, newGen, currentGlobal));
    }

    public void invalidate(PlayerId id) {
        if (id != null) {
            generations.compute(id, (k, v) -> (v == null ? 1L : v + 1));
            cache.remove(id);
        }
    }

    public void invalidateAll() {
        globalGeneration.incrementAndGet();
        cache.clear();
    }

    public boolean isCached(PlayerId id) {
        if (id == null) return false;
        Entry entry = cache.get(id);
        return entry != null && entry.globalGeneration() == globalGeneration.get();
    }

    public int size() {
        long currentGlobal = globalGeneration.get();
        return (int) cache.values().stream().filter(e -> e.globalGeneration() == currentGlobal).count();
    }

    /**
     * Returns the cached status if present, otherwise rebuilds it from the event supplier
     * using {@link Status#fromEvents(Iterable)} and caches the result.
     *
     * Guards against publishing stale data if an invalidation or save occurred while
     * the event supplier was loading.
     */
    public Status getOrRebuild(PlayerId id, Supplier<List<ReputationEvent>> eventSupplier) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(eventSupplier, "Event supplier must not be null");

        long currentGlobal = globalGeneration.get();
        Entry existing = cache.get(id);
        if (existing != null && existing.globalGeneration() == currentGlobal) {
            return existing.status();
        }

        long expectedGen = generations.computeIfAbsent(id, k -> 0L);

        List<ReputationEvent> events = eventSupplier.get();
        Status derived = Status.fromEvents(events);

        // Only cache if neither target generation nor global generation changed during rebuild
        cache.compute(id, (key, cur) -> {
            Long latestGen = generations.get(id);
            if (latestGen != null && latestGen == expectedGen && globalGeneration.get() == currentGlobal) {
                return new Entry(derived, expectedGen, currentGlobal);
            }
            return cur; // Invalidation occurred during rebuild; discard stale value
        });

        return derived;
    }
}
