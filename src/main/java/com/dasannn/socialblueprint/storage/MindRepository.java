package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.*;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** All read/apply/write operations serialize on the existing storage executor. */
public final class MindRepository {
    private final StorageEngine engine;
    private final List<Consumer<PlayerId>> invalidationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    public MindRepository(StorageEngine engine) { this.engine = Objects.requireNonNull(engine); }
    public void addInvalidationListener(Consumer<PlayerId> listener) { invalidationListeners.add(listener); }
    private void invalidate(PlayerId player) { invalidationListeners.forEach(listener -> listener.accept(player)); }
    public double value(PlayerId player) { return engine.execute(conn -> valueInternal(conn, player)); }
    static double valueInternal(Connection conn, PlayerId player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT mind_value FROM player_profile WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getDouble(1) : 0; }
        }
    }
    public CompletableFuture<MindState.Result> applyAsync(PlayerId player, MindInput kind, MindInputConfig config, String source, Instant now) {
        return engine.executeAsync(conn -> {
            boolean auto = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                MindState.Result result = applyInternal(conn, player, kind, config, source, now);
                conn.commit(); return result;
            } catch (SQLException | RuntimeException ex) { conn.rollback(); throw ex; }
            finally { conn.setAutoCommit(auto); }
        }).thenApply(result -> { if (result.enabled()) invalidate(player); return result; });
    }
    static MindState.Result applyInternal(Connection conn, PlayerId player, MindInput kind, MindInputConfig config, String source, Instant now) throws SQLException {
        double before = valueInternal(conn, player);
        if (config.enabled() && (!kind.bad() || kind == MindInput.HONOR_REVIEW_NEGATIVE) && capped(conn, player, kind, config.cap(), now))
            return new MindState.Result(before, 0, 0, before, false);
        MindState.Result result = MindState.apply(before, kind, config);
        if (result.enabled()) writeInternal(conn, new MindEvent(0, player, kind.id(), source,
                result.requestedDelta(), result.appliedDelta(), result.before(), result.after(), now, null), kind.bad() || kind == MindInput.CLEAN_DAY);
        return result;
    }
    /** SB-146: bounded reversal of the historical applied delta, including across Neutral. */
    static void reverseHonorInternal(Connection conn, PlayerId player, long ratingId, PlayerId admin, Instant now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT applied_delta FROM mind_event WHERE player_uuid = ? AND kind = 'honor-review' AND source = ?")) {
            ps.setString(1, player.toString()); ps.setString(2, Long.toString(ratingId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getDouble(1) == 0) return;
                double requested = -rs.getDouble(1);
                double before = valueInternal(conn, player);
                double after = Math.max(-100, Math.min(100, before + requested));
                writeInternal(conn, new MindEvent(0, player, "honor-revoke", Long.toString(ratingId),
                        requested, after - before, before, after, now, admin), false);
            }
        }
    }
    private static boolean capped(Connection conn, PlayerId player, MindInput kind, int cap, Instant now) throws SQLException {
        if (cap == 0) return true;
        String kinds = kind.peaceful() ? "kind IN ('fishing','breeding','feeding','planting','harvesting')" : "kind = ?";
        try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM mind_event WHERE player_uuid = ? AND created_at > ? AND created_at <= ? AND " + kinds)) {
            ps.setString(1, player.toString());
            ps.setString(2, StorageTimestamps.format(now.minus(java.time.Duration.ofHours(24))));
            ps.setString(3, StorageTimestamps.format(now));
            if (!kind.peaceful()) ps.setString(4, kind.id());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getLong(1) >= cap; }
        }
    }

    /** One main-thread active interval, clipped against any intervening bad action/reset. */
    public CompletableFuture<MindState.Result> accountActiveAsync(PlayerId player, double activeMillis,
            double requiredMinutes, MindInputConfig config, Instant now) {
        return accountActiveAsync(player, activeMillis, now, requiredMinutes, config, now);
    }
    public CompletableFuture<MindState.Result> accountActiveAsync(PlayerId player, double activeMillis,
            Instant activeEnd, double requiredMinutes, MindInputConfig config, Instant now) {
        if (!Double.isFinite(activeMillis) || activeMillis < 0 || !Double.isFinite(requiredMinutes) || requiredMinutes < 0)
            throw new IllegalArgumentException("Active duration and minimum must be finite and nonnegative");
        return engine.executeAsync(conn -> {
            boolean auto = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                String anchor;
                try (var ps = conn.prepareStatement("SELECT COALESCE(mind_clean_day_at, created_at) FROM player_profile WHERE uuid = ?")) {
                    ps.setString(1, player.toString());
                    try (var rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.commit();
                            return new MindState.Result(0, 0, 0, 0, false);
                        }
                        anchor = rs.getString(1);
                    }
                }
                double total = 0;
                try (var ps = conn.prepareStatement("SELECT active_millis FROM mind_activity WHERE player_uuid = ? AND anchor = ?")) {
                    ps.setString(1, player.toString()); ps.setString(2, anchor);
                    try (var rs = ps.executeQuery()) { if (rs.next()) total = rs.getDouble(1); }
                }
                Instant since = StorageTimestamps.parse(anchor);
                double elapsed = Math.max(0, java.time.Duration.between(since, activeEnd).toMillis());
                total += Math.min(activeMillis, elapsed);
                try (var ps = conn.prepareStatement("INSERT INTO mind_activity(player_uuid,anchor,active_millis) VALUES(?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET anchor=excluded.anchor, active_millis=excluded.active_millis")) {
                    ps.setString(1, player.toString()); ps.setString(2, anchor); ps.setDouble(3, total); ps.executeUpdate();
                }
                double before = valueInternal(conn, player);
                MindState.Result result = new MindState.Result(before, 0, 0, before, false);
                if (com.dasannn.socialblueprint.domain.MindTriggers.cleanDay(since, now, total, requiredMinutes)) {
                    result = applyInternal(conn, player, MindInput.CLEAN_DAY, config, "active-play", now);
                    if (result.enabled()) {
                        try (var ps = conn.prepareStatement("DELETE FROM mind_activity WHERE player_uuid = ?")) {
                            ps.setString(1, player.toString()); ps.executeUpdate();
                        }
                    }
                }
                conn.commit(); return result;
            } catch (SQLException | RuntimeException ex) { conn.rollback(); throw ex; }
            finally { conn.setAutoCommit(auto); }
        }).thenApply(result -> { if (result.enabled()) invalidate(player); return result; });
    }
    static void writeInternal(Connection conn, MindEvent event, boolean restartCleanDay) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO player_profile(uuid, last_known_name, created_at, updated_at, mind_value, mind_clean_day_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET mind_value = excluded.mind_value, updated_at = excluded.updated_at,
                    mind_clean_day_at = CASE WHEN ? THEN excluded.mind_clean_day_at ELSE player_profile.mind_clean_day_at END
                """)) {
            ps.setString(1, event.player().toString()); ps.setString(2, event.player().toString());
            ps.setString(3, StorageTimestamps.format(event.createdAt())); ps.setString(4, StorageTimestamps.format(event.createdAt()));
            ps.setDouble(5, event.after()); ps.setString(6, StorageTimestamps.format(event.createdAt())); ps.setBoolean(7, restartCleanDay);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO mind_event(player_uuid, kind, source, requested_delta, applied_delta, before, after, created_at, actor_uuid)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, event.player().toString()); ps.setString(2, event.kind()); ps.setString(3, event.source());
            ps.setDouble(4, event.requestedDelta()); ps.setDouble(5, event.appliedDelta()); ps.setDouble(6, event.before()); ps.setDouble(7, event.after());
            ps.setString(8, StorageTimestamps.format(event.createdAt())); ps.setString(9, event.actor() == null ? null : event.actor().toString());
            ps.executeUpdate();
        }
    }
    public CompletableFuture<Integer> resetAsync(PlayerId player, PlayerId actor, Instant now) {
        return resetPlayersAsync(player, actor, now);
    }
    public CompletableFuture<Integer> resetAllAsync(PlayerId actor, Instant now) { return resetPlayersAsync(null, actor, now); }
    private CompletableFuture<Integer> resetPlayersAsync(PlayerId target, PlayerId actor, Instant now) {
        Objects.requireNonNull(actor); Objects.requireNonNull(now);
        return engine.executeAsync(conn -> {
            List<PlayerId> players = new ArrayList<>();
            if (target != null) players.add(target);
            else try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT uuid FROM player_profile")) {
                while (rs.next()) players.add(PlayerId.fromString(rs.getString(1)));
            }
            boolean auto = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                AuditRepository audit = new AuditRepository(engine);
                for (PlayerId player : players) {
                    double before = valueInternal(conn, player);
                    writeInternal(conn, new MindEvent(0, player, "admin-reset", target == null ? "reset-all" : "reset",
                            -before, -before, before, 0, now, actor), true);
                    audit.saveInternal(conn, AuditEvent.forPlayer(actor, target == null ? "mind-reset-all" : "mind-reset",
                            player, Double.toString(before), "0.0", now));
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) { conn.rollback(); throw ex; }
            finally { conn.setAutoCommit(auto); }
            return players;
        }).thenApply(players -> { players.forEach(this::invalidate); return players.size(); });
    }
    public List<MindEvent> events(PlayerId player) {
        return engine.execute(conn -> {
            List<MindEvent> events = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM mind_event WHERE player_uuid = ? ORDER BY id")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) events.add(new MindEvent(rs.getLong("id"), player, rs.getString("kind"), rs.getString("source"),
                            rs.getDouble("requested_delta"), rs.getDouble("applied_delta"), rs.getDouble("before"), rs.getDouble("after"),
                            StorageTimestamps.parse(rs.getString("created_at")), rs.getString("actor_uuid") == null ? null : PlayerId.fromString(rs.getString("actor_uuid"))));
                }
            }
            return List.copyOf(events);
        });
    }
}
