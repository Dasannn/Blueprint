package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryConfigTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-03T01:30:00Z");

    @Test
    void defaultPatternUsesCalendarDateInSuppliedZone() {
        HistoryConfig config = HistoryConfig.load(new YamlConfiguration());
        assertThat(config.dateFormat()).isEqualTo("MM/dd/yyyy");
        assertThat(config.formatDate(CREATED_AT, ZoneId.of("America/Bogota"))).isEqualTo("10/02/2026");
        assertThat(config.formatDate(CREATED_AT, ZoneOffset.UTC)).isEqualTo("10/03/2026");
    }

    @Test
    void customPatternIsLoadedAndFormatted() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("history.date-format", "dd.MM.yyyy");
        assertThat(HistoryConfig.load(yaml).formatDate(CREATED_AT, ZoneOffset.UTC)).isEqualTo("03.10.2026");
    }

    @Test
    void invalidPatternsAndTypesNameTheKey() {
        for (Object pattern : new Object[]{"invalid", "yyyy-MM-dd'", 123}) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("history.date-format", pattern);
            assertThatThrownBy(() -> HistoryConfig.load(yaml))
                    .isInstanceOf(ConfigValidationException.class)
                    .hasMessageContaining("history.date-format");
        }
    }
}
