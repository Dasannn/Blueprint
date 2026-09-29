package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoHardcodedPlayerStringsTest {

    // Regex to detect calls sending literal strings directly to players/senders
    private static final Pattern LITERAL_SEND_MESSAGE_PATTERN = Pattern.compile(
            "\\b(?:sender|player|audience|target)\\.sendMessage\\s*\\(\\s*\"[^\"]+\"\\s*\\)"
    );

    private static final Pattern COMPONENT_LITERAL_SEND_PATTERN = Pattern.compile(
            "\\b(?:sender|player|audience|target)\\.sendMessage\\s*\\(\\s*Component\\.text\\s*\\(\\s*\"[^\"]+\"\\s*\\)\\s*\\)"
    );

    private static final Pattern LITERAL_ACTION_BAR_PATTERN = Pattern.compile(
            "\\b(?:sender|player|audience|target)\\.sendActionBar\\s*\\(\\s*\"[^\"]+\"\\s*\\)"
    );

    @Test
    @DisplayName("T-032c / DoD 5: No player-visible string is hardcoded in Java source files")
    void noPlayerVisibleStringsHardcodedInJava() throws Exception {
        Path srcRoot = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(srcRoot)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();

            for (Path javaFile : javaFiles) {
                String content = Files.readString(javaFile, StandardCharsets.UTF_8);

                // Check for direct literal sendMessage calls
                Matcher m1 = LITERAL_SEND_MESSAGE_PATTERN.matcher(content);
                while (m1.find()) {
                    violations.add(javaFile + ": " + m1.group());
                }

                Matcher m2 = COMPONENT_LITERAL_SEND_PATTERN.matcher(content);
                while (m2.find()) {
                    violations.add(javaFile + ": " + m2.group());
                }

                Matcher m3 = LITERAL_ACTION_BAR_PATTERN.matcher(content);
                while (m3.find()) {
                    violations.add(javaFile + ": " + m3.group());
                }
            }
        }

        assertThat(violations)
                .withFailMessage("Hardcoded player-visible strings found in Java source:\n%s", String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("T-032c: All message keys referenced in Java code exist in message files")
    void allMessageKeysReferencedInJavaExistInFiles() throws Exception {
        InputStream enStream = getClass().getClassLoader().getResourceAsStream("messages_en.yml");
        assertThat(enStream).isNotNull();
        YamlConfiguration enYaml = YamlConfiguration.loadConfiguration(new InputStreamReader(enStream, StandardCharsets.UTF_8));
        Map<String, String> keys = MessageRegistry.flattenKeys(enYaml);
        Set<String> validKeys = keys.keySet();

        // Pattern finding render calls: render("key") or renderWithPrefix("key")
        Pattern renderPattern = Pattern.compile("render(?:WithPrefix)?\\s*\\(\\s*\"([^\"]+)\"");

        Path srcRoot = Path.of("src", "main", "java");
        List<String> missingKeys = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(srcRoot)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path javaFile : javaFiles) {
                String content = Files.readString(javaFile, StandardCharsets.UTF_8);
                Matcher m = renderPattern.matcher(content);
                while (m.find()) {
                    String referencedKey = m.group(1);
                    if (!validKeys.contains(referencedKey)) {
                        missingKeys.add(javaFile.getFileName() + " references unknown key: '" + referencedKey + "'");
                    }
                }
            }
        }

        assertThat(missingKeys)
                .withFailMessage("Java source references message keys not defined in language files:\n%s", String.join("\n", missingKeys))
                .isEmpty();
    }

    @Test
    @DisplayName("T-032c: Test mechanism fails when a hardcoded string is present in source code")
    void scannerDetectsHardcodedStrings() {
        String testSnippet = "sender.sendMessage(\"Hardcoded message to player\");";
        Matcher matcher = LITERAL_SEND_MESSAGE_PATTERN.matcher(testSnippet);
        assertThat(matcher.find()).isTrue();
    }
}
