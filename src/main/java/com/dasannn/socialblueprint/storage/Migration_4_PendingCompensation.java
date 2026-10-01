package com.dasannn.socialblueprint.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Migration 4: Creates the pending_compensation table and ensures state column exists for existing databases.
 */
public final class Migration_4_PendingCompensation implements Migration {

    @Override
    public int version() {
        return 4;
    }

    @Override
    public String description() {
        return "Add pending_compensation table and state column for compensation reconciliation";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS pending_compensation (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL,
                    amount REAL NOT NULL,
                    reason TEXT NOT NULL,
                    state TEXT NOT NULL DEFAULT 'CHARGED',
                    created_at TEXT NOT NULL
                );
            """);
            boolean hasState = false;
            try (ResultSet columns = stmt.executeQuery("PRAGMA table_info(pending_compensation)")) {
                while (columns.next()) {
                    if ("state".equals(columns.getString("name"))) {
                        hasState = true;
                    }
                }
            }
            if (!hasState) {
                stmt.execute("ALTER TABLE pending_compensation ADD COLUMN state TEXT NOT NULL DEFAULT 'CHARGED';");
            }
        }
    }
}
