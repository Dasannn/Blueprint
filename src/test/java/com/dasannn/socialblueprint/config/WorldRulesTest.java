package com.dasannn.socialblueprint.config;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorldRulesTest {
    @Test void exactNamesAndEmptyList() {
        var rules = new WorldRules(List.of("minigames", "world with spaces"));
        assertThat(rules.isDisabled("minigames")).isTrue();
        assertThat(rules.isDisabled("Minigames")).isFalse();
        assertThat(rules.isDisabled(" minigames")).isFalse();
        assertThat(rules.isDisabled("world with spaces")).isTrue();
        assertThat(rules.isDisabled(null)).isFalse();
        assertThat(new WorldRules(List.of()).isDisabled("minigames")).isFalse();
    }
    @Test void yamlRejectsScalarAndNonStringList() {
        var yaml = new YamlConfiguration();
        assertThat(WorldRules.load(yaml).isDisabled("minigames")).isTrue();
        yaml.set("disabled-worlds", "minigames");
        assertThatThrownBy(() -> WorldRules.load(yaml)).hasMessageContaining("disabled-worlds");
        yaml.set("disabled-worlds", List.of("minigames", 1));
        assertThatThrownBy(() -> WorldRules.load(yaml)).hasMessageContaining("disabled-worlds");
        yaml.set("disabled-worlds", List.of());
        assertThat(WorldRules.load(yaml).disabledWorlds()).isEmpty();
    }
}
