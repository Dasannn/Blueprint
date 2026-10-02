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

    public SerenityService(StorageEngine storage, PsychosisRepository repository, ConfigManager manager,
                           Clock clock, LongSupplier ticker, Logger logger) {
        this.clock = clock;
        this.ticker = ticker;
        this.config = manager.snapshot().config().psychosis();

        manager.addSnapshotListener(snapshot -> reconfigure(snapshot.config().psychosis()));
    }

    public synchronized CompletableFuture<Void> join(PlayerId id) {
        if (online.containsKey(id)) return CompletableFuture.completedFuture(null);
        Online entry = new Online();
        online.put(id, entry);
        entry.session = new SerenitySession(0, null, 0, ticker.getAsLong());
        return CompletableFuture.completedFuture(null);
    }

    private void advance(Online entry) {
        if (entry.session != null) entry.session.advance(ticker.getAsLong(), clock.instant(), java.time.Duration.ZERO, config.serenity());
    }

    public synchronized void activity(PlayerId id) {
        Online entry = online.get(id);
        if (entry == null || entry.session == null) return;
        advance(entry);
        entry.session.activity(ticker.getAsLong());
    }

    /** AFK integrations may call this with a UUID-derived id; no Bukkit object is retained. */
    public synchronized void setAfk(PlayerId id, boolean afk) {
        Online entry = online.get(id);
        if (entry == null) return;
        if (entry.session != null) advance(entry);
        entry.explicitAfk = afk;
        if (entry.session != null) entry.session.afk(entry.explicitAfk || entry.metadataAfk);
    }

    public synchronized void setMetadataAfk(PlayerId id, boolean afk) {
        Online entry = online.get(id);
        if (entry == null) return;
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
        for (Online entry : online.values()) advance(entry);
    }

    public synchronized CompletableFuture<Void> flush() {
        return CompletableFuture.completedFuture(null);
    }

    public synchronized CompletableFuture<Void> leave(PlayerId id) {
        Online entry = online.remove(id);
        if (entry == null) return CompletableFuture.completedFuture(null);
        advance(entry);
        return CompletableFuture.completedFuture(null);
    }

    public synchronized void shutdown() {
        for (Online entry : online.values()) advance(entry);
        flush();
        online.clear();
    }

    private synchronized void reconfigure(PsychosisConfigSection next) {
        if (config.equals(next)) return;
        for (Online entry : online.values()) advance(entry);
        config = next;
    }
}
