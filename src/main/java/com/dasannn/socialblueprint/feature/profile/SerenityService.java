package com.dasannn.socialblueprint.feature.profile;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.PsychosisConfigSection;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.domain.SerenitySession;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.StorageEngine;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Only plain values cross the storage boundary. Session timestamps are never persisted. */
public final class SerenityService {
    private static final class Online {
        SerenitySession session;
        PsychosisEvent pendingKill;
        boolean explicitAfk;
        boolean metadataAfk;
    }
    private final Map<PlayerId, Online> online = new HashMap<>();
    private final StorageEngine storage;
    private final PsychosisRepository repository;
    private final Clock clock;
    private final LongSupplier ticker;
    private final Logger logger;
    private PsychosisConfigSection config;
    private long lastFlush;

    public SerenityService(StorageEngine storage, PsychosisRepository repository, ConfigManager manager,
                           Clock clock, LongSupplier ticker, Logger logger) {
        this.storage = storage;
        this.repository = repository;
        this.clock = clock;
        this.ticker = ticker;
        this.logger = logger;
        this.config = manager.snapshot().config().psychosis();
        this.lastFlush = ticker.getAsLong();
        repository.addKillListener(this::kill);
        manager.addSnapshotListener(snapshot -> reconfigure(snapshot.config().psychosis()));
    }

    public synchronized CompletableFuture<Void> join(PlayerId id) {
        if (online.containsKey(id)) return CompletableFuture.completedFuture(null);
        Online entry = new Online();
        online.put(id, entry);
        return storage.supplyAsync(() -> repository.loadStreak(id)).thenAccept(streak -> {
            synchronized (this) {
                if (online.get(id) != entry) return;
                entry.session = new SerenitySession(config.serenity().clamp(streak.activeMillis()),
                        streak.lastKillAt(), streak.lastKillId(), ticker.getAsLong());
                if (entry.pendingKill != null) entry.session.kill(entry.pendingKill.createdAt(), entry.pendingKill.id());
                entry.session.afk(entry.explicitAfk || entry.metadataAfk);
            }
        }).whenComplete((ignored, error) -> {
            if (error != null) logger.log(Level.WARNING, "Could not load serenity credit for " + id, error);
        });
    }

    private void advance(Online entry) {
        if (entry.session != null) entry.session.advance(ticker.getAsLong(), clock.instant(), config.window(), config.serenity());
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

    private synchronized void kill(PsychosisEvent event) {
        Online entry = online.get(event.killer());
        if (entry == null) return;
        entry.pendingKill = event;
        if (entry.session != null) entry.session.kill(event.createdAt(), event.id());
    }

    public synchronized double creditMillis(PlayerId id, double stored) {
        Online entry = online.get(id);
        return config.serenity().clamp(entry != null && entry.session != null ? entry.session.creditedMillis() : stored);
    }

    public synchronized void tick() {
        for (Online entry : online.values()) advance(entry);
        long now = ticker.getAsLong();
        if (now - lastFlush >= TimeUnit.SECONDS.toMillis(30)) {
            flush();
            lastFlush = now;
        }
    }

    private CompletableFuture<Void> save(PlayerId id, Online entry) {
        if (entry.session == null) return CompletableFuture.completedFuture(null);
        return repository.saveStreakAsync(id, entry.session.creditedMillis(), entry.session.killId())
                .whenComplete((ignored, error) -> {
                    if (error != null) logger.log(Level.WARNING, "Could not persist serenity credit for " + id, error);
                });
    }

    public synchronized CompletableFuture<Void> flush() {
        var writes = new java.util.ArrayList<CompletableFuture<Void>>();
        online.forEach((id, entry) -> writes.add(save(id, entry)));
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    public synchronized CompletableFuture<Void> leave(PlayerId id) {
        Online entry = online.remove(id);
        if (entry == null) return CompletableFuture.completedFuture(null);
        advance(entry);
        return save(id, entry);
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
        for (Online entry : online.values()) advance(entry); // clamp without crediting elapsed time twice
        repository.clampStreaksAsync(next.serenity().activeHoursToCeiling() * 3_600_000)
                .whenComplete((ignored, error) -> {
                    if (error != null) logger.log(Level.WARNING, "Could not clamp persisted serenity credit", error);
                });
        flush();
    }
}
