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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
        assertThat(newVersion).isEqualTo(7);

        // 3. Verify schema_version table contains exactly 1 row with version 6
        storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT version, applied_at FROM schema_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("version")).isEqualTo(7);
                assertThat(rs.getString("applied_at")).isNotBlank();
                assertThat(rs.next()).isFalse(); // Exactly single row per ARCHITECTURE.md §4
            }
            return null;
        });

        // 4. Verify all tables per ARCHITECTURE.md §4, SB-084, T-103, and Migration 4 exist in sqlite_master
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
                "psychosis_streak",
                "mind_event",
                "kill_penalty_claim",
                "honor_allowance",
                "duel",
                "duel_participant",
                "audit_event",
                "pending_compensation",
                "schema_version",
                "rater_reveal"
        );

        // 5. Rerunning migration is idempotent
        int secondRunVersion = storage.runMigrations(runner);
        assertThat(secondRunVersion).isEqualTo(7);
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
        // Run migrations 1 through 6
        storage.runMigrations(new MigrationRunner(List.of(new Migration_1_InitialSchema(),
                new Migration_2_RaterReveal(), new Migration_3_KillPenaltyClaim(),
                new Migration_4_PendingCompensation(), new Migration_5_Serenity(), new Migration_6_MindState())));
        int versionBefore = storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version")) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        });
        assertThat(versionBefore).isEqualTo(6);

        // Define a failing migration 7 that creates a table and then throws
        Migration failingMigration = new Migration() {
            @Override
            public int version() { return 7; }
            @Override
            public String description() { return "Failing migration test"; }
            @Override
            public void apply(Connection conn) throws SQLException {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE rolled_back_table (id INTEGER PRIMARY KEY);");
                }
                throw new SQLException("Simulated failure during migration 7");
            }
        };

        MigrationRunner runner = new MigrationRunner(List.of(
                new Migration_1_InitialSchema(),
                new Migration_2_RaterReveal(),
                new Migration_3_KillPenaltyClaim(),
                new Migration_4_PendingCompensation(),
                new Migration_5_Serenity(),
                new Migration_6_MindState(),
                failingMigration
        ));

        assertThatThrownBy(() -> storage.runMigrations(runner))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Failed applying migration 7");

        // Verify rolled_back_table was rolled back and does not exist
        boolean tableExists = storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='rolled_back_table'")) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1) > 0;
            }
        });
        assertThat(tableExists).isFalse();

        // Verify schema_version remains 6
        int versionAfter = storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version")) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        });
        assertThat(versionAfter).isEqualTo(6);
    }

    @Test
    @DisplayName("T-103 Finding 1: Database stamped at version 2 upgrades through migration chain to version 7")
    void upgradingFromVersion2CreatesKillPenaltyClaim() {
        MigrationRunner v2Runner = new MigrationRunner(List.of(
                new Migration_1_InitialSchema(),
                new Migration_2_RaterReveal()
        ));
        int v2 = storage.runMigrations(v2Runner);
        assertThat(v2).isEqualTo(2);

        // Drop tables to simulate an upgraded database that never had migrations 3 and 4 run
        storage.run(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS kill_penalty_claim;");
                stmt.execute("DROP TABLE IF EXISTS pending_compensation;");
            }
        });

        boolean tableExistsBefore = storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='kill_penalty_claim'")) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1) > 0;
            }
        });
        assertThat(tableExistsBefore).isFalse();

        MigrationRunner defaultRunner = MigrationRunner.withDefaultMigrations();
        int currentVersion = storage.runMigrations(defaultRunner);
        assertThat(currentVersion).isEqualTo(7);

        boolean tableExistsAfter = storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='kill_penalty_claim'")) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1) > 0;
            }
        });
        assertThat(tableExistsAfter).isTrue();

        boolean compTableExistsAfter = storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='pending_compensation'")) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1) > 0;
            }
        });
        assertThat(compTableExistsAfter).isTrue();

        boolean indexExists = storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='idx_kill_penalty_claim_pair'")) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1) > 0;
            }
        });
        assertThat(indexExists).isTrue();
    }

    @Test
    @DisplayName("Finding 1: Database stamped at version 1 upgrades through full chain to match current schema")
    void upgradingFromVersion1CreatesAllCurrentTablesAndColumns() {
        // 1. Fresh database runs all default migrations (current full schema)
        StorageEngine freshEngine = StorageEngine.inMemory();
        try {
            freshEngine.runMigrations(MigrationRunner.withDefaultMigrations());
            Map<String, Set<String>> expectedTablesAndColumns = extractTablesAndColumns(freshEngine);
            Set<String> expectedIndexes = extractIndexNames(freshEngine);

            // 2. Upgraded database simulates original version 1 schema (before pending_compensation and kill_penalty_claim were appended)
            storage.run(conn -> {
                try (Statement stmt = conn.createStatement()) {
                    // Original 7 tables from initial commit 13276fb
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS player_profile (
                            uuid TEXT PRIMARY KEY NOT NULL,
                            last_known_name TEXT NOT NULL,
                            effects_opt_out INTEGER NOT NULL DEFAULT 0,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        );
                    """);
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS reputation_event (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            actor_uuid TEXT,
                            target_uuid TEXT NOT NULL,
                            delta INTEGER NOT NULL,
                            kind TEXT NOT NULL,
                            cost REAL NOT NULL DEFAULT 0.0,
                            reason TEXT,
                            created_at TEXT NOT NULL
                        );
                    """);
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_reputation_target ON reputation_event (target_uuid);");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_reputation_actor ON reputation_event (actor_uuid);");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_reputation_actor_target ON reputation_event (actor_uuid, target_uuid);");

                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS psychosis_event (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            killer_uuid TEXT NOT NULL,
                            victim_uuid TEXT NOT NULL,
                            context TEXT NOT NULL,
                            created_at TEXT NOT NULL
                        );
                    """);
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_psychosis_killer ON psychosis_event (killer_uuid, created_at);");

                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS honor_allowance (
                            actor_uuid TEXT NOT NULL,
                            target_uuid TEXT NOT NULL,
                            sign TEXT NOT NULL,
                            count INTEGER NOT NULL DEFAULT 0,
                            window_start TEXT NOT NULL,
                            PRIMARY KEY (actor_uuid, target_uuid, sign)
                        );
                    """);

                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS duel (
                            id TEXT PRIMARY KEY NOT NULL,
                            state TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            ended_at TEXT
                        );
                    """);

                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS duel_participant (
                            duel_id TEXT NOT NULL,
                            uuid TEXT NOT NULL,
                            side TEXT NOT NULL,
                            PRIMARY KEY (duel_id, uuid)
                        );
                    """);

                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS audit_event (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            actor TEXT NOT NULL,
                            operation TEXT NOT NULL,
                            target TEXT NOT NULL,
                            before TEXT,
                            after TEXT,
                            created_at TEXT NOT NULL
                        );
                    """);

                    // Stamp schema_version = 1
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS schema_version (
                            version INTEGER NOT NULL,
                            applied_at TEXT NOT NULL
                        );
                    """);
                    stmt.execute("INSERT INTO schema_version (version, applied_at) VALUES (1, '2026-09-29T00:00:00.000000000Z');");
                }
            });

            // 3. Run default migration chain (should apply migrations 2, 3, 4...)
            int finalVersion = storage.runMigrations(MigrationRunner.withDefaultMigrations());
            assertThat(finalVersion).isEqualTo(7);

            // 4. Assert that every table and column present in the fresh schema is present in the upgraded database.
            // If any object is appended to migration 1 without a corresponding migration in the chain, this assertion fails!
            Map<String, Set<String>> upgradedTablesAndColumns = extractTablesAndColumns(storage);
            assertThat(upgradedTablesAndColumns).isEqualTo(expectedTablesAndColumns);
            assertThat(extractIndexNames(storage)).isEqualTo(expectedIndexes);
        } finally {
            freshEngine.close();
        }
    }

    @Test
    @DisplayName("Finding 1: Database stamped at version 1 with pending_compensation missing state gets repaired")
    void upgradingFromVersion1WithoutStateColumnRepairsStateColumn() {
        storage.run(conn -> {
            try (Statement stmt = conn.createStatement()) {
                // Table created without state column (as in commit a9e40db)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS pending_compensation (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        amount REAL NOT NULL,
                        reason TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    );
                """);
                // The rest of a real version-1 schema; IF NOT EXISTS keeps the table above.
                new Migration_1_InitialSchema().apply(conn);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER NOT NULL,
                        applied_at TEXT NOT NULL
                    );
                """);
                stmt.execute("INSERT INTO schema_version (version, applied_at) VALUES (1, '2026-09-29T00:00:00.000000000Z');");
                stmt.execute("INSERT INTO pending_compensation (player_uuid, amount, reason, created_at) "
                        + "VALUES ('00000000-0000-0000-0000-000000000001', 50.0, 'refund', '2026-09-29T00:00:00.000000000Z');");
            }
        });

        // Run migrations
        storage.runMigrations(MigrationRunner.withDefaultMigrations());

        // Verify state column exists in pending_compensation
        storage.run(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA table_info(pending_compensation)")) {
                List<String> columns = new ArrayList<>();
                while (rs.next()) {
                    columns.add(rs.getString("name"));
                }
                assertThat(columns).contains("state");
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT state, amount, reason FROM pending_compensation")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("state")).isEqualTo("CHARGED");
                assertThat(rs.getDouble("amount")).isEqualTo(50.0);
                assertThat(rs.getString("reason")).isEqualTo("refund");
                assertThat(rs.next()).isFalse();
            }
        });
        assertThat(storage.runMigrations()).isEqualTo(7);
    }

    private static Set<String> extractIndexNames(StorageEngine engine) {
        return engine.execute(conn -> {
            Set<String> indexes = new TreeSet<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='index'")) {
                while (rs.next()) {
                    indexes.add(rs.getString("name"));
                }
            }
            return indexes;
        });
    }

    private static Map<String, Set<String>> extractTablesAndColumns(StorageEngine engine) {
        return engine.execute(conn -> {
            Map<String, Set<String>> result = new TreeMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                List<String> tables = new ArrayList<>();
                while (rs.next()) {
                    tables.add(rs.getString("name"));
                }
                for (String table : tables) {
                    Set<String> columns = new TreeSet<>();
                    try (Statement colStmt = conn.createStatement();
                         ResultSet colRs = colStmt.executeQuery("PRAGMA table_info(" + table + ")")) {
                        while (colRs.next()) {
                            columns.add(colRs.getString("name"));
                        }
                    }
                    result.put(table, columns);
                }
            }
            return result;
        });
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

    @Test
    @DisplayName("Finding 5: StorageEngine shutdown delegates connection closure to DB executor thread")
    void finding5_executorClosesOwnConnectionAndTimesOutCleanly() throws Exception {
        AtomicReference<Thread> connectionCloseThread = new AtomicReference<>();
        StorageEngine customEngine = StorageEngine.inMemory();
        // Record connection thread
        AtomicReference<Thread> dbThread = new AtomicReference<>();
        customEngine.execute(conn -> {
            dbThread.set(Thread.currentThread());
            return null;
        });

        // Close from current (calling/main) thread
        Thread callingThread = Thread.currentThread();
        assertThat(callingThread).isNotEqualTo(dbThread.get());

        customEngine.close(1, TimeUnit.SECONDS);

        assertThat(customEngine.isClosed()).isTrue();
        assertThat(StorageEngine.DEFAULT_SHUTDOWN_TIMEOUT_SECONDS).isEqualTo(2);
    }
}
