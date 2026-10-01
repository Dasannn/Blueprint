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

    private record Entry(Status status, Instant expiresAt, long generation, long globalGeneration, DecayConfig decay) {}

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final ConcurrentMap<PlayerId, Entry> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<PlayerId, Long> generations = new ConcurrentHashMap<>();
    private final AtomicLong globalGeneration = new AtomicLong(0);
    private final Supplier<DecayConfig> defaultDecaySupplier;
    private volatile Duration ttl;
    private final Clock clock;

    public StatusCache(Duration ttl, Clock clock, Supplier<DecayConfig> defaultDecaySupplier) {
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be strictly positive (> 0), got " + ttl);
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.defaultDecaySupplier = defaultDecaySupplier != null ? defaultDecaySupplier : () -> DecayConfig.defaults();
    }

    public StatusCache(Duration ttl, Clock clock) {
        this(ttl, clock, () -> DecayConfig.defaults());
    }

    public StatusCache(Duration ttl) {
        this(ttl, Clock.systemUTC(), () -> DecayConfig.defaults());
    }

    public StatusCache() {
        this(DEFAULT_TTL, Clock.systemUTC(), () -> DecayConfig.defaults());
    }

    public Duration ttl() {
        return ttl;
    }

    public void updateTtl(Duration newTtl) {
        Objects.requireNonNull(newTtl, "newTtl must not be null");
        if (newTtl.isNegative() || newTtl.isZero()) {
            throw new IllegalArgumentException("ttl must be strictly positive (> 0), got " + newTtl);
        }
        this.ttl = newTtl;
    }

    private static boolean isCompatible(DecayConfig c1, DecayConfig c2) {
        boolean e1 = (c1 != null && c1.enabled());
        boolean e2 = (c2 != null && c2.enabled());
        if (!e1 && !e2) {
            return true;
        }
        if (e1 != e2) {
            return false;
        }
        return c1.halfLife().equals(c2.halfLife()) && Double.compare(c1.floor(), c2.floor()) == 0;
    }

    public Optional<Status> get(PlayerId id) {
        return get(id, null);
    }

    public Optional<Status> get(PlayerId id, DecayConfig decay) {
        if (id == null) return Optional.empty();
        Entry entry = cache.get(id);
        if (entry != null 
                && entry.globalGeneration() == globalGeneration.get()
                && entry.generation() == generations.getOrDefault(id, 0L)) {
            if (!clock.instant().isAfter(entry.expiresAt())) {
                if (decay == null || isCompatible(entry.decay(), decay)) {
                    return Optional.of(entry.status());
                }
            } else {
                // Entry expired due to TTL
                cache.remove(id, entry);
            }
        }
        return Optional.empty();
    }

    public void put(PlayerId id, Status status) {
        put(id, status, null);
    }

    public void put(PlayerId id, Status status, DecayConfig decay) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        long currentGlobal = globalGeneration.get();
        long newGen = generations.compute(id, (k, v) -> (v == null ? 1L : v + 1));
        Instant expiresAt = clock.instant().plus(ttl);
        cache.put(id, new Entry(status, expiresAt, newGen, currentGlobal, decay));
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
        return isCached(id, null);
    }

    public boolean isCached(PlayerId id, DecayConfig decay) {
        if (id == null) return false;
        Entry entry = cache.get(id);
        if (entry != null 
                && entry.globalGeneration() == globalGeneration.get()
                && entry.generation() == generations.getOrDefault(id, 0L)) {
            if (!clock.instant().isAfter(entry.expiresAt())) {
                return decay == null || isCompatible(entry.decay(), decay);
            }
        }
        return false;
    }

    public int size() {
        long currentGlobal = globalGeneration.get();
        Instant now = clock.instant();
        return (int) cache.entrySet().stream()
                .filter(e -> e.getValue().globalGeneration() == currentGlobal
                        && e.getValue().generation() == generations.getOrDefault(e.getKey(), 0L)
                        && !now.isAfter(e.getValue().expiresAt()))
                .count();
    }

    /**
     * Returns the cached status if present and unexpired, otherwise rebuilds it from the event supplier
     * using the canonical status derivation and caches the result.
     *
     * Guards against publishing stale data if an invalidation or save occurred while
     * the event supplier was loading.
     */
    public Status getOrRebuild(PlayerId id, Supplier<List<ReputationEvent>> eventSupplier) {
        return getOrRebuild(id, eventSupplier, defaultDecaySupplier.get(), clock.instant());
    }

    /**
     * Returns the cached status if present and unexpired, otherwise rebuilds it from the event supplier
     * using {@link Status#fromEvents(Iterable, DecayConfig, Instant)} with decay configuration.
     *
     * If the generation moves while the event supplier is running, reloads to ensure that no stale
     * value is ever returned or stored.
     */
    public Status getOrRebuild(PlayerId id, Supplier<List<ReputationEvent>> eventSupplier, DecayConfig decay, Instant now) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        Objects.requireNonNull(eventSupplier, "Event supplier must not be null");

        while (true) {
            long currentGlobal = globalGeneration.get();
            Instant refNow = (now != null) ? now : clock.instant();
            long currentGen = generations.getOrDefault(id, 0L);
            Entry existing = cache.get(id);
            if (existing != null 
                    && existing.globalGeneration() == currentGlobal 
                    && existing.generation() == currentGen
                    && !refNow.isAfter(existing.expiresAt())
                    && isCompatible(existing.decay(), decay)) {
                return existing.status();
            }

            long expectedGen = generations.computeIfAbsent(id, k -> 0L);
            currentGlobal = globalGeneration.get();

            List<ReputationEvent> events = eventSupplier.get();

            long postLoadGen = generations.getOrDefault(id, 0L);
            long postGlobalGen = globalGeneration.get();
            if (postLoadGen != expectedGen || postGlobalGen != currentGlobal) {
                // Generation moved while loading; reload to ensure fresh derived status
                continue;
            }

            Status derived = (decay != null && decay.enabled())
                    ? Status.fromEvents(events, decay, refNow)
                    : Status.fromEvents(events);

            Instant expiresAt = refNow.plus(ttl);
            Entry newEntry = new Entry(derived, expiresAt, expectedGen, currentGlobal, decay);

            // currentGlobal is reassigned by the retry loop, so it is not effectively
            // final; the lambda needs its own copy of this attempt's value.
            final long globalAtLoad = currentGlobal;
            Entry stored = cache.compute(id, (key, cur) -> {
                Long latestGen = generations.get(id);
                if (latestGen != null && latestGen == expectedGen && globalGeneration.get() == globalAtLoad) {
                    return newEntry;
                }
                return cur;
            });

            if (stored == newEntry) {
                return derived;
            }
            // Generation moved right before storing; loop and reload
        }
    }
}
