package com.dasannn.socialblueprint.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Append-only references; original ratings and money history remain untouched. */
public final class Migration_7_HonorRevocation implements Migration {
    public int version() { return 7; }
    public String description() { return "Honor rating revocation references"; }
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE reputation_event ADD COLUMN revoked_rating_id INTEGER REFERENCES reputation_event(id)");
            stmt.execute("CREATE UNIQUE INDEX idx_reputation_revocation ON reputation_event(revoked_rating_id) WHERE revoked_rating_id IS NOT NULL");
            stmt.execute("""
                CREATE TRIGGER validate_reputation_revocation BEFORE INSERT ON reputation_event
                WHEN NEW.revoked_rating_id IS NOT NULL OR NEW.kind = 'revocation'
                BEGIN
                    SELECT RAISE(ABORT, 'Invalid revocation') WHERE
                        NEW.kind <> 'revocation' OR NEW.revoked_rating_id IS NULL
                        OR NEW.actor_uuid IS NULL OR NEW.delta <> 0 OR NEW.cost <> 0
                        OR NOT EXISTS (SELECT 1 FROM reputation_event rating
                            WHERE rating.id = NEW.revoked_rating_id AND rating.target_uuid = NEW.target_uuid
                            AND rating.kind IN ('positive', 'negative', 'system_kill', 'admin_give', 'admin_take'));
                END
                """);
        }
    }
}
