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
    private static final Pattern FORBIDDEN_PATTERN = Pattern.compile(
            "\\.(?:setDisplayName|setPlayerListName|setCustomName)\\s*\\("
    );

    @Test
    @DisplayName("DoD 4 / T-044: No setDisplayName, setPlayerListName or setCustomName call exists in source")
    void noForbiddenMethodCallsInSource() throws Exception {
        Path srcMain = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(srcMain)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path javaFile : javaFiles) {
                String content = Files.readString(javaFile, StandardCharsets.UTF_8);
                Matcher m = FORBIDDEN_PATTERN.matcher(content);
                while (m.find()) {
                    violations.add(javaFile + ": found forbidden method call -> " + m.group());
                }
            }
        }

        assertThat(violations)
                .withFailMessage("DoD 4 violated: forbidden name setter methods found in production code:\n%s",
                        String.join("\n", violations))
                .isEmpty();
    }
}
