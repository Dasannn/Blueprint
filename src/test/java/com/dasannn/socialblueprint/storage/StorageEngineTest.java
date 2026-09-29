package com.dasannn.socialblueprint.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(newVersion).isEqualTo(1);

        // 3. Verify schema_version table contains exactly 1 row with version 1
        storage.execute(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT version, applied_at FROM schema_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("version")).isEqualTo(1);
                assertThat(rs.getString("applied_at")).isNotBlank();
                assertThat(rs.next()).isFalse(); // Exactly single row per ARCHITECTURE.md §4
            }
            return null;
        });

        // 4. Verify all 8 tables per ARCHITECTURE.md §4 exist in sqlite_master
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
                "schema_version"
        );

        // 5. Rerunning migration is idempotent
        int secondRunVersion = storage.runMigrations(runner);
        assertThat(secondRunVersion).isEqualTo(1);
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
}
