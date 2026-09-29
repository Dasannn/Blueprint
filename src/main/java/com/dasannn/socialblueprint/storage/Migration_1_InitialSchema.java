package com.dasannn.socialblueprint.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Migration 1: Initial schema creating all 8 tables defined in ARCHITECTURE.md §4.
 */
public final class Migration_1_InitialSchema implements Migration {

    @Override
    public int version() {
        return 1;
    }

    @Override
    public String description() {
        return "Initial schema per ARCHITECTURE.md §4";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // player_profile: uuid, last known name, opt-out flag, created/updated
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_profile (
                    uuid TEXT PRIMARY KEY NOT NULL,
                    last_known_name TEXT NOT NULL,
                    effects_opt_out INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
            """);

            // reputation_event: id, actor uuid, target uuid, delta, kind, cost, reason, created_at
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

            // psychosis_event: id, killer uuid, victim uuid, context (duel/open), created_at
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

            // honor_allowance: actor uuid, target uuid, sign, count, window_start
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

            // duel: id, state, created_at, ended_at
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS duel (
                    id TEXT PRIMARY KEY NOT NULL,
                    state TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    ended_at TEXT
                );
            """);

            // duel_participant: duel id, uuid, side
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS duel_participant (
                    duel_id TEXT NOT NULL,
                    uuid TEXT NOT NULL,
                    side TEXT NOT NULL,
                    PRIMARY KEY (duel_id, uuid)
                );
            """);

            // audit_event: id, actor, operation, target, before, after, created_at
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
        }
    }
}
