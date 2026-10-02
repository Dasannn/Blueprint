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
    public static final double REACH_MARGIN = 1;
    public record Position(UUID world, double x, double y, double z, double yaw) {
        public double distanceSquared(Position other) {
            double dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
    public record Follow(boolean ended, Position position) {}

    public static boolean followEnds(Position previousSubject, Position subject, boolean teleported) {
        return teleported || !previousSubject.world().equals(subject.world())
                || previousSubject.distanceSquared(subject) > 24 * 24;
    }

    /** Beside/behind the look direction; bounded steps, with immediate radial retreat inside the guard. */
    public static Position followCandidate(Position subject, Position last, double distance, double guard, int ticks) {
        double yaw = Math.toRadians(subject.yaw());
        double targetX = subject.x() + Math.sin(yaw - Math.PI / 4) * distance;
        double targetZ = subject.z() - Math.cos(yaw - Math.PI / 4) * distance;
        double dx = targetX - last.x(), dz = targetZ - last.z();
        double length = Math.hypot(dx, dz), step = Math.min(1, ticks * .4 / Math.max(length, .0001));
        double x = last.x() + dx * step, z = last.z() + dz * step;
        double radial = Math.hypot(x - subject.x(), z - subject.z());
        if (radial <= guard) {
            double oldRadius = Math.hypot(last.x() - subject.x(), last.z() - subject.z());
            if (oldRadius > guard) {
                double oldAngle = Math.atan2(last.x() - subject.x(), subject.z() - last.z());
                double targetAngle = yaw - Math.PI / 4;
                double turn = Math.atan2(Math.sin(targetAngle - oldAngle), Math.cos(targetAngle - oldAngle));
                double angle = oldAngle + Math.copySign(Math.min(Math.abs(turn), Math.min(.5, ticks * .4 / distance)), turn);
                x = subject.x() + Math.sin(angle) * distance;
                z = subject.z() - Math.cos(angle) * distance;
                return new Position(subject.world(), x, subject.y(), z,
                        Math.toDegrees(Math.atan2(x - subject.x(), subject.z() - z)));
            }
            double angle = radial < .0001 ? yaw - Math.PI / 4 : Math.atan2(last.x() - subject.x(), subject.z() - last.z());
            x = subject.x() + Math.sin(angle) * distance;
            z = subject.z() - Math.cos(angle) * distance;
        }
        return new Position(subject.world(), x, subject.y(), z,
                Math.toDegrees(Math.atan2(x - subject.x(), subject.z() - z)));
    }

    /** A null probe means no standable, clear surface: preserve the last safe position. */
    public static Follow follow(Position previousSubject, Position subject, Position last, Position ground,
                                double guard, boolean teleported) {
        if (followEnds(previousSubject, subject, teleported)) return new Follow(true, last);
        if (ground == null || !subject.world().equals(ground.world())
                || Math.hypot(ground.x() - subject.x(), ground.z() - subject.z()) <= guard)
            ground = last;
        return new Follow(false, new Position(ground.world(), ground.x(), ground.y(), ground.z(),
                Math.toDegrees(Math.atan2(ground.x() - subject.x(), subject.z() - ground.z()))));
    }
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

    public record Offset(double x, double z) {}

    /** Horizontal look direction at configured distance, with at most half a block of sideways variation. */
    public static Offset apparitionOffset(double yawDegrees, double range, double randomUnit) {
        double yaw = Math.toRadians(yawDegrees);
        double lateral = (randomUnit * 2 - 1) * Math.min(.5, range * .1);
        return new Offset(-Math.sin(yaw) * range + Math.cos(yaw) * lateral,
                Math.cos(yaw) * range + Math.sin(yaw) * lateral);
    }

    public static boolean inView(double yawDegrees, double pitchDegrees, double dx, double dy, double dz) {
        double yaw = Math.toRadians(yawDegrees), pitch = Math.toRadians(pitchDegrees);
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double dot = -Math.sin(yaw) * Math.cos(pitch) * dx - Math.sin(pitch) * dy
                + Math.cos(yaw) * Math.cos(pitch) * dz;
        return Double.isFinite(length) && length > 0 && dot / length >= Math.cos(Math.toRadians(45));
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
