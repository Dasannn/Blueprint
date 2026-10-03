package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.PsychosisLevel;
import com.dasannn.socialblueprint.feature.effects.AmbientEffectType;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed SB-102–108 settings. No registry-backed Bukkit objects in the decisions. */
public record PresentationConfig(Map<AmbientEffectType, Rule> rules, Sky sky, Particles particles,
                                 Flash flash, Sounds sounds, Episodes episodes, Block block, Block sign,
                                 Hurt hurt, Ghost ghost, Toast toast, Bar bar, double deathRange, int maxVisibleLength, DurationScale durationScale, Phantom phantom, HorrorConfig horror) {
    public PresentationConfig(Map<AmbientEffectType, Rule> rules, Sky sky, Particles particles,
                              Flash flash, Sounds sounds, Episodes episodes, Block block, Block sign,
                              Hurt hurt, Ghost ghost, Toast toast, Bar bar, double deathRange, int maxVisibleLength,
                              DurationScale durationScale, Phantom phantom) {
        this(rules, sky, particles, flash, sounds, episodes, block, sign, hurt, ghost, toast, bar,
                deathRange, maxVisibleLength, durationScale, phantom, HorrorConfig.defaults());
    }
    public record Rule(boolean enabled, PsychosisLevel minimumLevel, SingleEffectConfig limits) {
        public boolean allows(PsychosisLevel level) {
            return enabled && level.hasMadnessEffects() && level.ordinal() >= minimumLevel.ordinal();
        }
    }
    public record Episodes(long lowTicks, long mediumTicks, long highTicks, long extremeTicks, long quietTicks,
                           int lowConcurrent, int mediumConcurrent, int highConcurrent, int extremeConcurrent) {
        public int maxConcurrent(PsychosisLevel level) {
            return switch (level) {
                case LOW -> lowConcurrent;
                case MEDIUM -> mediumConcurrent;
                case HIGH -> highConcurrent;
                case EXTREME -> extremeConcurrent;
                default -> throw new IllegalArgumentException("This direction has no madness episodes");
            };
        }
        public long intervalTicks(PsychosisLevel level) {
            return switch (level) {
                case LOW -> lowTicks;
                case MEDIUM -> mediumTicks;
                case HIGH -> highTicks;
                case EXTREME -> extremeTicks;
                case NEUTRAL, SERENITY -> throw new IllegalArgumentException("This direction has no madness episodes");
            };
        }
    }
    public record DurationScale(double low, double medium, double high, double extreme) {
        public DurationScale(double medium, double high, double extreme) { this(1, medium, high, extreme); }
        public DurationScale {
            if (!Double.isFinite(low) || low < 1) fail("episodes.duration-scale.low", "Must be finite and >= 1");
            if (!Double.isFinite(medium) || medium < low) fail("episodes.duration-scale.medium", "Must be finite and >= low");
            if (!Double.isFinite(high) || high < medium) fail("episodes.duration-scale.high", "Must be finite and >= medium");
            if (!Double.isFinite(extreme) || extreme < high) fail("episodes.duration-scale.extreme", "Must be finite and >= high");
        }
        public int ticks(int base, PsychosisLevel level) { return ticks(base, level, 100); }
        public int ticks(int base, PsychosisLevel level, int cap) {
            double factor = switch (level) {
                case LOW -> low;
                case MEDIUM -> medium;
                case HIGH -> high;
                case EXTREME -> extreme;
                default -> 1;
            };
            return (int) Math.min(cap, Math.ceil(base * factor));
        }
    }
    public record Phantom(java.util.List<String> mobs, double distance, int durationTicks) {
        public Phantom { mobs = java.util.List.copyOf(mobs); }
    }

    /** Scale renderer settings once; the scheduler uses this same description for quiet time. */
    public PresentationConfig scaled(PsychosisLevel level) {
        int total = durationScale.ticks(flash.totalTicks(), level);
        int fadeIn = (int) ((long) total * flash.fadeInTicks() / flash.totalTicks());
        int fadeOut = (int) ((long) total * flash.fadeOutTicks() / flash.totalTicks());
        return new PresentationConfig(rules,
                new Sky(sky.mode(), durationScale.ticks(sky.durationTicks(), level, 200)),
                new Particles(particles.types(), particles.placement(), particles.count(), particles.radius(),
                        durationScale.ticks(particles.durationTicks(), level)),
                new Flash(flash.channel(), fadeIn, total - fadeIn - fadeOut, fadeOut), sounds, episodes,
                new Block(block.data(), block.range(), durationScale.ticks(block.durationTicks(), level)),
                new Block(sign.data(), sign.range(), durationScale.ticks(sign.durationTicks(), level)), hurt,
                new Ghost(ghost.range(), durationScale.ticks(ghost.durationTicks(), level)),
                new Toast(toast.icon(), durationScale.ticks(toast.durationTicks(), level)),
                new Bar(bar.colour(), bar.style(), bar.progress(), durationScale.ticks(bar.durationTicks(), level)),
                deathRange, maxVisibleLength, durationScale,
                new Phantom(phantom.mobs(), phantom.distance(), durationScale.ticks(phantom.durationTicks(), level)), horror);
    }

    public record Sky(String mode, int durationTicks) {}
    public static final int MAX_PARTICLE_COUNT = 64;
    public static final int MAX_PARTICLE_SENDS = 512;

    // Smoke/WhiteSmoke <= 40 ticks, Soul <= 44, EndRod <= 71; include the removal tick.
    public static final Map<String, Integer> PARTICLE_TAILS = Map.of("smoke", 41, "soul", 45, "white_smoke", 41, "end_rod", 72);
    public record Particles(List<String> types, String placement, int count, double radius, int durationTicks) {
        public Particles { types = List.copyOf(types); }
        public Particles(String type, String placement, int count, double radius, int durationTicks) {
            this(List.of(type), placement, count, radius, durationTicks);
        }
        public Particles choose(java.util.Random random) {
            return new Particles(types.get(random.nextInt(types.size())), placement, count, radius, totalTicks());
        }
        public boolean fitsAudience(int viewers) {
            return viewers > 0 && (long) count * viewers <= MAX_PARTICLE_SENDS;
        }
        public long emissionDelay(int index) {
            return (long) Math.max(0, totalTicks() - tailTicks()) * (count == 1 ? 1 : index) / Math.max(1, count - 1);
        }
        private int tailTicks() { return types.stream().mapToInt(PARTICLE_TAILS::get).max().orElseThrow(); }
        public int totalTicks() { return Math.max(durationTicks, tailTicks()); }
    }
    public static List<String> choices(ConfigurationSection root, String path, List<String> fallback, Set<String> allowed) {
        Object raw = root.get(path);
        if (raw == null) return List.copyOf(fallback);
        if (!(raw instanceof List<?> list) || list.isEmpty())
            throw new ConfigValidationException(path, "Must be a nonempty list");
        List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            if (!(entry instanceof String value) || !allowed.contains(value.toLowerCase(Locale.ROOT)))
                throw new ConfigValidationException(path + "[" + i + "]", "Invalid entry: " + entry);
            result.add(((String) entry).toLowerCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }
    public int maxConcurrent(PsychosisLevel level) {
        return episodes == null ? level.ordinal() - PsychosisLevel.LOW.ordinal() + 1 : episodes.maxConcurrent(level);
    }
    public record Flash(String channel, int fadeInTicks, int durationTicks, int fadeOutTicks) {
        public int totalTicks() { return fadeInTicks + durationTicks + fadeOutTicks; }
    }
    public record Sounds(String slot, double forward, double right, double up, int playbackTicks) {}
    public record Block(String data, double range, int durationTicks) {}
    /** Pure cosmetic instruction; deliberately contains no damage or movement fields. */
    public record Hurt(String slot, int playbackTicks) {
        public int animationTicks() { return 10; }
        public float yaw() { return 0; }
    }
    public record Ghost(double range, int durationTicks) {}

    public record Toast(String icon, int durationTicks) {}
    public record Bar(String colour, String style, double progress, int durationTicks) {}

    public PresentationConfig { rules = Map.copyOf(rules); }

    public long durationTicks(AmbientEffectType type, SoundsConfigSection slots) {
        return switch (type) {
            case FOOTSTEPS, WATCHER, NEARBY_NOISES, TORCH_FLICKER, SUBLIMINAL, RED_VIGNETTE, FAKE_LIGHTNING -> horror.durationTicks(type, slots);
            case SILVERFISH -> phantom.durationTicks();
            case ADVANCEMENT_TOAST -> toast.durationTicks();
            case BOSS_BAR -> bar.durationTicks();
            case SKY -> sky.durationTicks();
            case PARTICLES -> particles.totalTicks();
            case SCREEN_FLASH -> flash.totalTicks();
            case BLOCK_CHANGE -> block.durationTicks();
            case SIGN -> sign.durationTicks();
            case VICTIM_GHOST -> ghost.durationTicks();
            case HURT_FLASH -> Math.max(hurt.animationTicks(), soundTicks(slots, hurt.slot(), hurt.playbackTicks()));
            case SOURCE_LESS_SOUNDS -> slots.get(sounds.slot()).layers().stream()
                    .filter(layer -> !layer.isSilent()).mapToLong(SoundLayerConfig::delay).max().orElse(0)
                    + sounds.playbackTicks();
            default -> 0;
        };
    }

    public void validateSounds(SoundsConfigSection slots) {
        if (!slots.slots().containsKey(sounds.slot()))
            fail("source-less-sounds.sound-slot", "Must name an existing sounds slot");
        if (slots.get(sounds.slot()).layers().stream().filter(layer -> !layer.isSilent())
                .anyMatch(layer -> layer.delay() > 100L - sounds.playbackTicks()))
            fail("source-less-sounds.playback-ticks", "Final delay plus playback must not exceed 100 ticks");
    }

    public void validateHurtSounds(SoundsConfigSection slots) {
        if (!slots.slots().containsKey(hurt.slot())) fail("hurt-flash.sound-slot", "Must name an existing sounds slot");
        if (slots.get(hurt.slot()).layers().stream().filter(layer -> !layer.isSilent())
                .anyMatch(layer -> layer.delay() > 100L - hurt.playbackTicks()))
            fail("hurt-flash.playback-ticks", "Final delay plus playback must not exceed 100 ticks");
    }

    private static long soundTicks(SoundsConfigSection slots, String slot, int playback) {
        return slots.get(slot).layers().stream().filter(layer -> !layer.isSilent())
                .mapToLong(SoundLayerConfig::delay).max().orElse(0) + playback;
    }

    public static PresentationConfig load(ConfigurationSection root) {
        ConfigurationSection scale = root.getConfigurationSection("effects.episodes.duration-scale");
        if (root.contains("effects.episodes.duration-scale") && scale == null)
            fail("episodes.duration-scale", "Must be a mapping");
        if (scale != null) for (String key : scale.getKeys(false))
            if (!Set.of("low", "medium", "high", "extreme").contains(key)) fail("episodes.duration-scale." + key, "Unknown key");
        Map<AmbientEffectType, Rule> rules = new java.util.EnumMap<>(AmbientEffectType.class);
        for (AmbientEffectType type : Set.of(AmbientEffectType.SKY, AmbientEffectType.PARTICLES,
                AmbientEffectType.SCREEN_FLASH, AmbientEffectType.SOURCE_LESS_SOUNDS,
                AmbientEffectType.BLOCK_CHANGE, AmbientEffectType.SIGN, AmbientEffectType.HURT_FLASH, AmbientEffectType.VICTIM_GHOST,
                AmbientEffectType.ADVANCEMENT_TOAST, AmbientEffectType.BOSS_BAR, AmbientEffectType.FALSE_DEATH,
                AmbientEffectType.WHISPER, AmbientEffectType.FAKE_ANNOUNCEMENT,
                AmbientEffectType.FOOTSTEPS, AmbientEffectType.WATCHER, AmbientEffectType.NEARBY_NOISES,
                AmbientEffectType.TORCH_FLICKER, AmbientEffectType.SUBLIMINAL, AmbientEffectType.RED_VIGNETTE, AmbientEffectType.FAKE_LIGHTNING)) {
            String id = type.configId();
            String path = "effects." + id;
            Set<String> allowed = new java.util.HashSet<>(Set.of("enabled", "minimum-level", "cooldown-ticks", "session-cap"));
            allowed.addAll(switch (type) {
                case FOOTSTEPS, WATCHER, NEARBY_NOISES, TORCH_FLICKER, SUBLIMINAL, RED_VIGNETTE, FAKE_LIGHTNING -> HorrorConfig.PARAMETERS.get(id);
                case ADVANCEMENT_TOAST -> Set.of("icon", "duration-ticks");
                case BOSS_BAR -> Set.of("colour", "style", "progress", "duration-ticks");
                case FALSE_DEATH -> Set.of("range-blocks");
                case WHISPER -> Set.of("max-visible-length");
                case SKY -> Set.of("mode", "duration-ticks");
                case PARTICLES -> Set.of("types", "placement", "count", "radius-blocks", "duration-ticks");
                case SCREEN_FLASH -> Set.of("channel", "fade-in-ticks", "duration-ticks", "fade-out-ticks");
                case SOURCE_LESS_SOUNDS -> Set.of("sound-slot", "offset", "playback-ticks");
                case BLOCK_CHANGE, SIGN -> Set.of("block-data", "range-blocks", "duration-ticks");
                case HURT_FLASH -> Set.of("sound-slot", "playback-ticks");
                case VICTIM_GHOST -> Set.of("range-blocks", "duration-ticks");
                default -> Set.of();
            });
            ConfigurationSection section = root.getConfigurationSection(path);
            if (root.contains(path) && section == null) fail(id, "Must be a mapping");
            if (section != null) for (String key : section.getKeys(false))
                if (!allowed.contains(key)) fail(id + "." + key, "Unknown key");
            if (root.contains(path + ".enabled") && !root.isBoolean(path + ".enabled"))
                fail(id + ".enabled", "Must be boolean");
            PsychosisLevel floor = type.floor();
            PsychosisLevel minimum;
            try { minimum = PsychosisLevel.valueOf(root.getString(path + ".minimum-level", floor.name()).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException error) { throw new ConfigValidationException(path + ".minimum-level", "Unknown level"); }
            if (minimum.ordinal() < floor.ordinal()) fail(id + ".minimum-level", "Below catalogue floor");
            String legacy = type == AmbientEffectType.WHISPER ? "whisper" : "fake-announcement";
            int cooldownDefault = !root.contains(path + ".cooldown-ticks") && (type == AmbientEffectType.WHISPER || type == AmbientEffectType.FAKE_ANNOUNCEMENT)
                    ? Math.toIntExact(DurationParser.parseNonNegative(root.getString("effects." + legacy + ".cooldown", "5m"),
                            "effects." + legacy + ".cooldown").toMillis() / 50L) : 1200;
            int capDefault = type == AmbientEffectType.WHISPER || type == AmbientEffectType.FAKE_ANNOUNCEMENT
                    ? root.getInt("effects." + legacy + ".session-cap", 6) : 6;
            if ((type == AmbientEffectType.WHISPER || type == AmbientEffectType.FAKE_ANNOUNCEMENT) && !root.contains(path)) continue;
            rules.put(type, new Rule(root.getBoolean(path + ".enabled", root.contains(path)), minimum,
                    new SingleEffectConfig(Duration.ofMillis(integer(root, id + ".cooldown-ticks", Math.max(1, cooldownDefault), 1, Integer.MAX_VALUE) * 50L),
                            integer(root, id + ".session-cap", capDefault, 0, Integer.MAX_VALUE))));
        }
        // Only native particles with a verified finite tail; no unbounded client effects or extra data.
        List<String> particle = choices(root, "effects.particles.types", List.of("smoke", "soul"), PARTICLE_TAILS.keySet());
        Flash flash = new Flash(choice(root, "screen-flash.channel", "title", Set.of("title", "action-bar")),
                integer(root, "screen-flash.fade-in-ticks", 5, 0, 100),
                integer(root, "screen-flash.duration-ticks", 30, 1, 100),
                integer(root, "screen-flash.fade-out-ticks", 5, 0, 100));
        if (flash.totalTicks() > 100) fail("screen-flash.duration-ticks", "Total including fades must not exceed 100 ticks");
        ConfigurationSection offset = root.getConfigurationSection("effects.source-less-sounds.offset");
        if (root.contains("effects.source-less-sounds.offset") && offset == null) fail("source-less-sounds.offset", "Must be a mapping");
        if (offset != null) for (String key : offset.getKeys(false))
            if (!Set.of("forward-blocks", "right-blocks", "up-blocks").contains(key)) fail("source-less-sounds.offset." + key, "Unknown key");
        Episodes episodes = null;
        if (root.contains("effects.episodes")) {
            ConfigurationSection episodeSection = root.getConfigurationSection("effects.episodes");
            if (episodeSection == null) fail("episodes", "Must be a mapping");
            for (String key : episodeSection.getKeys(false))
                if (!Set.of("low", "medium", "high", "extreme", "quiet-ticks", "duration-scale").contains(key))
                    fail("episodes." + key, "Unknown key");
            for (String level : List.of("low", "medium", "high", "extreme")) {
                String key = "episodes." + level;
                ConfigurationSection section = root.getConfigurationSection("effects." + key);
                if (root.contains("effects." + key) && section == null) fail(key, "Must be a mapping");
                if (section != null) for (String leaf : section.getKeys(false))
                    if (!Set.of("interval-ticks", "max-concurrent").contains(leaf)) fail(key + "." + leaf, "Unknown key");
            }
            long low = ticks(root, "episodes.low.interval-ticks", 4800);
            long medium = ticks(root, "episodes.medium.interval-ticks", 2400);
            long high = ticks(root, "episodes.high.interval-ticks", 1200);
            long extreme = ticks(root, "episodes.extreme.interval-ticks", 400);
            if (low <= medium || medium <= high || high <= extreme) fail("episodes", "Intervals must decrease low > medium > high > extreme");
            long quiet = ticks(root, "episodes.quiet-ticks", 20);
            if (quiet > extreme) fail("episodes.quiet-ticks", "Must fit within the extreme interval to preserve level gradation");
            int lowCount = integer(root, "episodes.low.max-concurrent", 1, 1, 1);
            int mediumCount = integer(root, "episodes.medium.max-concurrent", 2, lowCount, 2);
            int highCount = integer(root, "episodes.high.max-concurrent", 3, mediumCount, 3);
            int extremeCount = integer(root, "episodes.extreme.max-concurrent", 4, highCount, 4);
            episodes = new Episodes(low, medium, high, extreme, quiet, lowCount, mediumCount, highCount, extremeCount);
        }
        return new PresentationConfig(rules,
                new Sky(choice(root, "sky.mode", "escalating", Set.of("night", "storm", "both", "escalating")), integer(root, "sky.duration-ticks", 100, 1, 200)),
                new Particles(particle, choice(root, "particles.placement", "around", Set.of("around", "beneath")),
                        integer(root, "particles.count", 8, 1, MAX_PARTICLE_COUNT), number(root, "particles.radius-blocks", 1, true),
                        integer(root, "particles.duration-ticks", 40, 1, 100)), flash,
                new Sounds(choice(root, "source-less-sounds.sound-slot", "source-less", null),
                        number(root, "source-less-sounds.offset.forward-blocks", -2, false),
                        number(root, "source-less-sounds.offset.right-blocks", 0, false),
                        number(root, "source-less-sounds.offset.up-blocks", 0, false),
                        integer(root, "source-less-sounds.playback-ticks", 20, 1, 100)), episodes,
                block(root, "block-change", "minecraft:andesite"), block(root, "sign", "minecraft:oak_sign[rotation=0,waterlogged=false]"),
                new Hurt(choice(root, "hurt-flash.sound-slot", "hurt", null), integer(root, "hurt-flash.playback-ticks", 20, 1, 100)),
                new Ghost(range(root, "victim-ghost"), integer(root, "victim-ghost.duration-ticks", 40, 1, 100)),
                new Toast(icon(root),
                        integer(root, "advancement-toast.duration-ticks", 60, 1, 100)),
                new Bar(choice(root, "boss-bar.colour", "purple", Set.of("pink", "blue", "red", "green", "yellow", "purple", "white")),
                        choice(root, "boss-bar.style", "progress", Set.of("progress", "notched_6", "notched_10", "notched_12", "notched_20")),
                        progress(root), integer(root, "boss-bar.duration-ticks", 60, 1, 100)),
                number(root, "false-death.range-blocks", 16, true), integer(root, "private-chat.max-visible-length", 160, 1, 160),
                new DurationScale(number(root, "episodes.duration-scale.low", 1, false),
                        number(root, "episodes.duration-scale.medium", 1, false),
                        number(root, "episodes.duration-scale.high", 1.5, false),
                        number(root, "episodes.duration-scale.extreme", 2, false)),
                new Phantom(phantomMobs(root), number(root, "silverfish.distance-blocks", 8, true),
                        integer(root, "silverfish.duration-ticks", 20, 1, 100)), HorrorConfig.load(root));
    }

    private static java.util.List<String> phantomMobs(ConfigurationSection root) {
        String path = "effects.silverfish.mobs";
        Object raw = root.get(path);
        java.util.List<?> values = raw == null ? java.util.List.of("silverfish", "zombie", "skeleton", "spider", "creeper", "enderman")
                : raw instanceof java.util.List<?> list ? list : java.util.List.of();
        if (values.isEmpty()) fail("silverfish.mobs", "Must be a nonempty list of hostile living mob keys");
        // Enum metadata is safe without a server; Registry.ENTITY_TYPE is touched only by the renderer.
        Set<String> hostileKeys = java.util.Arrays.stream(org.bukkit.entity.EntityType.values())
                .filter(type -> type.getEntityClass() != null
                        && org.bukkit.entity.Enemy.class.isAssignableFrom(type.getEntityClass()))
                .map(type -> type.getKey().toString()).collect(java.util.stream.Collectors.toSet());
        java.util.List<String> result = new java.util.ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String)) fail("silverfish.mobs", "Every entry must be a hostile living mob key");
            String key = (String) value;
            if (!key.contains(":")) key = "minecraft:" + key;
            if (!hostileKeys.contains(key)) fail("silverfish.mobs", "Unknown or non-hostile living mob: " + value);
            result.add(key);
        }
        return java.util.List.copyOf(result);
    }

    private static Block block(ConfigurationSection root, String id, String fallback) {
        String data = choice(root, id + ".block-data", fallback, null);
        boolean valid = id.equals("sign") ? validSignData(data)
                : !com.dasannn.socialblueprint.feature.effects.BlockEquivalence.cubeClass(data).isEmpty();
        if (!valid) fail(id + ".block-data", "Must be in the cosmetic equivalence allow-list");
        return new Block(data, range(root, id), integer(root, id + ".duration-ticks", 40, 1, 100));
    }

    private static boolean validSignData(String data) {
        if (!com.dasannn.socialblueprint.feature.effects.BlockEquivalence.knownSign(data)) return false;
        // Only non-hanging signs; fixed orientation and dry state. Renderer compares canonical data exactly.
        if (data.matches("(?:minecraft:)?[a-z_]+_wall_sign\\[facing=(?:north|south|east|west),waterlogged=false\\]")) return true;
        return !com.dasannn.socialblueprint.feature.effects.BlockEquivalence.material(data).endsWith("_wall_sign")
                && data.matches("(?:minecraft:)?[a-z_]+_sign\\[rotation=(?:[0-9]|1[0-5]),waterlogged=false\\]");
    }

    private static double range(ConfigurationSection root, String id) {
        return number(root, id + ".range-blocks", id.equals("victim-ghost") ? 8 : 6, true);
    }

    private static String icon(ConfigurationSection root) {
        String icon = choice(root, "advancement-toast.icon", "minecraft:paper", null);
        if (!icon.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) fail("advancement-toast.icon", "Expected a namespaced visual icon key");
        return icon;
    }

    private static double progress(ConfigurationSection root) {
        double value = number(root, "boss-bar.progress", 0.5, false);
        if (value < 0 || value > 1) fail("boss-bar.progress", "Must be in [0, 1]");
        return value;
    }

    public static PresentationConfig defaults() {
        var root = new org.bukkit.configuration.MemoryConfiguration();
        root.createSection("effects.episodes");
        return load(root);
    }
    private static long ticks(ConfigurationSection root, String key, long fallback) {
        Object raw = root.get("effects." + key);
        if (raw != null && !(raw instanceof Integer) && !(raw instanceof Long)) fail(key, "Must be integer ticks");
        long value = raw == null ? fallback : ((Number) raw).longValue();
        if (value < 1 || value > (Long.MAX_VALUE - 20_000L) / 150L) fail(key, "Must be positive ticks within cadence bounds");
        return value;
    }
    private static int integer(ConfigurationSection root, String key, int fallback, int min, int max) {
        String path = "effects." + key;
        if (root.contains(path) && !root.isInt(path)) fail(key, "Must be an integer");
        int value = root.getInt(path, fallback);
        if (value < min || value > max) fail(key, "Out of bounds: " + min + " to " + max);
        return value;
    }
    private static double number(ConfigurationSection root, String key, double fallback, boolean positive) {
        String path = "effects." + key;
        if (root.contains(path) && !(root.get(path) instanceof Number)) fail(key, "Must be numeric");
        double value = root.getDouble(path, fallback);
        if (!Double.isFinite(value) || positive && value <= 0) fail(key, "Must be finite" + (positive ? " and positive" : ""));
        return value;
    }
    private static String choice(ConfigurationSection root, String key, String fallback, Set<String> options) {
        String path = "effects." + key;
        if (root.contains(path) && !root.isString(path)) fail(key, "Must be text");
        String value = root.getString(path, fallback).toLowerCase(Locale.ROOT);
        if (value.isBlank() || options != null && !options.contains(value)) fail(key, "Invalid value");
        return value;
    }
    private static void fail(String key, String reason) { throw new ConfigValidationException("effects." + key, reason); }
}
