package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.SerenityEffectsConfig;
import com.dasannn.socialblueprint.config.SoundsConfigSection;
import com.dasannn.socialblueprint.config.SoundLayerConfig;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Plain decisions; no player, registry object, metric write or mechanical modifier. */
public final class SereneEpisode {
    private SereneEpisode() {}
    public record Candidate(UUID id, boolean online, boolean sameWorld, boolean visible,
                            boolean vanished, double distanceSquared) {}
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public boolean separatedFrom(Bounds other) {
            return maxX + 1 < other.minX || minX - 1 > other.maxX
                    || maxY + 1 < other.minY || minY - 1 > other.maxY
                    || maxZ + 1 < other.minZ || minZ - 1 > other.maxZ;
        }
        public boolean outsideReach(double x, double y, double z, double reach) {
            double dx = Math.max(minX - x, Math.max(0, x - maxX));
            double dy = Math.max(minY - y, Math.max(0, y - maxY));
            double dz = Math.max(minZ - z, Math.max(0, z - maxZ));
            return Double.isFinite(reach) && reach >= 0 && dx * dx + dy * dy + dz * dz > reach * reach;
        }
    }

    public static boolean allows(PsychosisLevel direction, double magnitude, SerenityEffectsConfig.Rule rule) {
        return direction == PsychosisLevel.SERENITY && rule.enabled() && Double.isFinite(magnitude)
                && magnitude >= rule.minimumSerenity() && rule.sessionCap() > 0;
    }

    public static Set<UUID> audience(UUID subject, boolean dawn, Collection<Candidate> candidates, double range) {
        Set<UUID> result = candidates.stream().filter(c -> !dawn && c.online() && c.sameWorld()
                && c.visible() && !c.vanished() && c.distanceSquared() >= 0 && c.distanceSquared() <= range * range)
                .map(Candidate::id).collect(Collectors.toSet());
        result.add(subject);
        return Set.copyOf(result);
    }

    public static Set<UUID> departed(Set<UUID> previous, Set<UUID> current) {
        return previous.stream().filter(id -> !current.contains(id)).collect(Collectors.toUnmodifiableSet());
    }

    public static long durationTicks(String effect, SerenityEffectsConfig config, SoundsConfigSection sounds) {
        return switch (effect) {
            case "dawn" -> config.dawnDuration();
            case "particles" -> config.particles().totalTicks();
            case "apparition" -> config.animalDuration();
            case "source-less-sounds" -> sounds.get(config.sounds().slot()).layers().stream()
                    .filter(layer -> !layer.isSilent()).mapToLong(SoundLayerConfig::delay).max().orElse(0)
                    + config.sounds().playbackTicks();
            default -> throw new IllegalArgumentException(effect);
        };
    }

    public static long reservationTicks(String effect, SerenityEffectsConfig config, SoundsConfigSection sounds) {
        return durationTicks(effect, config, sounds) + Math.max(config.intervalTicks(), config.quietTicks());
    }
}
