package com.dasannn.socialblueprint.storage;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Storage engine managing the single-threaded executor and single SQLite connection per T-018 and ARCHITECTURE.md §5.
 * - Exactly one connection to the SQLite database.
 * - All database access is submitted to a single-threaded executor.
 * - Writes and reads serialise; SQLite never sees concurrent writers.
 */
public final class StorageEngine implements Closeable {

    @FunctionalInterface
    public interface ConnectionFunction<T> {
        T apply(Connection conn) throws SQLException;
    }

    @FunctionalInterface
    public interface ConnectionConsumer {
        void accept(Connection conn) throws SQLException;
    }

    private final ExecutorService executor;
    private final Connection connection;
    private final Thread dbThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private StorageEngine(String jdbcUrl) {
        Objects.requireNonNull(jdbcUrl, "JDBC URL must not be null");

        // Hold a reference to the single DB thread to detect re-entrant calls
        Thread[] threadHolder = new Thread[1];
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "socialblueprint-db");
                t.setDaemon(true);
                threadHolder[0] = t;
                return t;
            }
        });

        // Initialize connection on the executor thread
        try {
            this.connection = executor.submit(() -> {
                Connection conn = DriverManager.getConnection(jdbcUrl);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = ON;");
                    if (!jdbcUrl.contains(":memory:")) {
                        stmt.execute("PRAGMA journal_mode = WAL;");
                    }
                }
                return conn;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Interrupted while opening SQLite connection", e);
        } catch (ExecutionException e) {
            throw new StorageException("Failed to open SQLite connection to " + jdbcUrl, e.getCause());
        }

        this.dbThread = threadHolder[0];
    }

    public static StorageEngine inMemory() {
        return new StorageEngine("jdbc:sqlite::memory:");
    }

    public static StorageEngine open(String jdbcUrl) {
        return new StorageEngine(jdbcUrl);
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
    public <T> T execute(ConnectionFunction<T> function) {
        checkNotClosed();
        if (Thread.currentThread() == dbThread) {
            try {
                return function.apply(connection);
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
    public void run(ConnectionConsumer consumer) {
        execute(conn -> {
            consumer.accept(conn);
            return null;
        });
    }

    /**
     * Executes a function against the SQLite connection asynchronously on the executor thread.
     */
    public <T> CompletableFuture<T> executeAsync(ConnectionFunction<T> function) {
        checkNotClosed();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return function.apply(connection);
            } catch (SQLException e) {
                throw new StorageException("Database query failed", e);
            }
        }, executor);
    }

    /**
     * Executes a consumer against the SQLite connection asynchronously on the executor thread.
     */
    public CompletableFuture<Void> runAsync(ConnectionConsumer consumer) {
        return executeAsync(conn -> {
            consumer.accept(conn);
            return null;
        });
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("StorageEngine is closed");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                executor.submit(() -> {
                    try {
                        if (connection != null && !connection.isClosed()) {
                            connection.close();
                        }
                    } catch (SQLException ignored) {
                    }
                }).get();
            } catch (Exception ignored) {
                // Best effort closing
            } finally {
                executor.shutdown();
            }
        }
    }
}
