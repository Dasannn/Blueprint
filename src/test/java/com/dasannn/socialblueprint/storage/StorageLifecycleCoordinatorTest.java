package com.dasannn.socialblueprint.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    @Test
    @DisplayName("Round 2 Finding 3: Failed migration disables plugin and closes the opened storage engine")
    void failedMigrationClosesEngineAndDisablesPlugin() throws Exception {
        AtomicBoolean disabled = new AtomicBoolean(false);
        AtomicReference<java.sql.Connection> connectionRef = new AtomicReference<>();

        StorageLifecycleCoordinator coordinator = new StorageLifecycleCoordinator(
                logger,
                Runnable::run,
                () -> disabled.set(true)
        );

        Migration failingMigration = new Migration() {
            @Override
            public int version() { return 1; }
            @Override
            public String description() { return "Failing migration"; }
            @Override
            public void apply(java.sql.Connection conn) throws java.sql.SQLException {
                java.sql.Connection raw = conn;
                if (java.lang.reflect.Proxy.isProxyClass(conn.getClass())) {
                    java.lang.reflect.InvocationHandler ih = java.lang.reflect.Proxy.getInvocationHandler(conn);
                    for (java.lang.reflect.Field f : ih.getClass().getDeclaredFields()) {
                        f.setAccessible(true);
                        try {
                            Object val = f.get(ih);
                            if (val instanceof java.sql.Connection c && !java.lang.reflect.Proxy.isProxyClass(c.getClass())) {
                                raw = c;
                                break;
                            }
                        } catch (IllegalAccessException ignored) {
                        }
                    }
                }
                connectionRef.set(raw);
                throw new java.sql.SQLException("Simulated migration failure");
            }
        };
        MigrationRunner failingRunner = new MigrationRunner(List.of(failingMigration));

        CompletableFuture<StorageEngine> future = coordinator.start(
                "jdbc:sqlite::memory:",
                null,
                failingRunner,
                engine -> {}
        );

        future.handle((res, err) -> null).join();

        assertThat(disabled.get()).isTrue();
        assertThat(connectionRef.get()).isNotNull();
        assertThat(connectionRef.get().isClosed())
                .as("StorageEngine SQLite connection must be closed when migrations fail")
                .isTrue();
    }

    @Test
    @DisplayName("Round 2 Finding 2: When plugin is already disabled, coordinator runs no callbacks and closes engine")
    void disabledPluginDoesNotRunLifecycleCallbacksAndClosesEngine() {
        AtomicBoolean mainThreadInvoked = new AtomicBoolean(false);
        AtomicBoolean disabledActionInvoked = new AtomicBoolean(false);
        AtomicBoolean successInvoked = new AtomicBoolean(false);
        AtomicReference<StorageEngine> engineRef = new AtomicReference<>();

        StorageLifecycleCoordinator coordinator = new StorageLifecycleCoordinator(
                logger,
                runnable -> {
                    mainThreadInvoked.set(true);
                    runnable.run();
                },
                () -> disabledActionInvoked.set(true),
                () -> false // Plugin is already disabled!
        );

        CompletableFuture<StorageEngine> future = coordinator.start(
                "jdbc:sqlite::memory:",
                null,
                engine -> {
                    successInvoked.set(true);
                    engineRef.set(engine);
                }
        );

        future.handle((res, err) -> null).join();

        assertThat(mainThreadInvoked.get())
                .as("Main thread runner must not be invoked if plugin is already disabled")
                .isFalse();
        assertThat(disabledActionInvoked.get())
                .as("Disable action must not be invoked if plugin is already disabled")
                .isFalse();
        assertThat(successInvoked.get())
                .as("Success callback must not be invoked if plugin is already disabled")
                .isFalse();
    }
}
