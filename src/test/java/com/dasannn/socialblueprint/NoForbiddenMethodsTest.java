package com.dasannn.socialblueprint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class NoForbiddenMethodsTest {

    // DoD 4 / T-044 / SB-014: Never call setDisplayName, setPlayerListName or setCustomName
    // 1. Direct legacy setter method calls: player.setDisplayName(...), player.setPlayerListName(...), player.setCustomName(...)
    private static final Pattern LEGACY_SETTER_CALL = Pattern.compile(
            "\\.(?:setDisplayName|setPlayerListName|setCustomName)\\s*\\("
    );

    // 2. Adventure/Paper component setters: player.displayName(component), player.playerListName(component), player.customName(component)
    // Non-empty argument distinguishes 1-arg setters from 0-arg getters like tier.displayName()
    private static final Pattern COMPONENT_SETTER_CALL = Pattern.compile(
            "\\.(?:displayName|playerListName|customName)\\s*\\(\\s*[^)\\s]"
    );

    // 3. Method references: player::setDisplayName, player::setPlayerListName, player::setCustomName
    private static final Pattern METHOD_REFERENCE = Pattern.compile(
            "::(?:setDisplayName|setPlayerListName|setCustomName)\\b"
    );

    // 4. Reflection: String literals naming forbidden setter methods
    private static final Pattern REFLECTION_STRING = Pattern.compile(
            "\"(?:setDisplayName|setPlayerListName|setCustomName)\""
    );

    // 5. Reflection targeting component methods: e.g. getMethod("displayName", ...) or getDeclaredMethod("displayName", ...)
    private static final Pattern REFLECTION_COMPONENT = Pattern.compile(
            "\\b(?:getMethod|getDeclaredMethod)\\s*\\(\\s*\"(?:displayName|playerListName|customName)\""
    );

    static List<String> findViolationsIn(String content, String sourceIdentifier) {
        List<String> violations = new ArrayList<>();
        Matcher m1 = LEGACY_SETTER_CALL.matcher(content);
        while (m1.find()) {
            violations.add(sourceIdentifier + ": forbidden legacy setter call -> " + m1.group());
        }
        Matcher m2 = COMPONENT_SETTER_CALL.matcher(content);
        while (m2.find()) {
            violations.add(sourceIdentifier + ": forbidden component setter call -> " + m2.group());
        }
        Matcher m3 = METHOD_REFERENCE.matcher(content);
        while (m3.find()) {
            violations.add(sourceIdentifier + ": forbidden method reference -> " + m3.group());
        }
        Matcher m4 = REFLECTION_STRING.matcher(content);
        while (m4.find()) {
            violations.add(sourceIdentifier + ": forbidden reflection string literal -> " + m4.group());
        }
        Matcher m5 = REFLECTION_COMPONENT.matcher(content);
        while (m5.find()) {
            violations.add(sourceIdentifier + ": forbidden reflection component method lookup -> " + m5.group());
        }
        return violations;
    }

    @Test
    @DisplayName("DoD 4 / T-044 / Finding 10: No forbidden name modification calls exist in production source")
    void noForbiddenMethodCallsInSource() throws Exception {
        Path srcMain = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(srcMain)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path javaFile : javaFiles) {
                String content = Files.readString(javaFile, StandardCharsets.UTF_8);
                violations.addAll(findViolationsIn(content, javaFile.toString()));
            }
        }

        assertThat(violations)
                .withFailMessage("DoD 4 violated: forbidden name modification patterns found in production code:\n%s",
                        String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("Finding 10: Scanner catches method references, reflection, and component setters, while allowing 0-arg getters")
    void scannerCatchesAllForbiddenFormsAndAllowsGetters() {
        // Direct legacy setters
        assertThat(findViolationsIn("player.setDisplayName(\"foo\");", "test")).isNotEmpty();
        assertThat(findViolationsIn("player.setPlayerListName(\"foo\");", "test")).isNotEmpty();
        assertThat(findViolationsIn("player.setCustomName(\"foo\");", "test")).isNotEmpty();

        // Adventure/Paper component setters
        assertThat(findViolationsIn("player.displayName(Component.text(\"foo\"));", "test")).isNotEmpty();
        assertThat(findViolationsIn("player.playerListName(component);", "test")).isNotEmpty();
        assertThat(findViolationsIn("entity.customName(component);", "test")).isNotEmpty();

        // Method references
        assertThat(findViolationsIn("Consumer<Component> c = player::setDisplayName;", "test")).isNotEmpty();
        assertThat(findViolationsIn("Consumer<Component> c = Player::setPlayerListName;", "test")).isNotEmpty();
        assertThat(findViolationsIn("Consumer<Component> c = player::setCustomName;", "test")).isNotEmpty();

        // Reflection
        assertThat(findViolationsIn("Method m = player.getClass().getMethod(\"setDisplayName\", String.class);", "test")).isNotEmpty();
        assertThat(findViolationsIn("Method m = player.getClass().getDeclaredMethod(\"setPlayerListName\", String.class);", "test")).isNotEmpty();
        assertThat(findViolationsIn("Method m = player.getClass().getDeclaredMethod(\"displayName\", Component.class);", "test")).isNotEmpty();
        assertThat(findViolationsIn("String name = \"setCustomName\";", "test")).isNotEmpty();

        // Allowed 0-argument getters
        assertThat(findViolationsIn("String name = tier.displayName();", "test")).isEmpty();
        assertThat(findViolationsIn("String name = confidence.displayName();", "test")).isEmpty();
        assertThat(findViolationsIn("Component c = player.displayName();", "test")).isEmpty();
        assertThat(findViolationsIn("Component c = player.playerListName();", "test")).isEmpty();
        assertThat(findViolationsIn("Component c = entity.customName();", "test")).isEmpty();
    }
}
