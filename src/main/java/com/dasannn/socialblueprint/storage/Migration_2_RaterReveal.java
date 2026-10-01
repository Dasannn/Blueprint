package com.dasannn.socialblueprint.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Migration 2: Creates the rater_reveal table and its index per SB-084 and T-124.
 */
public final class Migration_2_RaterReveal implements Migration {

    @Override
    public int version() {
        return 2;
    }

    @Override
    public String description() {
        return "Add rater_reveal table and index for per-viewer rater anonymity reveals";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rater_reveal (
                    viewer_uuid TEXT NOT NULL,
                    event_id INTEGER NOT NULL,
                    rater_uuid TEXT,
                    cost REAL NOT NULL DEFAULT 0.0,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (viewer_uuid, event_id)
                );
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_reveal_viewer ON rater_reveal (viewer_uuid);");
        }
    }
}
