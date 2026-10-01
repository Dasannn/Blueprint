package com.dasannn.socialblueprint.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Migration 3: Creates the kill_penalty_claim table and its index for existing databases (T-103, T-130).
 */
public final class Migration_3_KillPenaltyClaim implements Migration {

    @Override
    public int version() {
        return 3;
    }

    @Override
    public String description() {
        return "Add kill_penalty_claim table and index for non-duel kill penalties";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS kill_penalty_claim (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    killer_uuid TEXT NOT NULL,
                    victim_uuid TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_kill_penalty_claim_pair ON kill_penalty_claim (killer_uuid, victim_uuid, created_at);");
        }
    }
}
