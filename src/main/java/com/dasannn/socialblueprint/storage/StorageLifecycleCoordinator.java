package com.dasannn.socialblueprint.storage;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Coordinates asynchronous database initialization and migrations per ARCHITECTURE.md §5 and P3 review.
 * - Opens SQLite connection and executes schema migrations on the storage executor thread.
 * - Never blocks the server main thread during startup or migrations.
 * - On success, schedules component registration on the main thread.
 * - On failure, schedules plugin disablement on the main thread with a clear reason.
 */
public final class StorageLifecycleCoordinator {

    private final Logger logger;
    private final Consumer<Runnable> mainThreadRunner;
    private final Runnable disableAction;
    private final BooleanSupplier isEnabledSupplier;

    public StorageLifecycleCoordinator(
            Logger logger,
            Consumer<Runnable> mainThreadRunner,
            Runnable disableAction
    ) {
        this(logger, mainThreadRunner, disableAction, () -> true);
    }

    public StorageLifecycleCoordinator(
            Logger logger,
            Consumer<Runnable> mainThreadRunner,
            Runnable disableAction,
            BooleanSupplier isEnabledSupplier
    ) {
        this.logger = logger != null ? logger : Logger.getLogger(StorageLifecycleCoordinator.class.getName());
        this.mainThreadRunner = Objects.requireNonNull(mainThreadRunner, "mainThreadRunner must not be null");
        this.disableAction = Objects.requireNonNull(disableAction, "disableAction must not be null");
        this.isEnabledSupplier = Objects.requireNonNull(isEnabledSupplier, "isEnabledSupplier must not be null");
    }

    /**
     * Initiates asynchronous database connection and migrations on the storage executor.
     * Does not block the calling thread. Registers components on the main thread upon success.
     */
    public CompletableFuture<StorageEngine> start(
            String jdbcUrl,
            ExecutorService customExecutor,
            MigrationRunner migrationRunner,
            Consumer<StorageEngine> onSuccess
    ) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        Objects.requireNonNull(onSuccess, "onSuccess must not be null");
        MigrationRunner runner = migrationRunner != null ? migrationRunner : MigrationRunner.withDefaultMigrations();

        return StorageEngine.openAsync(jdbcUrl, customExecutor)
                .thenCompose(engine -> engine.runMigrationsAsync(runner)
                        .thenApply(count -> engine)
                        .exceptionallyCompose(migrationError -> {
                            engine.close();
                            return CompletableFuture.failedFuture(migrationError);
                        }))
                .whenComplete((engine, error) -> {
                    if (!isEnabledSupplier.getAsBoolean()) {
                        if (engine != null) {
                            engine.close();
                        }
                        return;
                    }
                    mainThreadRunner.accept(() -> {
                        if (!isEnabledSupplier.getAsBoolean()) {
                            if (engine != null) {
                                engine.close();
                            }
                            return;
                        }
                        if (error != null) {
                            logger.severe("Failed to initialize database: " + error.getMessage());
                            disableAction.run();
                            return;
                        }
                        try {
                            onSuccess.accept(engine);
                        } catch (Throwable t) {
                            logger.severe("Failed to register plugin components: " + t.getMessage());
                            if (engine != null) {
                                engine.close();
                            }
                            disableAction.run();
                        }
                    });
                });
    }

    public CompletableFuture<StorageEngine> start(
            String jdbcUrl,
            ExecutorService customExecutor,
            Consumer<StorageEngine> onSuccess
    ) {
        return start(jdbcUrl, customExecutor, null, onSuccess);
    }

    public CompletableFuture<StorageEngine> start(String jdbcUrl, Consumer<StorageEngine> onSuccess) {
        return start(jdbcUrl, null, null, onSuccess);
    }
}
