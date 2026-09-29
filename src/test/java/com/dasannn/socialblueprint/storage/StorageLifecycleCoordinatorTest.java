package com.dasannn.socialblueprint.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class StorageLifecycleCoordinatorTest {

    private final Logger logger = Logger.getLogger(StorageLifecycleCoordinatorTest.class.getName());

    @Test
    @DisplayName("Finding 1: start does not block caller thread during connection or migrations")
    void startDoesNotBlockCallerThreadWhenExecutorIsHeld() throws Exception {
        ExecutorService heldExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch holdLatch = new CountDownLatch(1);

        // Hold the single-threaded storage executor with an ongoing task
        heldExecutor.submit(() -> {
            try {
                holdLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        AtomicBoolean mainThreadInvoked = new AtomicBoolean(false);
        AtomicBoolean disabled = new AtomicBoolean(false);
        AtomicReference<StorageEngine> registeredEngine = new AtomicReference<>();

        StorageLifecycleCoordinator coordinator = new StorageLifecycleCoordinator(
                logger,
                runnable -> {
                    mainThreadInvoked.set(true);
                    runnable.run();
                },
                () -> disabled.set(true)
        );

        long start = System.currentTimeMillis();
        CompletableFuture<StorageEngine> future = coordinator.start(
                "jdbc:sqlite::memory:",
                heldExecutor,
                engine -> registeredEngine.set(engine)
        );
        long elapsed = System.currentTimeMillis() - start;

        // Startup must return immediately without waiting on executor or SQLite
        assertThat(elapsed)
                .as("start() must return immediately (< 250ms) and not block on storage executor or migrations")
                .isLessThan(250);

        assertThat(future.isDone())
                .as("Future must remain in-flight while the executor is held")
                .isFalse();
        assertThat(mainThreadInvoked.get())
                .as("Main thread runner must not be called while initialization is in flight")
                .isFalse();
        assertThat(registeredEngine.get())
                .as("Components must not be registered while executor is held")
                .isNull();

        // Release the held task
        holdLatch.countDown();

        // Now initialization should complete cleanly
        StorageEngine engine = future.get(5, TimeUnit.SECONDS);
        assertThat(engine).isNotNull();
        assertThat(mainThreadInvoked.get()).isTrue();
        assertThat(registeredEngine.get()).isSameAs(engine);
        assertThat(disabled.get()).isFalse();

        // Verify that migrations actually ran on the engine
        boolean profileTableExists = engine.execute(conn -> {
            try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, "player_profile", null)) {
                return rs.next();
            }
        });
        assertThat(profileTableExists).isTrue();

        engine.close();
        heldExecutor.shutdownNow();
    }

    @Test
    @DisplayName("Finding 1: failure during storage initialization disables plugin with clear reason")
    void failureDuringInitDisablesPlugin() throws Exception {
        AtomicBoolean disabled = new AtomicBoolean(false);
        AtomicBoolean mainThreadInvoked = new AtomicBoolean(false);
        AtomicBoolean successCalled = new AtomicBoolean(false);

        StorageLifecycleCoordinator coordinator = new StorageLifecycleCoordinator(
                logger,
                runnable -> {
                    mainThreadInvoked.set(true);
                    runnable.run();
                },
                () -> disabled.set(true)
        );

        CompletableFuture<StorageEngine> future = coordinator.start(
                "jdbc:invalid-scheme://nonexistent/path",
                null,
                engine -> successCalled.set(true)
        );

        // Wait for completion without re-throwing the expected failure
        future.handle((res, err) -> null).join();

        assertThat(mainThreadInvoked.get()).isTrue();
        assertThat(disabled.get())
                .as("Plugin must be disabled when database initialization fails")
                .isTrue();
        assertThat(successCalled.get())
                .as("onSuccess must not be called if database initialization fails")
                .isFalse();
    }

    @Test
    @DisplayName("Finding 1: error during component registration closes engine and disables plugin")
    void errorDuringComponentRegistrationDisablesPluginAndClosesEngine() throws Exception {
        AtomicBoolean disabled = new AtomicBoolean(false);
        AtomicReference<StorageEngine> capturedEngine = new AtomicReference<>();

        StorageLifecycleCoordinator coordinator = new StorageLifecycleCoordinator(
                logger,
                Runnable::run,
                () -> disabled.set(true)
        );

        CompletableFuture<StorageEngine> future = coordinator.start(
                "jdbc:sqlite::memory:",
                null,
                engine -> {
                    capturedEngine.set(engine);
                    throw new RuntimeException("Simulated registration error");
                }
        );

        future.handle((res, err) -> null).join();

        assertThat(disabled.get())
                .as("Plugin must be disabled if component registration throws an exception")
                .isTrue();
        assertThat(capturedEngine.get()).isNotNull();
        assertThat(capturedEngine.get().isClosed())
                .as("StorageEngine must be closed if component registration fails")
                .isTrue();
    }
}
