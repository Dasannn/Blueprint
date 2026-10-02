package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SkyConfigTest {
    @TempDir Path folder;

    private YamlConfiguration defaults() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8));
    }

    private ConfigManager manager(Path directory) {
        return new ConfigManager(directory.resolve("config.yml").toFile(),
                new MessageRegistry(directory.toFile(), "en", null), Runnable::run, null);
    }

    @Test void shippedAndMissingModeDefaultToEscalatingAndAllFourModesValidate() {
        var yaml = defaults();
        assertThat(PresentationConfig.load(yaml).sky().mode()).isEqualTo("escalating");
        yaml.set("effects.sky.mode", null);
        assertThat(PresentationConfig.load(yaml).sky().mode()).isEqualTo("escalating");
        for (String mode : List.of("night", "storm", "both", "escalating")) {
            yaml.set("effects.sky.mode", mode);
            assertThat(PresentationConfig.load(yaml).sky().mode()).isEqualTo(mode);
        }
        for (Object bad : List.of("day", "", "night,storm", 1, true, List.of("night", "storm"))) {
            yaml.set("effects.sky.mode", bad);
            assertThatThrownBy(() -> PresentationConfig.load(yaml)).hasMessageContaining("effects.sky.mode");
        }
    }

    @Test void firstUpgradeAdoptsExactlyNightThenKeepsLaterEditsAcrossReloadAndRestart() throws Exception {
        for (String mode : List.of("night", "storm", "both", "escalating", "NIGHT")) {
            Path directory = Files.createDirectory(folder.resolve("case-" + mode + "-" + (mode.equals("NIGHT") ? "upper" : "lower")));
            var yaml = defaults();
            yaml.set("effects.sky.mode", mode);
            yaml.set("effects.sky.duration-ticks", 80);
            yaml.save(directory.resolve("config.yml").toFile());
            var manager = manager(directory);
            manager.initialize();
            String expected = mode.equals("night") ? "escalating" : mode.toLowerCase(java.util.Locale.ROOT);
            assertThat(manager.config().effects().presentation().sky().mode()).isEqualTo(expected);
            assertThat(YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile())
                    .getString("effects.sky.mode")).isEqualTo(mode.equals("night") ? "escalating" : mode);
            assertThat(manager.config().effects().presentation().sky().durationTicks()).isEqualTo(80);
            assertThat(directory.resolve("sky-defaults-v1.flag")).exists();
            String upgraded = Files.readString(directory.resolve("config.yml"));
            manager.reload();
            assertThat(Files.readString(directory.resolve("config.yml"))).isEqualTo(upgraded);
            for (String choice : List.of("both", "storm", "escalating", "night")) {
                manager.set("effects.sky.mode", choice);
                manager.reload();
                var restarted = manager(directory);
                restarted.initialize();
                assertThat(restarted.config().effects().presentation().sky().mode()).isEqualTo(choice);
            }
            var snapshot = manager.snapshot();
            String disk = Files.readString(directory.resolve("config.yml"));
            assertThatThrownBy(() -> manager.set("effects.sky.mode", "day")).hasMessageContaining("effects.sky.mode");
            assertThat(manager.snapshot()).isSameAs(snapshot);
            assertThat(Files.readString(directory.resolve("config.yml"))).isEqualTo(disk);
        }
    }

    @Test void freshInstallRecordsAdoptionAndPreservesAnExplicitNightOnRestart() {
        var manager = manager(folder);
        manager.initialize();
        assertThat(manager.config().effects().presentation().sky().mode()).isEqualTo("escalating");
        manager.set("effects.sky.mode", "night");
        var restarted = manager(folder);
        restarted.initialize();
        assertThat(restarted.config().effects().presentation().sky().mode()).isEqualTo("night");
    }
}
