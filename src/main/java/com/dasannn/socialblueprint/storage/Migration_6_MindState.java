package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.*;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Schema and release-1 conversion commit together exactly once. */
public final class Migration_6_MindState implements Migration {
    private final LegacyMindConversion legacy;
    private final Clock clock;
    public Migration_6_MindState() { this(LegacyMindConversion.DEFAULT, Clock.systemUTC()); }
    public Migration_6_MindState(LegacyMindConversion legacy, Clock clock) {
        this.legacy = Objects.requireNonNull(legacy); this.clock = Objects.requireNonNull(clock);
    }
    public int version() { return 6; }
    public String description() { return "Signed mental state and immutable mind events"; }
    public void apply(Connection conn) throws SQLException {
        Instant now = clock.instant();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE player_profile ADD COLUMN mind_value REAL NOT NULL DEFAULT 0 CHECK(mind_value BETWEEN -100 AND 100)");
            stmt.execute("ALTER TABLE player_profile ADD COLUMN mind_clean_day_at TEXT");
            stmt.execute("""
                    CREATE TABLE mind_event (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL,
                        kind TEXT NOT NULL, source TEXT NOT NULL, requested_delta REAL NOT NULL,
                        applied_delta REAL NOT NULL, before REAL NOT NULL CHECK(before BETWEEN -100 AND 100),
                        after REAL NOT NULL CHECK(after BETWEEN -100 AND 100), created_at TEXT NOT NULL, actor_uuid TEXT
                    )
                    """);
            stmt.execute("CREATE INDEX idx_mind_player ON mind_event(player_uuid, created_at)");
            stmt.execute("CREATE TRIGGER mind_event_no_update BEFORE UPDATE ON mind_event BEGIN SELECT RAISE(ABORT, 'mind_event is immutable'); END");
            stmt.execute("CREATE TRIGGER mind_event_no_delete BEFORE DELETE ON mind_event BEGIN SELECT RAISE(ABORT, 'mind_event is immutable'); END");
        }
        // R1 could write kill/credit rows before the join profile warm-up committed.
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO player_profile(uuid, last_known_name, created_at, updated_at)
                SELECT player_uuid, player_uuid, ?, ? FROM
                    (SELECT killer_uuid AS player_uuid FROM psychosis_event UNION SELECT player_uuid FROM psychosis_streak)
                """)) {
            ps.setString(1, StorageTimestamps.format(now)); ps.setString(2, StorageTimestamps.format(now)); ps.executeUpdate();
        }
        List<PlayerId> players = new ArrayList<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT uuid FROM player_profile")) {
            while (rs.next()) players.add(PlayerId.fromString(rs.getString(1)));
        }
        for (PlayerId player : players) {
            int kills = 0;
            try (PreparedStatement ps = conn.prepareStatement("SELECT created_at FROM psychosis_event WHERE killer_uuid = ? AND context = 'open'")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Instant when = StorageTimestamps.parse(rs.getString(1));
                        if (when.isAfter(now.minus(legacy.window())) && !when.isAfter(now)) kills++;
                    }
                }
            }
            double credit = 0;
            try (PreparedStatement ps = conn.prepareStatement("SELECT active_millis FROM psychosis_streak WHERE player_uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) credit = rs.getDouble(1); }
            }
            double value = legacy.convert(kills, credit);
            MindRepository.writeInternal(conn, new MindEvent(0, player, "upgrade", "release-1", value, value, 0, value, now, null), true);
        }
    }
}
