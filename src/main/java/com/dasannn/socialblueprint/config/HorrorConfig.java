package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.feature.effects.AmbientEffectType;
import org.bukkit.configuration.ConfigurationSection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded settings for the seven private horror renderers. */
public record HorrorConfig(int footstepsTicks, int steps, double startDistance, double endDistance,
                           float stepVolume, float stepPitch, List<String> watcherKinds,
                           double watcherMin, double watcherMax, double lookDot, int watcherTicks,
                           double noiseRange, float noiseVolume, float noisePitch, int noiseTicks,
                           double flickerRange, int lightCount, int flickers, int flickerTicks,
                           int subliminalTicks, int vignetteTicks, double lightningRange,
                           int lightningTicks, String thunderSlot, int thunderPlayback) {
    public HorrorConfig { watcherKinds = List.copyOf(watcherKinds); }
    public static final Set<AmbientEffectType> TYPES = Set.of(AmbientEffectType.FOOTSTEPS,
            AmbientEffectType.WATCHER, AmbientEffectType.NEARBY_NOISES, AmbientEffectType.TORCH_FLICKER,
            AmbientEffectType.SUBLIMINAL, AmbientEffectType.RED_VIGNETTE, AmbientEffectType.FAKE_LIGHTNING);
    public static final Map<String, Set<String>> PARAMETERS = Map.of(
            "footsteps", Set.of("duration-ticks", "steps", "start-distance-blocks", "end-distance-blocks", "volume", "pitch"),
            "watcher", Set.of("kinds", "min-distance-blocks", "max-distance-blocks", "look-dot", "duration-ticks"),
            "nearby-noises", Set.of("range-blocks", "volume", "pitch", "playback-ticks"),
            "torch-flicker", Set.of("range-blocks", "max-lights", "flickers", "duration-ticks"),
            "subliminal", Set.of("duration-ticks"), "red-vignette", Set.of("duration-ticks"),
            "fake-lightning", Set.of("range-blocks", "duration-ticks", "sound-slot", "playback-ticks"));

    public static HorrorConfig load(ConfigurationSection root) {
        double start = number(root, "footsteps.start-distance-blocks", 6, 2, 12);
        double end = number(root, "footsteps.end-distance-blocks", 2, 1, 12);
        if (end >= start) fail("footsteps.end-distance-blocks", "Must be below start distance");
        double min = number(root, "watcher.min-distance-blocks", 20, 20, 40);
        double max = number(root, "watcher.max-distance-blocks", 40, min, 40);
        int ticks = integer(root, "footsteps.duration-ticks", 60, 20, 100);
        int steps = integer(root, "footsteps.steps", 7, 2, 16);
        if (steps > ticks) fail("footsteps.steps", "Must fit duration");
        int flickerTicks = integer(root, "torch-flicker.duration-ticks", 40, 6, 100);
        int flickers = integer(root, "torch-flicker.flickers", 3, 1, 8);
        if (flickers * 2 > flickerTicks) fail("torch-flicker.flickers", "Must fit duration");
        String slot = root.getString("effects.fake-lightning.sound-slot", "horror-thunder");
        if (!root.isString("effects.fake-lightning.sound-slot") && root.contains("effects.fake-lightning.sound-slot")
                || slot == null || slot.isBlank()) fail("fake-lightning.sound-slot", "Must name a sound slot");
        return new HorrorConfig(ticks, steps, start, end,
                (float) number(root, "footsteps.volume", .6, 0, 2), (float) number(root, "footsteps.pitch", 1, .1, 2),
                PresentationConfig.choices(root, "effects.watcher.kinds", List.of("enderman"), Set.of("enderman", "wither_skeleton")),
                min, max, number(root, "watcher.look-dot", .985, .95, 1), integer(root, "watcher.duration-ticks", 100, 1, 200),
                number(root, "nearby-noises.range-blocks", 8, 1, 12),
                (float) number(root, "nearby-noises.volume", .7, 0, 2), (float) number(root, "nearby-noises.pitch", .8, .1, 2),
                integer(root, "nearby-noises.playback-ticks", 40, 1, 100),
                number(root, "torch-flicker.range-blocks", 8, 1, 12), integer(root, "torch-flicker.max-lights", 3, 1, 8),
                flickers, flickerTicks, integer(root, "subliminal.duration-ticks", 3, 1, 10),
                integer(root, "red-vignette.duration-ticks", 60, 1, 100), number(root, "fake-lightning.range-blocks", 12, 4, 24),
                integer(root, "fake-lightning.duration-ticks", 20, 1, 40), slot,
                integer(root, "fake-lightning.playback-ticks", 80, 1, 200));
    }
    public static HorrorConfig defaults() { return load(new org.bukkit.configuration.MemoryConfiguration()); }
    public long durationTicks(AmbientEffectType type, SoundsConfigSection sounds) {
        return switch (type) {
            case FOOTSTEPS -> footstepsTicks;
            case WATCHER -> watcherTicks;
            case NEARBY_NOISES -> noiseTicks;
            case TORCH_FLICKER -> flickerTicks;
            case SUBLIMINAL -> subliminalTicks;
            case RED_VIGNETTE -> vignetteTicks;
            case FAKE_LIGHTNING -> Math.max(lightningTicks, sounds.get(thunderSlot).layers().stream()
                    .filter(layer -> !layer.isSilent()).mapToLong(SoundLayerConfig::delay).max().orElse(0) + thunderPlayback);
            default -> throw new IllegalArgumentException("Not a horror effect");
        };
    }
    public void validateSounds(SoundsConfigSection sounds) {
        if (!sounds.slots().containsKey(thunderSlot)) fail("fake-lightning.sound-slot", "Must name an existing sounds slot");
        if (sounds.get(thunderSlot).layers().stream().anyMatch(layer -> !layer.isSilent() && layer.delay() > 200 - thunderPlayback))
            fail("fake-lightning.playback-ticks", "Final delay plus playback must not exceed 200 ticks");
    }
    private static int integer(ConfigurationSection root, String key, int fallback, int min, int max) {
        if (root.contains("effects." + key) && !root.isInt("effects." + key)) fail(key, "Must be integer");
        return (int) number(root, key, fallback, min, max);
    }
    private static double number(ConfigurationSection root, String key, double fallback, double min, double max) {
        String path = "effects." + key;
        if (root.contains(path) && !(root.get(path) instanceof Number)) fail(key, "Must be numeric");
        double value = root.getDouble(path, fallback);
        if (!Double.isFinite(value) || value < min || value > max) fail(key, "Out of bounds: " + min + " to " + max);
        return value;
    }
    private static void fail(String key, String reason) { throw new ConfigValidationException("effects." + key, reason); }
}
