package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.DecayConfig;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * In-memory status cache per T-019, ARCHITECTURE.md §4, and P9 Caching.
 * - Invalidated on write.
 * - Bounded accuracy via configured TTL so time-decayed values do not outlive their freshness.
 * - Uses per-target and global generation tracking to prevent race conditions where a stale
 *   rebuild could overwrite a newer invalidation or write.
 * - Rebuildable from reputation events at any time via {@link Status#fromEvents(Iterable)}.
 * - It is strictly a cache: nothing depends on it being present.
 */
public final class StatusCache {

    private record Entry(Status status, Instant expiresAt, long generation, long globalGeneration) {}

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final ConcurrentMap<PlayerId, Entry> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<PlayerId, Long> generations = new ConcurrentHashMap<>();
    private final AtomicLong globalGeneration = new AtomicLong(0);
    private final Duration ttl;
    private final Clock clock;

    public StatusCache(Duration ttl, Clock clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be strictly positive (> 0), got " + ttl);
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public StatusCache(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    public StatusCache() {
        this(DEFAULT_TTL, Clock.systemUTC());
    }

    public Duration ttl() {
        return ttl;
    }

    public Optional<Status> get(PlayerId id) {
        if (id == null) return Optional.empty();
        Entry entry = cache.get(id);
        if (entry != null && entry.globalGeneration() == globalGeneration.get()) {
            if (!clock.instant().isAfter(entry.expiresAt())) {
                return Optional.of(entry.status());
            }
            // Entry expired due to TTL
            cache.remove(id, entry);
        }
        return Optional.empty();
    }

    public void put(PlayerId id, Status status) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        long currentGlobal = globalGeneration.get();
        long newGen = generations.compute(id, (k, v) -> (v == null ? 1L : v + 1));
        Instant expiresAt = clock.instant().plus(ttl);
        cache.put(id, new Entry(status, expiresAt, newGen, currentGlobal));
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
        if (entry != null && entry.globalGeneration() == globalGeneration.get()) {
            return !clock.instant().isAfter(entry.expiresAt());
        }
        return false;
    }

    public int size() {
        long currentGlobal = globalGeneration.get();
        Instant now = clock.instant();
        return (int) cache.values().stream()
                .filter(e -> e.globalGeneration() == currentGlobal && !now.isAfter(e.expiresAt()))
                .count();
    }

    /**
     * Returns the cached status if present and unexpired, otherwise rebuilds it from the event supplier
     * using {@link Status#fromEvents(Iterable)} and caches the result.
     *
     * Guards against publishing stale data if an invalidation or save occurred while
     * the event supplier was loading.
     */
    public Status getOrRebuild(PlayerId id, Supplier<List<ReputationEvent>> eventSupplier) {
        return getOrRebuild(id, eventSupplier, null, null);
    }

    /**
     * Returns the cached status if present and unexpired, otherwise rebuilds it from the event supplier
     * using {@link Status#fromEvents(Iterable, DecayConfig, Instant)} with decay configuration.
     */
    public Status getOrRebuild(PlayerId id, Supplier<List<ReputationEvent>> eventSupplier, DecayConfig decay, Instant now) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(eventSupplier, "Event supplier must not be null");

        long currentGlobal = globalGeneration.get();
        Instant refNow = (now != null) ? now : clock.instant();
        Entry existing = cache.get(id);
        if (existing != null && existing.globalGeneration() == currentGlobal && !refNow.isAfter(existing.expiresAt())) {
            return existing.status();
        }

        long expectedGen = generations.computeIfAbsent(id, k -> 0L);

        List<ReputationEvent> events = eventSupplier.get();
        Status derived = (decay != null && decay.enabled())
                ? Status.fromEvents(events, decay, refNow)
                : Status.fromEvents(events);

        Instant expiresAt = refNow.plus(ttl);

        // Only cache if neither target generation nor global generation changed during rebuild
        cache.compute(id, (key, cur) -> {
            Long latestGen = generations.get(id);
            if (latestGen != null && latestGen == expectedGen && globalGeneration.get() == currentGlobal) {
                return new Entry(derived, expiresAt, expectedGen, currentGlobal);
            }
            return cur; // Invalidation occurred during rebuild; discard stale value
        });

        return derived;
    }
}
