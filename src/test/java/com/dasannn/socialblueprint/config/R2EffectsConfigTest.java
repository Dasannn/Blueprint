package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.ChatCorruption;
import com.dasannn.socialblueprint.domain.ChatCorruptionConfig;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import com.dasannn.socialblueprint.feature.effects.AmbientEffectType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

class R2EffectsConfigTest {
    @TempDir Path folder;
    private static final List<PsychosisLevel> LEVELS = List.of(PsychosisLevel.LOW, PsychosisLevel.MEDIUM,
            PsychosisLevel.HIGH, PsychosisLevel.EXTREME);
    private static final List<String> LIST_KEYS = List.of("effects.particles.types",
            "effects.serenity.particles.types", "effects.serenity.apparition.kinds");
    private YamlConfiguration resource(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream(name), StandardCharsets.UTF_8));
    }
    private ConfigManager manager() {
        return new ConfigManager(folder.resolve("config.yml").toFile(),
                new MessageRegistry(folder.toFile(), "en", null), Runnable::run, null);
    }

    @Test void lowFloorsAndFourLevelDefaultsKeepChatAtMedium() {
        var config = EffectsConfigSection.load(resource("config.yml"));
        assertThat(PsychosisLevel.LOW.hasMadnessEffects()).isTrue();
        assertThat(EffectsConfigSection.defaults().quietInterval(PsychosisLevel.LOW)).isEqualTo(java.time.Duration.ofMinutes(4));
        for (var type : List.of(AmbientEffectType.PARTICLES, AmbientEffectType.SCREEN_FLASH,
                AmbientEffectType.SOURCE_LESS_SOUNDS, AmbientEffectType.FAKE_ANNOUNCEMENT,
                AmbientEffectType.BOSS_BAR, AmbientEffectType.WHISPER)) {
            assertThat(type.floor()).isEqualTo(PsychosisLevel.LOW);
            assertThat(config.presentation().rules().get(type).minimumLevel()).isEqualTo(PsychosisLevel.LOW);
            assertThat(config.presentation().rules().get(type).allows(PsychosisLevel.LOW)).isTrue();
            assertThat(config.presentation().rules().get(type).allows(PsychosisLevel.NEUTRAL)).isFalse();
        }
        assertThat(AmbientEffectType.ADVANCEMENT_TOAST.floor()).isEqualTo(PsychosisLevel.MEDIUM);
        assertThat(AmbientEffectType.CREEPER_SOUND.floor()).isEqualTo(PsychosisLevel.MEDIUM);
        assertThat(AmbientEffectType.FALSE_DEATH.floor()).isEqualTo(PsychosisLevel.HIGH);
        for (int i = 0; i < LEVELS.size(); i++) {
            var level = LEVELS.get(i);
            assertThat(config.presentation().episodes().intervalTicks(level)).isEqualTo(new long[]{4800, 2400, 1200, 400}[i]);
            assertThat(config.presentation().maxConcurrent(level)).isEqualTo(i + 1);
            assertThat(config.presentation().durationScale().ticks(20, level)).isEqualTo(new int[]{20, 20, 30, 40}[i]);
            assertThat(config.quietInterval(level)).isGreaterThan(java.time.Duration.ZERO);
            if (i > 0) assertThat(config.quietInterval(LEVELS.get(i - 1))).isGreaterThan(config.quietInterval(level));
        }
        for (int i = 0; i < 100; i++)
            assertThat(ChatCorruption.isEpisode("long enough for a chat episode", PsychosisLevel.LOW,
                    1, i, ChatCorruptionConfig.DEFAULT)).isFalse();
    }

    @Test void orderingAndConcurrencyRejectInvalidValuesAtTheirKeys() {
        for (var entry : Map.<String, List<?>>of(
                "effects.episodes.low.interval-ticks", List.of(0, 2400, "bad"),
                "effects.episodes.duration-scale.low", List.of(0.5, 1.1, Double.NaN, "bad"),
                "effects.episodes.low.max-concurrent", List.of(0, -1, 2, 1.5, "bad"),
                "effects.episodes.medium.max-concurrent", List.of(0, 3),
                "effects.episodes.high.max-concurrent", List.of(1, 4),
                "effects.episodes.extreme.max-concurrent", List.of(2, 5)).entrySet()) {
            for (Object bad : entry.getValue()) {
                var yaml = resource("config.yml"); yaml.set(entry.getKey(), bad);
                assertThatThrownBy(() -> EffectsConfigSection.load(yaml)).isInstanceOf(ConfigValidationException.class)
                        .hasMessageContaining(entry.getKey().contains("interval") ? "effects.episodes" :
                                entry.getKey().endsWith(".low") && bad.equals(1.1) ? "effects.episodes.duration-scale.medium" : entry.getKey());
            }
        }
    }

    @Test void particleAndAnimalDecisionsRejectBadEntriesWithoutRegistryAccess() {
        for (String key : LIST_KEYS) {
            for (Object bad : List.of("smoke", List.of(), List.of(1), List.of("unknown"),
                    List.of("end_rod", "block"))) {
                var yaml = resource("config.yml"); yaml.set(key, bad);
                assertThatThrownBy(() -> EffectsConfigSection.load(yaml)).hasMessageContaining(key);
            }
        }
        for (String key : LIST_KEYS.subList(0, 2)) {
            for (String bad : List.of("dust", "block", "item", "trail", "vibration", "ash", "white_ash", "unknown")) {
                var yaml = resource("config.yml"); yaml.set(key, List.of("smoke", bad));
                assertThatThrownBy(() -> EffectsConfigSection.load(yaml)).hasMessageContaining(key + "[1]").hasMessageContaining(bad);
            }
        }
        var yaml = resource("config.yml");
        for (String animal : List.of("turtle", "fox", "armadillo", "bee", "cat", "wolf")) {
            yaml.set("effects.serenity.apparition.kinds", List.of(animal));
            assertThat(EffectsConfigSection.load(yaml).serenity().animals()).containsExactly(animal);
        }
        for (String animal : List.of("zombie", "armor_stand", "item", "player")) {
            yaml.set("effects.serenity.apparition.kinds", List.of("cat", animal));
            assertThatThrownBy(() -> EffectsConfigSection.load(yaml)).hasMessageContaining("apparition.kinds[1]").hasMessageContaining(animal);
        }
        var particles = EffectsConfigSection.load(resource("config.yml")).presentation().particles();
        var random = new Random(42);
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < 100; i++) {
            var chosen = particles.choose(random);
            assertThat(chosen.types()).hasSize(1);
            seen.add(chosen.types().getFirst());
            assertThat(chosen.totalTicks()).isEqualTo(particles.totalTicks());
            assertThat(chosen.emissionDelay(chosen.count() - 1) + PresentationConfig.PARTICLE_TAILS.get(chosen.types().getFirst()))
                    .isLessThanOrEqualTo(chosen.totalTicks());
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(particles.types());
    }

    @Test void falseDeathListsRequireSubjectAndValidateSubstitutionBoundsAndActions() {
        String key = "effects.false-death.lines";
        for (Object bad : List.of("{player}", List.of(), List.of(1), List.of("missing-subject"),
                List.of("{player}\nsecond"), List.of("{player}<click:run_command:'/op'>"),
                List.of("{player}" + "x".repeat(145))))
            assertThatThrownBy(() -> CatalogueLines.validateList(key, bad, 160)).hasMessageContaining(key);
        for (String language : List.of("en", "es")) {
            var messages = resource("messages_" + language + ".yml");
            assertThat(CatalogueLines.validateList(key, messages.get(key), 160)).hasSizeGreaterThan(1);
            assertThat(messages.contains("effects.false-death.line")).isFalse();
            var registry = new MessageRegistry(folder.toFile(), language, null);
            assertThat(registry.snapshot().messages().lineKeys(key)).contains(key + ".0", key + ".1");
        }
    }

    @Test void upgradeAdoptsEverySingleValueAndPreservesOwnerFloorsAndCadence() throws Exception {
        var yaml = resource("config.yml");
        yaml.set("effects.episodes.low", null);
        yaml.set("effects.episodes.duration-scale.low", null);
        yaml.set("effects.episodes.medium.interval-ticks", 7200);
        yaml.set("effects.particles.minimum-level", "medium");
        for (String level : List.of("medium", "high", "extreme")) yaml.set("effects.episodes." + level + ".max-concurrent", null);
        yaml.set(LIST_KEYS.get(0), null); yaml.set("effects.particles.type", "end_rod");
        yaml.set(LIST_KEYS.get(1), null); yaml.set("effects.serenity.particles.type", "smoke");
        yaml.set(LIST_KEYS.get(2), null); yaml.set("effects.serenity.apparition.kind", "wolf");
        yaml.save(folder.resolve("config.yml").toFile());
        for (String language : List.of("en", "es")) {
            var text = resource("messages_" + language + ".yml");
            text.set("effects.false-death.lines", null);
            text.set("effects.false-death.line", "&7{player}");
            text.save(folder.resolve("messages_" + language + ".yml").toFile());
        }
        var manager = manager(); manager.initialize();
        var effects = manager.config().effects();
        assertThat(effects.presentation().particles().types()).containsExactly("end_rod");
        assertThat(effects.serenity().particles().types()).containsExactly("smoke");
        assertThat(effects.serenity().animals()).containsExactly("wolf");
        assertThat(effects.presentation().rules().get(AmbientEffectType.PARTICLES).minimumLevel()).isEqualTo(PsychosisLevel.MEDIUM);
        assertThat(effects.presentation().episodes().mediumTicks()).isEqualTo(7200);
        assertThat(effects.presentation().episodes().lowTicks()).isGreaterThan(7200);
        for (String old : List.of("effects.particles.type", "effects.serenity.particles.type", "effects.serenity.apparition.kind")) {
            assertThat(YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile()).contains(old)).isFalse();
            assertThat(manager.isEditableKey(old)).isFalse();
        }
        for (String language : List.of("en", "es")) {
            var text = YamlConfiguration.loadConfiguration(folder.resolve("messages_" + language + ".yml").toFile());
            assertThat(text.getStringList("effects.false-death.lines").getFirst()).isEqualTo("&7{player}");
            assertThat(text.contains("effects.false-death.line")).isFalse();
        }
        String before = Files.readString(folder.resolve("config.yml"));
        manager.reload();
        assertThat(Files.readString(folder.resolve("config.yml"))).isEqualTo(before);
    }

    @Test void untouchedLegacyCatAdoptsNewDefaultsAndDawn() throws Exception {
        var yaml = resource("config.yml");
        yaml.set(LIST_KEYS.get(2), null);
        yaml.set("effects.serenity.apparition.kind", "cat");
        yaml.set("effects.serenity.dawn.duration-ticks", 60);
        yaml.save(folder.resolve("config.yml").toFile());
        var manager = manager(); manager.initialize();
        assertThat(manager.config().effects().serenity().animals()).containsExactly("turtle", "fox", "armadillo", "bee");
        assertThat(manager.config().effects().serenity().dawnDuration()).isEqualTo(200);
        assertThat(YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile())
                .contains("effects.serenity.apparition.kind")).isFalse();
        assertThat(folder.resolve("serenity-defaults-v1.flag")).exists();
        String upgraded = Files.readString(folder.resolve("config.yml"));
        manager.reload();
        assertThat(Files.readString(folder.resolve("config.yml"))).isEqualTo(upgraded);
    }

    @Test void alreadyUpgradedCatIsRepairedOnceThenOwnerEditsSurviveRestart() throws Exception {
        var yaml = resource("config.yml");
        yaml.set(LIST_KEYS.get(2), List.of("cat"));
        yaml.set("effects.serenity.dawn.duration-ticks", 60);
        yaml.save(folder.resolve("config.yml").toFile());
        var manager = manager(); manager.initialize();
        assertThat(manager.config().effects().serenity().animals()).containsExactly("turtle", "fox", "armadillo", "bee");
        assertThat(manager.config().effects().serenity().dawnDuration()).isEqualTo(200);
        manager.set(LIST_KEYS.get(2), "[cat]");
        manager.set("effects.serenity.dawn.duration-ticks", "60");
        var restarted = manager(); restarted.initialize();
        assertThat(restarted.config().effects().serenity().animals()).containsExactly("cat");
        assertThat(restarted.config().effects().serenity().dawnDuration()).isEqualTo(60);
    }

    @Test void customLegacyKindAndDawnAreKept() throws Exception {
        var yaml = resource("config.yml");
        yaml.set(LIST_KEYS.get(2), null);
        yaml.set("effects.serenity.apparition.kind", "wolf");
        yaml.set("effects.serenity.dawn.duration-ticks", 80);
        yaml.save(folder.resolve("config.yml").toFile());
        var manager = manager(); manager.initialize();
        assertThat(manager.config().effects().serenity().animals()).containsExactly("wolf");
        assertThat(manager.config().effects().serenity().dawnDuration()).isEqualTo(80);
    }

    @Test void adoptionMarkerProtectsExplicitCatAndSixtyTickDawn() throws Exception {
        var yaml = resource("config.yml");
        yaml.set(LIST_KEYS.get(2), List.of("cat"));
        yaml.set("effects.serenity.dawn.duration-ticks", 60);
        yaml.save(folder.resolve("config.yml").toFile());
        Files.writeString(folder.resolve("serenity-defaults-v1.flag"), "owner retained settings");
        var manager = manager(); manager.initialize();
        assertThat(manager.config().effects().serenity().animals()).containsExactly("cat");
        assertThat(manager.config().effects().serenity().dawnDuration()).isEqualTo(60);
    }

    @Test void upgradeRetiresScalarsWithoutReplacingAnExplicitOwnerList() throws Exception {
        var yaml = resource("config.yml");
        yaml.set(LIST_KEYS.get(0), List.of("end_rod")); yaml.set("effects.particles.type", "smoke");
        yaml.set(LIST_KEYS.get(1), List.of("smoke")); yaml.set("effects.serenity.particles.type", "end_rod");
        yaml.set(LIST_KEYS.get(2), List.of("cat", "bee")); yaml.set("effects.serenity.apparition.kind", "wolf");
        yaml.save(folder.resolve("config.yml").toFile());
        for (String language : List.of("en", "es")) {
            var text = resource("messages_" + language + ".yml");
            text.set("effects.false-death.lines", List.of("&8{player}"));
            text.set("effects.false-death.line", "&7{player}");
            text.save(folder.resolve("messages_" + language + ".yml").toFile());
        }
        var manager = manager(); manager.initialize();
        assertThat(manager.config().effects().presentation().particles().types()).containsExactly("end_rod");
        assertThat(manager.config().effects().serenity().particles().types()).containsExactly("smoke");
        assertThat(manager.config().effects().serenity().animals()).containsExactly("cat", "bee");
        for (String language : List.of("en", "es")) {
            var text = YamlConfiguration.loadConfiguration(folder.resolve("messages_" + language + ".yml").toFile());
            assertThat(text.getStringList("effects.false-death.lines")).containsExactly("&8{player}");
            assertThat(text.contains("effects.false-death.line")).isFalse();
        }
    }

    @Test void liveListsAndLimitsRoundTripAndInvalidEditsAreAtomic() throws Exception {
        var manager = manager(); manager.initialize();
        manager.set(LIST_KEYS.get(0), "[end_rod, smoke]");
        manager.set(LIST_KEYS.get(1), "[smoke, end_rod]");
        manager.set(LIST_KEYS.get(2), "[bee, cat, wolf]");
        manager.set("effects.private-chat.max-visible-length", "80");
        manager.set("effects.false-death.lines", "['&7{player}" + "x".repeat(100) + "', '&8{player}']");
        assertThatThrownBy(() -> manager.set("effects.episodes.extreme.max-concurrent", "5"))
                .hasMessageContaining("effects.episodes.extreme.max-concurrent");
        assertThatThrownBy(() -> manager.set("effects.episodes.high.max-concurrent", "4"))
                .hasMessageContaining("effects.episodes.high.max-concurrent");
        assertThatThrownBy(() -> manager.set("effects.episodes.medium.max-concurrent", "3"))
                .hasMessageContaining("effects.episodes.medium.max-concurrent");
        assertThatThrownBy(() -> manager.set("effects.episodes.low.max-concurrent", "2"))
                .hasMessageContaining("effects.episodes.low.max-concurrent");
        for (String key : LIST_KEYS) assertThat(manager.get(key)).isNotNull();
        var before = manager.snapshot(); String disk = Files.readString(folder.resolve("config.yml"));
        assertThatThrownBy(() -> manager.set(LIST_KEYS.get(0), "[smoke, dust]")).hasMessageContaining(LIST_KEYS.get(0) + "[1]");
        for (String bad : List.of("[smoke, '']", "[smoke, 1]", "[]", "smoke"))
            assertThatThrownBy(() -> manager.set(LIST_KEYS.get(0), bad)).hasMessageContaining(LIST_KEYS.get(0));
        assertThatThrownBy(() -> manager.set("effects.episodes.high.max-concurrent", "1")).hasMessageContaining("effects.episodes.high.max-concurrent");
        assertThat(manager.snapshot()).isSameAs(before);
        assertThat(Files.readString(folder.resolve("config.yml"))).isEqualTo(disk);
        manager.reload();
        assertThat(manager.config().effects().serenity().animals()).containsExactly("bee", "cat", "wolf");
        assertThat(manager.config().effects().presentation().maxConcurrent(PsychosisLevel.LOW)).isEqualTo(1);
        assertThat(manager.snapshot().messages().lineKeys("effects.false-death.lines")).hasSize(2);
    }
}
