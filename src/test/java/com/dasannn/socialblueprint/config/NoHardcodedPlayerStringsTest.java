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

    // 1. Literal strings passed directly to sendMessage or sendActionBar on any receiver
    private static final Pattern DIRECT_LITERAL_MESSAGE_PATTERN = Pattern.compile(
            "\\b[a-zA-Z0-9_]+\\.(?:sendMessage|sendActionBar)\\s*\\(\\s*\"[^\"]+\"\\s*\\)"
    );

    // 2. Component.text("literal") passed inside sendMessage or sendActionBar on any receiver (including chaining)
    private static final Pattern COMPONENT_TEXT_MESSAGE_PATTERN = Pattern.compile(
            "\\b[a-zA-Z0-9_]+\\.(?:sendMessage|sendActionBar)\\s*\\([^;]*Component\\.text\\s*\\(\\s*\"[^\"]+\""
    );

    // 3. Title display calls with literal strings or Component.text("literal") on any receiver
    private static final Pattern TITLE_LITERAL_PATTERN = Pattern.compile(
            "\\b[a-zA-Z0-9_]+\\.(?:showTitle|sendTitle)\\s*\\([^;]*(?:\"[^\"]+\"|Component\\.text\\s*\\(\\s*\"[^\"]+\")"
    );

    // 4. Bukkit or Server broadcast calls with literal strings or Component.text("literal")
    private static final Pattern BROADCAST_LITERAL_PATTERN = Pattern.compile(
            "\\b(?:Bukkit|Server|server)\\.(?:broadcast|broadcastMessage)\\s*\\([^;]*(?:\"[^\"]+\"|Component\\.text\\s*\\(\\s*\"[^\"]+\")"
    );

    static List<String> scanForViolations(String content) {
        List<String> found = new ArrayList<>();
        Matcher m1 = DIRECT_LITERAL_MESSAGE_PATTERN.matcher(content);
        while (m1.find()) {
            found.add("Direct literal: " + m1.group());
        }
        Matcher m2 = COMPONENT_TEXT_MESSAGE_PATTERN.matcher(content);
        while (m2.find()) {
            found.add("Component.text literal: " + m2.group());
        }
        Matcher m3 = TITLE_LITERAL_PATTERN.matcher(content);
        while (m3.find()) {
            found.add("Title literal: " + m3.group());
        }
        Matcher m4 = BROADCAST_LITERAL_PATTERN.matcher(content);
        while (m4.find()) {
            found.add("Broadcast literal: " + m4.group());
        }
        return found;
    }

    @Test
    @DisplayName("T-032c / DoD 5: No player-visible string is hardcoded in Java source files")
    void noPlayerVisibleStringsHardcodedInJava() throws Exception {
        Path srcRoot = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(srcRoot)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();

            for (Path javaFile : javaFiles) {
                String content = Files.readString(javaFile, StandardCharsets.UTF_8);
                List<String> fileViolations = scanForViolations(content);
                for (String v : fileViolations) {
                    violations.add(javaFile + ": " + v);
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
    @DisplayName("T-032c: Widened scanner catches all player-visible display API shapes (negative test)")
    void scannerDetectsAllHardcodedNegativeExamples() {
        List<String> negativeExamples = List.of(
                "viewer.sendMessage(\"Hello\");",
                "player.sendMessage(Component.text(\"Hello\").color(NamedTextColor.RED));",
                "player.showTitle(Title.title(Component.text(\"Title\"), Component.text(\"Subtitle\")));",
                "player.sendTitle(\"Title\", \"Subtitle\", 10, 70, 20);",
                "Bukkit.broadcast(Component.text(\"Hello\"));",
                "Bukkit.broadcastMessage(\"Broadcast\");",
                "server.broadcastMessage(\"Server broadcast\");",
                "audience.sendActionBar(\"Action bar\");",
                "target.sendActionBar(Component.text(\"Action bar\"));"
        );

        for (String example : negativeExamples) {
            List<String> violations = scanForViolations(example);
            assertThat(violations)
                    .withFailMessage("Scanner failed to detect violation in: %s", example)
                    .isNotEmpty();
        }
    }
}
