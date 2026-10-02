package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceFilteringTest {

    @Test
    void onlyPluginDescriptorIsFilteredAndUsesProjectVersion() throws Exception {
        var pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var xpath = XPathFactory.newInstance().newXPath();
        String resources = "/project/build/resources/resource";
        assertThat(xpath.evaluate("count(" + resources + ")", pom)).isEqualTo("2");
        assertThat(xpath.evaluate(resources + "[filtering='true']/directory", pom)).isEqualTo("src/main/resources");
        assertThat(xpath.evaluate(resources + "[filtering='true']/includes/include", pom)).isEqualTo("plugin.yml");
        assertThat(xpath.evaluate("count(" + resources + "[filtering='true']/includes/include)", pom)).isEqualTo("1");
        assertThat(xpath.evaluate(resources + "[filtering='false']/directory", pom)).isEqualTo("src/main/resources");
        assertThat(xpath.evaluate(resources + "[filtering='false']/excludes/exclude", pom)).isEqualTo("plugin.yml");

        assertThat(Files.readString(Path.of("src/main/resources/plugin.yml")))
                .contains("version: '${project.version}'");
        try (var in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertThat(in).isNotNull();
            var descriptor = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertThat(descriptor.getString("version")).isEqualTo(xpath.evaluate("/project/version", pom));
        }
        for (String resource : new String[]{"config.yml", "messages_es.yml", "messages_en.yml"}) {
            try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertThat(in).isNotNull();
                assertThat(in.readAllBytes()).isEqualTo(Files.readAllBytes(Path.of("src/main/resources", resource)));
            }
        }
    }
}
