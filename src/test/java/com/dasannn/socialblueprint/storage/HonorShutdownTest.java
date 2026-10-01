package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.domain.CompensationState;
import com.dasannn.socialblueprint.feature.honor.HonorService;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import net.milkbowl.vault.economy.Economy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HonorShutdownTest {
    @TempDir
    File tempDir;

    @Test
    @Timeout(10)
    void shutdownDrainsInFlightClaimsBeforeRevertingUnstartedDeposits() throws Exception {
        try (StorageEngine storage = StorageEngine.inMemory()) {
            storage.runMigrations();
            CompensationRepository compensation = new CompensationRepository(storage);
            long id = compensation.saveCompensationAsync(UUID.randomUUID(), 50.0, "refund", Instant.now()).join();
            Logger logger = Logger.getLogger(HonorShutdownTest.class.getName());
            MessageRegistry messages = new MessageRegistry(tempDir, "en", logger);
            ConfigManager config = new ConfigManager(new File(tempDir, "config.yml"), messages, Runnable::run, logger);
            StatusCache cache = new StatusCache();
            ReputationRepository reputation = new ReputationRepository(storage, cache);
            ProfileService profiles = new ProfileService(storage, reputation, new PsychosisRepository(storage),
                    new ProfileRepository(storage), cache, config, null, logger);
            Economy economy = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(),
                    new Class<?>[]{Economy.class}, (proxy, method, args) -> {
                        throw new AssertionError("Vault must not run during shutdown");
                    });
            Queue<Runnable> deposits = new ConcurrentLinkedQueue<>();
            HonorService honor = new HonorService(config, messages, reputation, new AuditRepository(storage),
                    compensation, profiles, economy, deposits::add, Clock.systemUTC(), uuid -> {
                        throw new AssertionError("An aborted deposit must not resolve a Bukkit player");
                    });

            CountDownLatch dbBlocked = new CountDownLatch(1);
            CountDownLatch releaseDb = new CountDownLatch(1);
            storage.executeAsync(conn -> {
                dbBlocked.countDown();
                try {
                    if (!releaseDb.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Storage executor was not released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
                return null;
            });
            assertThat(dbBlocked.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> reconciliation = honor.reconcileCompensationsAsync();
            CompletableFuture<Void> shutdown = CompletableFuture.runAsync(honor::shutdown);
            try {
                assertThatThrownBy(() -> shutdown.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                releaseDb.countDown();
            }
            shutdown.get(5, TimeUnit.SECONDS);
            reconciliation.get(5, TimeUnit.SECONDS);
            assertThat(compensation.findByIdAsync(id).join().orElseThrow().state())
                    .isEqualTo(CompensationState.CHARGED);
            assertThat(deposits).hasSize(1);
            deposits.remove().run(); // Even a late scheduler delivery cannot refund a reverted claim.
            assertThat(compensation.findByIdAsync(id).join().orElseThrow().state())
                    .isEqualTo(CompensationState.CHARGED);

            // A second pass must not mistake our still-queued refund for a crash.
            honor.reconcileCompensationsAsync().join();
            honor.reconcileCompensationsAsync().join();
            assertThat(compensation.findByIdAsync(id).join().orElseThrow().state())
                    .isEqualTo(CompensationState.REFUNDING);
            assertThat(deposits).hasSize(1);
            honor.shutdown();
            deposits.remove().run();
            assertThat(compensation.findByIdAsync(id).join().orElseThrow().state())
                    .isEqualTo(CompensationState.CHARGED);
        }
    }
}
