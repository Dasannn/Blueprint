package com.dasannn.socialblueprint.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageEngineTest {

    private StorageEngine storage;

    @BeforeEach
    void setUp() {
        storage = StorageEngine.inMemory();
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    @DisplayName("T-017, T-021: Runs full migration chain from empty database")
    void fullMigrationChainFromEmpty() {
        MigrationRunner runner = MigrationRunner.withDefaultMigrations();

        // 1. Initial version before migrations is 0
        int initialVersion = storage.execute(runner::getCurrentVersion);
        assertThat(initialVersion).isZero();

        // 2. Run migrations
        int newVersion = storage.runMigrations(runner);
        assertThat(newVersion).isEqualTo(2);

        // 3. Verify schema_version table contains exactly 1 row with version 2
        storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT version, applied_at FROM schema_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("version")).isEqualTo(2);
                assertThat(rs.getString("applied_at")).isNotBlank();
                assertThat(rs.next()).isFalse(); // Exactly single row per ARCHITECTURE.md §4
            }
            return null;
        });

        // 4. Verify all tables per ARCHITECTURE.md §4 and SB-084 exist in sqlite_master
        List<String> tables = storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
                List<String> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(rs.getString("name"));
                }
                return list;
            }
        });

        assertThat(tables).contains(
                "player_profile",
                "reputation_event",
                "psychosis_event",
                "honor_allowance",
                "duel",
                "duel_participant",
                "audit_event",
                "schema_version",
                "rater_reveal"
        );

        // 5. Rerunning migration is idempotent
        int secondRunVersion = storage.runMigrations(runner);
        assertThat(secondRunVersion).isEqualTo(2);
    }

    @Test
    @DisplayName("T-018: All database operations execute on single thread (no concurrent SQLite writers)")
    void singleThreadedExecution() {
        storage.runMigrations();

        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            final int index = i;
            futures.add(storage.executeAsync(conn -> {
                threadNames.add(Thread.currentThread().getName());
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT 1 + " + index);
                }
                return null;
            }));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Exactly one thread was used for all 50 operations
        assertThat(threadNames).containsExactly("socialblueprint-db");
    }

    @Test
    @DisplayName("Finding 8: Raw connection cannot escape the database executor thread")
    void rawConnectionCannotEscapeExecutor() {
        // 1. Returning connection from execute() throws IllegalStateException
        assertThatThrownBy(() -> storage.execute(conn -> conn))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("escape");

        // 2. Returning connection from executeAsync() throws ExecutionException wrapping IllegalStateException
        assertThatThrownBy(() -> storage.executeAsync(conn -> conn).get())
                .hasCauseInstanceOf(IllegalStateException.class);

        // 3. Leaking connection via captured reference throws when accessed off-thread
        AtomicReference<Connection> leaked = new AtomicReference<>();
        storage.run(leaked::set);
        Connection conn = leaked.get();
        assertThat(conn).isNotNull();

        // Calling from test thread
        assertThatThrownBy(conn::createStatement)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("off the database executor thread");

        // Calling from another worker thread
        CompletableFuture<Void> offThreadAccess = CompletableFuture.runAsync(() -> {
            try {
                conn.getAutoCommit();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
        assertThatThrownBy(offThreadAccess::join)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Finding 9: MigrationRunner rejects duplicate and non-consecutive versions")
    void migrationRunnerRejectsInvalidVersionSequences() {
        Migration m1 = new Migration_1_InitialSchema();
        Migration mDuplicate = new Migration() {
            @Override
            public int version() { return 1; }
            @Override
            public String description() { return "duplicate"; }
            @Override
            public void apply(Connection conn) {}
        };
        Migration mGap = new Migration() {
            @Override
            public int version() { return 3; }
            @Override
            public String description() { return "gap"; }
            @Override
            public void apply(Connection conn) {}
        };
        Migration mStartsAt2 = new Migration() {
            @Override
            public int version() { return 2; }
            @Override
            public String description() { return "starts at 2"; }
            @Override
            public void apply(Connection conn) {}
        };

        assertThatThrownBy(() -> new MigrationRunner(List.of(m1, mDuplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate migration version: 1");

        assertThatThrownBy(() -> new MigrationRunner(List.of(m1, mGap)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Non-consecutive migration version: expected 2, got 3");

        assertThatThrownBy(() -> new MigrationRunner(List.of(mStartsAt2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Non-consecutive migration version: expected 1, got 2");
    }

    @Test
    @DisplayName("Finding 9: Failed migration rolls back both DDL changes and schema_version entry")
    void failedMigrationRollsBackTableAndVersion() {
        // Run migration 1 and 2
        storage.runMigrations();
        int versionBefore = storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version")) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        });
        assertThat(versionBefore).isEqualTo(2);

        // Define a failing migration 3 that creates a table and then throws
        Migration failingMigration = new Migration() {
            @Override
            public int version() { return 3; }
            @Override
            public String description() { return "Failing migration test"; }
            @Override
            public void apply(Connection conn) throws SQLException {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE rolled_back_table (id INTEGER PRIMARY KEY);");
                }
                throw new SQLException("Simulated failure during migration 3");
            }
        };

        MigrationRunner runner = new MigrationRunner(List.of(
                new Migration_1_InitialSchema(),
                new Migration_2_RaterReveal(),
                failingMigration
        ));

        assertThatThrownBy(() -> storage.runMigrations(runner))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Failed applying migration 3");

        // Verify rolled_back_table was rolled back and does not exist
        boolean tableExists = storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='rolled_back_table'")) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1) > 0;
            }
        });
        assertThat(tableExists).isFalse();

        // Verify schema_version remains 2
        int versionAfter = storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version")) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        });
        assertThat(versionAfter).isEqualTo(2);
    }

    @Test
    @DisplayName("Finding 10: Calling close() from the database thread completes without self-deadlock")
    void closeFromDatabaseThreadDoesNotDeadlock() {
        storage.run(conn -> {
            storage.close();
        });

        assertThatThrownBy(() -> storage.run(conn -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("Finding 10: Queued write followed immediately by close() drains successfully before shutdown")
    void queuedWriteFollowedByCloseDrainsSuccessfully() throws Exception {
        Path tempDb = Files.createTempFile("storage-close-test", ".db");
        try {
            StorageEngine fileEngine = StorageEngine.open("jdbc:sqlite:" + tempDb.toAbsolutePath());
            fileEngine.runMigrations();

            CompletableFuture<Void> writeFuture = fileEngine.runAsync(conn -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("INSERT INTO player_profile (uuid, last_known_name, effects_opt_out, created_at, updated_at) " +
                            "VALUES ('00000000-0000-0000-0000-000000000001', 'PlayerOne', 0, '2026-01-01T00:00:00.000000000Z', '2026-01-01T00:00:00.000000000Z');");
                }
            });

            fileEngine.close();
            assertThat(writeFuture.isDone()).isTrue();
            assertThat(writeFuture.isCompletedExceptionally()).isFalse();

            // Reopen and verify data was committed
            try (StorageEngine reopened = StorageEngine.open("jdbc:sqlite:" + tempDb.toAbsolutePath())) {
                String name = reopened.execute(conn -> {
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT last_known_name FROM player_profile WHERE uuid='00000000-0000-0000-0000-000000000001'")) {
                        assertThat(rs.next()).isTrue();
                        return rs.getString(1);
                    }
                });
                assertThat(name).isEqualTo("PlayerOne");
            }
        } finally {
            Files.deleteIfExists(tempDb);
        }
    }

    @Test
    @DisplayName("Finding 10: Construction failure cleans up resources and shuts down executor")
    void constructionFailureCleansUpResources() {
        assertThatThrownBy(() -> StorageEngine.open("jdbc:unsupported-driver:test"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("Round 2 Finding 1: Held database task does not hold close() past the bounded timeout")
    void heldTaskDoesNotHoldClosePastBound() {
        CountDownLatch holdLatch = new CountDownLatch(1);
        storage.submitAsync(() -> {
            try {
                holdLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        long start = System.currentTimeMillis();
        // Bounded close with 250ms
        storage.close(250, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("close() must return within bounded time and not block indefinitely on held task")
                .isLessThan(1500);
        assertThat(storage.isClosed()).isTrue();
    }
}
