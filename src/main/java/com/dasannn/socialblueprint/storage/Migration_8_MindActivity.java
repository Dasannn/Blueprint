package com.dasannn.socialblueprint.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Active play since the current clean-day anchor; no historical credit is invented. */
public final class Migration_8_MindActivity implements Migration {
    public int version() { return 8; }
    public String description() { return "Persistent clean-day active play"; }
    public void apply(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE mind_activity (
                        player_uuid TEXT PRIMARY KEY, anchor TEXT NOT NULL,
                        active_millis REAL NOT NULL DEFAULT 0 CHECK(active_millis >= 0)
                    )
                    """);
            stmt.execute("""
                    CREATE TRIGGER mind_activity_restart AFTER UPDATE OF mind_clean_day_at ON player_profile
                    WHEN NEW.mind_clean_day_at IS NOT OLD.mind_clean_day_at
                    BEGIN DELETE FROM mind_activity WHERE player_uuid = NEW.uuid; END
                    """);
        }
    }
}
