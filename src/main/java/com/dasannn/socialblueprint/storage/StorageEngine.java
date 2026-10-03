package com.dasannn.socialblueprint.storage;

import java.io.Closeable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Storage engine managing the single-threaded executor and single SQLite connection per T-018 and ARCHITECTURE.md §5.
 * - Exactly one connection to the SQLite database.
 * - All database access is submitted to a single-threaded executor.
 * - Writes and reads serialise; SQLite never sees concurrent writers.
 * - Raw connection access is package-private to storage and guarded against off-thread access.
 */
public final class StorageEngine implements Closeable {

    public static final long DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final Logger LOGGER = Logger.getLogger(StorageEngine.class.getName());

    @FunctionalInterface
    interface ConnectionFunction<T> {
        T apply(Connection conn) throws SQLException;
    }

    @FunctionalInterface
    interface ConnectionConsumer {
        void accept(Connection conn) throws SQLException;
    }

    private final ExecutorService executor;
    private final Connection connection;
    private final Connection guardedConnection;
    private final Thread dbThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private StorageEngine(ExecutorService executor, Connection connection, Thread dbThread) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.dbThread = Objects.requireNonNull(dbThread, "dbThread must not be null");
        this.guardedConnection = createGuardedConnection(this.connection, this.dbThread);
    }

    public static CompletableFuture<StorageEngine> openAsync(String jdbcUrl) {
        return openAsync(jdbcUrl, null);
    }

    public static CompletableFuture<StorageEngine> openAsync(String jdbcUrl, ExecutorService customExecutor) {
        Objects.requireNonNull(jdbcUrl, "JDBC URL must not be null");

        Thread[] threadHolder = new Thread[1];
        ExecutorService exec = customExecutor != null ? customExecutor : Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "socialblueprint-db");
                t.setDaemon(true);
                threadHolder[0] = t;
                return t;
            }
        });

        return CompletableFuture.supplyAsync(() -> {
            Connection rawConnection = null;
            try {
                if (threadHolder[0] == null) {
                    threadHolder[0] = Thread.currentThread();
                }
                rawConnection = DriverManager.getConnection(jdbcUrl);
                try (Statement stmt = rawConnection.createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = ON;");
                    if (!jdbcUrl.contains(":memory:")) {
                        stmt.execute("PRAGMA journal_mode = WAL;");
                    }
                }
                return new StorageEngine(exec, rawConnection, threadHolder[0]);
            } catch (Throwable t) {
                if (customExecutor == null) {
                    exec.shutdownNow();
                }
                if (rawConnection != null) {
                    try {
                        rawConnection.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (t instanceof RuntimeException re) {
                    throw re;
                }
                throw new StorageException("Failed to open SQLite connection to " + jdbcUrl, t);
            }
        }, exec);
    }

    private static Connection createGuardedConnection(Connection delegate, Thread allowedThread) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (Thread.currentThread() != allowedThread) {
                        throw new IllegalStateException("Connection cannot be accessed off the database executor thread: "
                                + Thread.currentThread().getName());
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
        );
    }

    private static <T> T sanitizeResult(T result) {
        if (result instanceof Connection) {
            throw new IllegalStateException("Connection must not escape the database executor thread");
        }
        return result;
    }

    public static StorageEngine inMemory() {
        return open("jdbc:sqlite::memory:");
    }

    public static StorageEngine open(String jdbcUrl) {
        try {
            return openAsync(jdbcUrl).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Interrupted while opening SQLite connection", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new StorageException("Failed to open SQLite connection to " + jdbcUrl, cause);
        }
    }

    public CompletableFuture<Integer> runMigrationsAsync(MigrationRunner runner) {
        return executeAsync(runner::runMigrations);
    }

    public CompletableFuture<Integer> runMigrationsAsync() {
        return runMigrationsAsync(MigrationRunner.withDefaultMigrations());
    }

    public int runMigrations(MigrationRunner runner) {
        return execute(runner::runMigrations);
    }

    public int runMigrations() {
        return runMigrations(MigrationRunner.withDefaultMigrations());
    }

    /**
     * Executes a function against the SQLite connection synchronously on the executor thread.
     */
    <T> T execute(ConnectionFunction<T> function) {
        checkNotClosed();
        if (Thread.currentThread() == dbThread) {
            try {
                return sanitizeResult(function.apply(guardedConnection));
            } catch (SQLException e) {
                throw new StorageException("Database query failed", e);
            }
        }
        try {
            return executeAsync(function).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Database operation interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new StorageException("Database operation failed", cause);
        }
    }

    /**
     * Executes a consumer against the SQLite connection synchronously on the executor thread.
     */
    void run(ConnectionConsumer consumer) {
        execute(conn -> {
            consumer.accept(conn);
            return null;
        });
    }

    /**
     * Executes a function against the SQLite connection asynchronously on the executor thread.
     */
    <T> CompletableFuture<T> executeAsync(ConnectionFunction<T> function) {
        checkNotClosed();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sanitizeResult(function.apply(guardedConnection));
            } catch (SQLException e) {
                throw new StorageException("Database query failed", e);
            }
        }, executor);
    }

    /**
     * Executes a consumer against the SQLite connection asynchronously on the executor thread.
     */
    CompletableFuture<Void> runAsync(ConnectionConsumer consumer) {
        return executeAsync(conn -> {
            consumer.accept(conn);
            return null;
        });
    }

    /**
     * Submits a task to be executed asynchronously on the database executor thread.
     */
    public CompletableFuture<Void> submitAsync(Runnable task) {
        Objects.requireNonNull(task, "Task must not be null");
        checkNotClosed();
        return CompletableFuture.runAsync(task, executor);
    }

    /** Thread identity only; does not touch the connection. */
    public boolean isStorageThread() { return Thread.currentThread() == dbThread; }

    /**
     * Submits a supplier to be executed asynchronously on the database executor thread.
     */
    public <T> CompletableFuture<T> supplyAsync(java.util.function.Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "Supplier must not be null");
        checkNotClosed();
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    public boolean isClosed() {
        return closed.get();
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("StorageEngine is closed");
        }
    }

    @Override
    public void close() {
        close(DEFAULT_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public void close(long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit, "TimeUnit must not be null");
        if (closed.compareAndSet(false, true)) {
            if (Thread.currentThread() == dbThread) {
                // Called from DB thread: close directly to prevent self-deadlock
                try {
                    if (connection != null && !connection.isClosed()) {
                        connection.close();
                    }
                } catch (SQLException e) {
                    throw new StorageException("Failed to close SQLite connection", e);
                } finally {
                    executor.shutdown();
                }
            } else {
                executor.execute(() -> {
                    try {
                        if (connection != null && !connection.isClosed()) {
                            connection.close();
                        }
                    } catch (SQLException e) {
                        LOGGER.log(Level.WARNING, "Failed to close SQLite connection during shutdown", e);
                    }
                });
                executor.shutdown();
                boolean terminated = false;
                try {
                    terminated = executor.awaitTermination(timeout, unit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (!terminated) {
                    List<Runnable> dropped = executor.shutdownNow();
                    LOGGER.warning("StorageEngine shutdown timed out after " + timeout + " " + unit
                            + ". Abandoned " + dropped.size() + " tasks.");
                }
            }
        }
    }
}
