package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for {@link PlayerProfile}s per T-018, SB-060 and ARCHITECTURE.md §4.
 */
public final class ProfileRepository {

    private final StorageEngine engine;

    public ProfileRepository(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "StorageEngine must not be null");
    }

    public void save(PlayerProfile profile) {
        Objects.requireNonNull(profile, "Profile must not be null");
        engine.run(conn -> saveInternal(conn, profile));
    }

    public CompletableFuture<Void> saveAsync(PlayerProfile profile) {
        Objects.requireNonNull(profile, "Profile must not be null");
        return engine.runAsync(conn -> saveInternal(conn, profile));
    }

    void saveInternal(Connection conn, PlayerProfile profile) throws SQLException {
        String sql = """
            INSERT INTO player_profile (uuid, last_known_name, effects_opt_out, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                last_known_name = excluded.last_known_name,
                effects_opt_out = excluded.effects_opt_out,
                updated_at = excluded.updated_at;
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, profile.id().toString());
            ps.setString(2, profile.lastKnownName());
            ps.setInt(3, profile.effectsOptOut() ? 1 : 0);
            ps.setString(4, StorageTimestamps.format(profile.createdAt()));
            ps.setString(5, StorageTimestamps.format(profile.updatedAt()));
            ps.executeUpdate();
        }
    }

    public Optional<PlayerProfile> findById(PlayerId id) {
        Objects.requireNonNull(id, "PlayerId must not be null");
        return engine.execute(conn -> {
            String sql = """
                SELECT uuid, last_known_name, effects_opt_out, created_at, updated_at
                FROM player_profile
                WHERE uuid = ?;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    public Optional<PlayerProfile> findByName(String name) {
        Objects.requireNonNull(name, "Name must not be null");
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return engine.execute(conn -> {
            String sql = """
                SELECT uuid, last_known_name, effects_opt_out, created_at, updated_at
                FROM player_profile
                WHERE LOWER(last_known_name) = ?;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, normalized);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    private static PlayerProfile mapRow(ResultSet rs) throws SQLException {
        PlayerId id = PlayerId.fromString(rs.getString("uuid"));
        String lastKnownName = rs.getString("last_known_name");
        boolean effectsOptOut = rs.getInt("effects_opt_out") != 0;
        Instant createdAt = StorageTimestamps.parse(rs.getString("created_at"));
        Instant updatedAt = StorageTimestamps.parse(rs.getString("updated_at"));

        return new PlayerProfile(id, lastKnownName, effectsOptOut, createdAt, updatedAt);
    }
}
