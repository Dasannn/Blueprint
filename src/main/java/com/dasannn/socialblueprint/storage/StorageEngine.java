package com.dasannn.socialblueprint.storage;

import java.io.Closeable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
 * - Raw connection access is package-private to storage and guarded against off-thread access.
 */
public final class StorageEngine implements Closeable {

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

        // Initialize connection on the executor thread with resource cleanup on failure
        Connection rawConnection = null;
        try {
            rawConnection = executor.submit(() -> {
                Connection conn = DriverManager.getConnection(jdbcUrl);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = ON;");
                    if (!jdbcUrl.contains(":memory:")) {
                        stmt.execute("PRAGMA journal_mode = WAL;");
                    }
                } catch (Throwable t) {
                    try {
                        conn.close();
                    } catch (Throwable ignored) {
                    }
                    throw t;
                }
                return conn;
            }).get();
            this.connection = rawConnection;
        } catch (Throwable t) {
            executor.shutdownNow();
            if (rawConnection != null) {
                try {
                    rawConnection.close();
                } catch (Throwable ignored) {
                }
            }
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new StorageException("Interrupted while opening SQLite connection", t);
            } else if (t instanceof ExecutionException ee) {
                throw new StorageException("Failed to open SQLite connection to " + jdbcUrl, ee.getCause());
            } else if (t instanceof RuntimeException re) {
                throw re;
            } else {
                throw new StorageException("Failed to open SQLite connection to " + jdbcUrl, t);
            }
        }

        this.dbThread = threadHolder[0];
        this.guardedConnection = createGuardedConnection(this.connection, this.dbThread);
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

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("StorageEngine is closed");
        }
    }

    @Override
    public void close() {
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
                // Called from another thread: submit to executor to drain queued work first
                try {
                    executor.submit(() -> {
                        try {
                            if (connection != null && !connection.isClosed()) {
                                connection.close();
                            }
                        } catch (SQLException e) {
                            throw new StorageException("Failed to close SQLite connection", e);
                        }
                        return null;
                    }).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new StorageException("Interrupted while closing StorageEngine", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new StorageException("Failed to close SQLite connection", cause);
                } finally {
                    executor.shutdown();
                }
            }
        }
    }
}
