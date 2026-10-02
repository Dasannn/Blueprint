package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanguageFilesKeyDivergenceTest {

    @Test
    @DisplayName("T-032c / T-032a: messages_es.yml and messages_en.yml have identical key sets")
    void languageFilesHaveIdenticalKeySets() {
        InputStream esStream = getClass().getClassLoader().getResourceAsStream("messages_es.yml");
        InputStream enStream = getClass().getClassLoader().getResourceAsStream("messages_en.yml");

        assertThat(esStream).isNotNull();
        assertThat(enStream).isNotNull();

        YamlConfiguration esYaml = YamlConfiguration.loadConfiguration(new InputStreamReader(esStream, StandardCharsets.UTF_8));
        YamlConfiguration enYaml = YamlConfiguration.loadConfiguration(new InputStreamReader(enStream, StandardCharsets.UTF_8));

        Map<String, String> esMap = MessageRegistry.flattenKeys(esYaml);
        Map<String, String> enMap = MessageRegistry.flattenKeys(enYaml);

        assertThat(esMap.values()).allSatisfy(text -> assertThat(text).doesNotContainIgnoringCase("Killing"));
        assertThat(enMap.values()).allSatisfy(text -> assertThat(text).doesNotContainIgnoringCase("Killing"));

        Set<String> esKeys = new TreeSet<>(esMap.keySet());
        Set<String> enKeys = new TreeSet<>(enMap.keySet());

        Set<String> inEsOnly = new TreeSet<>(esKeys);
        inEsOnly.removeAll(enKeys);

        Set<String> inEnOnly = new TreeSet<>(enKeys);
        inEnOnly.removeAll(esKeys);

        assertThat(inEsOnly)
                .withFailMessage("Keys present in messages_es.yml but missing in messages_en.yml: %s", inEsOnly)
                .isEmpty();

        assertThat(inEnOnly)
                .withFailMessage("Keys present in messages_en.yml but missing in messages_es.yml: %s", inEnOnly)
                .isEmpty();

        assertThat(esKeys).containsExactlyInAnyOrderElementsOf(enKeys);

        // Assert that every shipped key in Spanish has a non-blank value (finding 10)
        for (Map.Entry<String, String> entry : esMap.entrySet()) {
            // An empty list is a deliberate "nothing" (SB-115 custom lines), not a missing translation.
            if (esYaml.isList(entry.getKey()) && esYaml.getList(entry.getKey()).isEmpty()) continue;
            assertThat(entry.getValue())
                    .withFailMessage("Key '%s' in messages_es.yml has a blank value", entry.getKey())
                    .isNotBlank();
        }

        // Assert that every shipped key in English has a non-blank value (finding 10)
        for (Map.Entry<String, String> entry : enMap.entrySet()) {
            // An empty list is a deliberate "nothing" (SB-115 custom lines), not a missing translation.
            if (enYaml.isList(entry.getKey()) && enYaml.getList(entry.getKey()).isEmpty()) continue;
            assertThat(entry.getValue())
                    .withFailMessage("Key '%s' in messages_en.yml has a blank value", entry.getKey())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("T-032c: Test mechanism fails when two simulated key sets diverge")
    void failsWhenKeySetsDiverge() {
        Set<String> setA = Set.of("tiers.tier-4", "commands.help");
        Set<String> setB = Set.of("tiers.tier-4", "commands.unknown");

        assertThatThrownBy(() -> assertThat(setA).containsExactlyInAnyOrderElementsOf(setB))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("T-032c / Finding 10: Test mechanism fails when a key has an empty or blank value")
    void failsWhenAKeyHasBlankValue() {
        Map<String, String> mapWithBlank = Map.of(
                "key1", "Valid value",
                "key2", "   "
        );

        assertThatThrownBy(() -> {
            for (Map.Entry<String, String> entry : mapWithBlank.entrySet()) {
                assertThat(entry.getValue())
                        .withFailMessage("Key '%s' has a blank value", entry.getKey())
                        .isNotBlank();
            }
        }).isInstanceOf(AssertionError.class).hasMessageContaining("key2");
    }
}
