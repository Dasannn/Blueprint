package com.dasannn.socialblueprint.feature.profile;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.PsychosisConfigSection;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.SerenitySession;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.StorageEngine;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Active/idle accounting for T-201; time alone never changes the mental-state value. */
public final class SerenityService {
    private static final class Online {
        SerenitySession session;
        boolean explicitAfk;
        boolean metadataAfk;
    }
    private final Map<PlayerId, Online> online = new HashMap<>();
    private final Clock clock;
    private final LongSupplier ticker;
    private PsychosisConfigSection config;
    private com.dasannn.socialblueprint.storage.MindRepository mind;
    private java.util.function.Predicate<PlayerId> inDuel = id -> false;
    private final Logger logger;

    public SerenityService(StorageEngine storage, PsychosisRepository repository, ConfigManager manager,
                           Clock clock, LongSupplier ticker, Logger logger) {
        this.clock = clock;
        this.logger = logger;
        this.ticker = ticker;
        this.config = manager.snapshot().config().psychosis();

        manager.addSnapshotListener(snapshot -> reconfigure(snapshot.config().psychosis()));
    }

    public synchronized CompletableFuture<Void> join(PlayerId id) {
        if (online.containsKey(id)) return CompletableFuture.completedFuture(null);
        Online entry = new Online();
        online.put(id, entry);
        entry.session = new SerenitySession(0, null, 0, ticker.getAsLong());
        account(id, 0);
        return CompletableFuture.completedFuture(null);
    }

    public synchronized void bindMind(com.dasannn.socialblueprint.storage.MindRepository mind,
                                      java.util.function.Predicate<PlayerId> inDuel) {
        this.mind = mind;
        this.inDuel = inDuel;
    }

    private void account(PlayerId id, double millis) {
        account(id, millis, clock.instant());
    }
    private void account(PlayerId id, double millis, java.time.Instant activeEnd) {
        if (mind == null || inDuel.test(id)) return;
        // ponytail: persist observed intervals directly; batch at heartbeat if the storage queue grows.
        mind.accountActiveAsync(id, millis, activeEnd, config.cleanDayActiveMinutes(),
                config.input(com.dasannn.socialblueprint.domain.MindInput.CLEAN_DAY), clock.instant())
                .exceptionally(ex -> { logger.log(java.util.logging.Level.SEVERE, "Failed to persist active mind time for " + id, ex); return null; });
    }

    private double advance(PlayerId id, Online entry) {
        if (entry.session == null) return 0;
        double before = entry.session.creditedMillis();
        entry.session.advance(ticker.getAsLong(), clock.instant(), java.time.Duration.ZERO, config.serenity());
        double delta = entry.session.creditedMillis() - before;
        if (delta > 0) account(id, delta, clock.instant().minusMillis((long) Math.ceil(entry.session.creditLagMillis())));
        return delta;
    }

    public synchronized boolean active(PlayerId id) {
        Online entry = online.get(id);
        return entry != null && entry.session != null && entry.session.active(ticker.getAsLong(), config.serenity().idleTimeoutSeconds());
    }

    public synchronized boolean afk(PlayerId id) {
        Online entry = online.get(id);
        return entry == null || entry.explicitAfk || entry.metadataAfk;
    }

    public synchronized void activity(PlayerId id) {
        Online entry = online.get(id);
        if (entry == null || entry.session == null) return;
        advance(id, entry);
        entry.session.activity(ticker.getAsLong());
    }

    /** AFK integrations may call this with a UUID-derived id; no Bukkit object is retained. */
    public synchronized void setAfk(PlayerId id, boolean afk) {
        Online entry = online.get(id);
        if (entry == null) return;
        if (entry.session != null) advance(id, entry);
        entry.explicitAfk = afk;
        if (entry.session != null) entry.session.afk(entry.explicitAfk || entry.metadataAfk);
    }

    public synchronized void setMetadataAfk(PlayerId id, boolean afk) {
        Online entry = online.get(id);
        if (entry == null) return;
        if (entry.session != null) advance(id, entry);
        entry.metadataAfk = afk;
        if (entry.session != null) entry.session.afk(entry.explicitAfk || entry.metadataAfk);
    }

    public synchronized java.util.OptionalDouble onlineCredit(PlayerId id) {
        Online entry = online.get(id);
        if (entry == null) return java.util.OptionalDouble.empty();
        return java.util.OptionalDouble.of(entry.session == null ? 0 : entry.session.creditedMillis());
    }

    public synchronized double creditMillis(PlayerId id, double stored) {
        Online entry = online.get(id);
        return entry != null && entry.session != null ? entry.session.creditedMillis() : 0;
    }

    public synchronized void tick() {
        online.forEach((id, entry) -> { if (advance(id, entry) == 0) account(id, 0); });
    }

    public synchronized CompletableFuture<Void> flush() {
        return CompletableFuture.completedFuture(null);
    }

    public synchronized CompletableFuture<Void> leave(PlayerId id) {
        Online entry = online.remove(id);
        if (entry == null) return CompletableFuture.completedFuture(null);
        advance(id, entry);
        return CompletableFuture.completedFuture(null);
    }

    public synchronized void shutdown() {
        online.forEach(this::advance);
        flush();
        online.clear();
    }

    private synchronized void reconfigure(PsychosisConfigSection next) {
        if (config.equals(next)) return;
        online.forEach(this::advance);
        config = next;
    }
}
