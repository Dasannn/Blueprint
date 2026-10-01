package com.dasannn.socialblueprint.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** No historical timestamps are converted into peaceful play credit. */
public final class Migration_5_Serenity implements Migration {
    public int version() { return 5; }
    public String description() { return "Persist credited active Psychosis serenity duration"; }
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS psychosis_streak (player_uuid TEXT PRIMARY KEY)");
            boolean hasCredit = false;
            try (ResultSet columns = stmt.executeQuery("PRAGMA table_info(psychosis_streak)")) {
                while (columns.next()) if ("active_millis".equals(columns.getString("name"))) hasCredit = true;
            }
            if (!hasCredit) stmt.execute("ALTER TABLE psychosis_streak ADD COLUMN active_millis REAL NOT NULL DEFAULT 0 CHECK(active_millis >= 0)");
        }
    }
}
