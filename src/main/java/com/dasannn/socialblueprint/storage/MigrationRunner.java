package com.dasannn.socialblueprint.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Executes numbered migrations driven by the single-row schema_version table per T-017 and ARCHITECTURE.md §4.
 */
public final class MigrationRunner {

    private final List<Migration> migrations;

    public MigrationRunner(List<Migration> migrations) {
        Objects.requireNonNull(migrations, "Migrations list must not be null");
        this.migrations = migrations.stream()
                .sorted(Comparator.comparingInt(Migration::version))
                .toList();

        for (int i = 0; i < this.migrations.size(); i++) {
            int expected = i + 1;
            int actual = this.migrations.get(i).version();
            if (actual != expected) {
                if (i > 0 && actual == this.migrations.get(i - 1).version()) {
                    throw new IllegalArgumentException("Duplicate migration version: " + actual);
                }
                throw new IllegalArgumentException("Non-consecutive migration version: expected " + expected + ", got " + actual);
            }
        }
    }

    public static MigrationRunner withDefaultMigrations() {
        return withDefaultMigrations(com.dasannn.socialblueprint.domain.LegacyMindConversion.DEFAULT, java.time.Clock.systemUTC());
    }

    public static MigrationRunner withDefaultMigrations(com.dasannn.socialblueprint.domain.LegacyMindConversion legacy, java.time.Clock clock) {
        return new MigrationRunner(List.of(
                new Migration_1_InitialSchema(),
                new Migration_2_RaterReveal(),
                new Migration_3_KillPenaltyClaim(),
                new Migration_4_PendingCompensation(),
                new Migration_5_Serenity(),
                new Migration_6_MindState(legacy, clock)
        ));
    }

    /**
     * Runs all pending migrations against the provided SQLite connection.
     * Each migration runs inside a transaction, recording the version in schema_version.
     *
     * @return the resulting schema version
     */
    public int runMigrations(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "Connection must not be null");

        ensureSchemaVersionTable(conn);
        int currentVersion = getCurrentVersion(conn);

        for (Migration migration : migrations) {
            if (migration.version() > currentVersion) {
                boolean initialAutoCommit = conn.getAutoCommit();
                try {
                    conn.setAutoCommit(false);
                    migration.apply(conn);
                    updateSchemaVersion(conn, migration.version());
                    conn.commit();
                    currentVersion = migration.version();
                } catch (Exception e) {
                    conn.rollback();
                    throw new StorageException("Failed applying migration " + migration.version() + ": "
                            + migration.description(), e);
                } finally {
                    conn.setAutoCommit(initialAutoCommit);
                }
            }
        }

        return currentVersion;
    }

    public int getCurrentVersion(Connection conn) throws SQLException {
        ensureSchemaVersionTable(conn);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
            if (rs.next()) {
                return rs.getInt("version");
            }
            return 0;
        }
    }

    private void ensureSchemaVersionTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER NOT NULL,
                    applied_at TEXT NOT NULL
                );
            """);
        }
    }

    private void updateSchemaVersion(Connection conn, int newVersion) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM schema_version;");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO schema_version (version, applied_at) VALUES (?, ?);")) {
            ps.setInt(1, newVersion);
            ps.setString(2, StorageTimestamps.format(Instant.now()));
            ps.executeUpdate();
        }
    }
}
