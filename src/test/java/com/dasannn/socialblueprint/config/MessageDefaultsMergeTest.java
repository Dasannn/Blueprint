package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageDefaultsMergeTest {
    @TempDir
    Path directory;

    private File messages;
    private Path baseline;
    private Logger logger;
    private final List<String> summaries = new ArrayList<>();

    @BeforeEach
    void setUp() {
        messages = directory.resolve("messages_en.yml").toFile();
        baseline = directory.resolve(".defaults/messages_en.yml");
        logger = Logger.getLogger("MessageDefaultsMergeTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                summaries.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
    }

    @Test
    void untouchedKeyUpdatesAndCustomisedKeysArePreservedWithNamedConflicts() throws Exception {
        String original = """
                status:
                  untouched: 'old'
                  custom: 'default'
                  conflict: 'old default'
                  removed: 'keep me'
                """;
        String operator = original.replace("custom: 'default'", "custom: 'operator'")
                .replace("conflict: 'old default'", "conflict: 'operator conflict'");
        String shipped = """
                status:
                  untouched: 'new'
                  custom: 'default'
                  conflict: 'new default'
                  added: 'addition'
                """;
        writeBaseline(original);
        Files.writeString(messages.toPath(), operator);

        assertThat(merge(shipped)).isEqualTo(1);

        assertThat(values(messages.toPath())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "status.untouched", "new", "status.custom", "operator",
                "status.conflict", "operator conflict", "status.added", "addition",
                "status.removed", "keep me"));
        assertThat(values(baseline)).isEqualTo(values(shipped));
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst()).contains("Added 1", "updated 1", "conflicting keys: status.conflict")
                .doesNotContain("status.custom", "no baseline");

        summaries.clear();
        merge(shipped);
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst()).contains("updated 0", "conflicting keys: none");
    }

    @Test
    void missingBaselineOnlyAddsKeysAndExplainsWhy() throws Exception {
        Files.writeString(messages.toPath(), "existing: 'operator or old default'\nremoved: 'retain'\n");
        String shipped = "existing: 'current default'\nadded: 'new key'\n";

        assertThat(merge(shipped)).isEqualTo(1);

        assertThat(values(messages.toPath())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "existing", "operator or old default", "removed", "retain", "added", "new key"));
        assertThat(values(baseline)).isEqualTo(values(shipped));
        assertThat(Files.readString(baseline)).startsWith("# Plugin-managed shipped defaults. DO NOT EDIT.");
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst()).contains("updated 0", "no baseline", "cannot know what was customised",
                "preserved existing values", "added missing keys only");
    }

    @Test
    void deletedBaselineFallsBackSafelyAndIsRecreated() throws Exception {
        Files.writeString(messages.toPath(), "entry: 'v1'\n");
        merge("entry: 'v1'\n");
        merge("entry: 'v2'\n");
        assertThat(values(baseline)).containsEntry("entry", "v2");
        Files.delete(baseline);
        summaries.clear();

        merge("entry: 'v3'\nadded: 'new'\n");

        assertThat(values(messages.toPath())).containsEntry("entry", "v2").containsEntry("added", "new");
        assertThat(values(baseline)).containsEntry("entry", "v3");
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst()).contains("no baseline", "updated 0");
    }

    @Test
    void firstRunWithoutAdditionsDoesNotRewriteOperatorFile() throws Exception {
        String content = "# operator comment\r\nentry: \"custom\"\r\n";
        Files.writeString(messages.toPath(), content);

        assertThat(merge("entry: 'default'\n")).isZero();

        assertThat(Files.readString(messages.toPath())).isEqualTo(content);
        assertThat(values(baseline)).containsEntry("entry", "default");
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst()).contains("no baseline");
    }

    @Test
    void freshInstallIsSilent() throws Exception {
        String shipped = "entry: 'default'\n";
        Files.writeString(messages.toPath(), shipped);

        assertThat(merge(shipped)).isZero();

        assertThat(summaries).isEmpty();
        assertThat(values(baseline)).containsEntry("entry", "default");
    }

    @Test
    void comparesParsedValuesAndPreservesUnrelatedFormattingAndStringTypes() throws Exception {
        writeBaseline("entry: 'old'\nblock: |-\n  old block\n");
        Files.writeString(messages.toPath(), "# operator banner\r\nentry: \"old\"\r\nblock: |-\r\n  old block\r\ncustom: \"preserve quotes\"\r\n");
        String shipped = "entry: '[true]'\nblock: |-\n  first line\n  second line\n";

        merge(shipped);

        assertThat(values(messages.toPath())).containsEntry("entry", "[true]")
                .containsEntry("block", "first line\nsecond line")
                .containsEntry("custom", "preserve quotes");
        assertThat(Files.readString(messages.toPath())).startsWith("# operator banner\r\n")
                .contains("custom: \"preserve quotes\"\r\n");
    }

    @Test
    void startupMergeAppliesToBothLanguagesAndLeavesConfigMergeAlone() throws Exception {
        for (String language : List.of("en", "es")) {
            String name = "messages_" + language + ".yml";
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
                assertThat(in).isNotNull();
                String shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                String old = YamlFileUpdater.updateLeafContent(shipped, "status.history-entry", "old template");
                Path stored = directory.resolve(".defaults").resolve(name);
                Files.createDirectories(stored.getParent());
                Files.writeString(stored, old);
                Files.writeString(directory.resolve(name), old);
            }
        }
        File config = directory.resolve("config.yml").toFile();
        String originalConfig = "# owner config\nhonor:\n  cost: 250.0\n";
        Files.writeString(config.toPath(), originalConfig);

        ConfigMerger.mergeMissingDefaults(config, directory.toFile(), "test", logger);

        for (String language : List.of("en", "es")) {
            String name = "messages_" + language + ".yml";
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
                assertThat(in).isNotNull();
                Map<String, String> shipped = values(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                assertThat(values(directory.resolve(name))).isEqualTo(shipped);
                assertThat(values(directory.resolve(".defaults").resolve(name))).isEqualTo(shipped);
            }
        }
        assertThat(values(config.toPath())).containsEntry("honor.cost", "250.0");
        assertThat(Files.readString(directory.resolve("config.yml.bak-test"))).isEqualTo(originalConfig);
        assertThat(summaries.stream().filter(line -> line.contains("messages_")).toList()).hasSize(2)
                .allMatch(line -> line.contains("updated 1") && line.contains("conflicting keys: none"));
    }

    @Test
    void failedMergeDoesNotAdvanceBaselineOrRewriteOperatorFile() throws Exception {
        String original = "entry: 'old'\n";
        writeBaseline(original);
        Files.writeString(messages.toPath(), "entry: [invalid\n");

        assertThatThrownBy(() -> merge("entry: 'new'\n")).isInstanceOf(IllegalStateException.class);

        assertThat(Files.readString(baseline)).isEqualTo(original);
        assertThat(Files.readString(messages.toPath())).isEqualTo("entry: [invalid\n");
        assertThat(summaries).isEmpty();
    }

    private int merge(String shipped) {
        return ConfigMerger.mergeFile(messages, shipped, null, false, logger);
    }

    private void writeBaseline(String content) throws Exception {
        Files.createDirectories(baseline.getParent());
        Files.writeString(baseline, content);
    }

    private Map<String, String> values(Path file) throws Exception {
        return values(Files.readString(file));
    }

    private Map<String, String> values(String content) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new StringReader(content));
        return MessageRegistry.flattenKeys(yaml);
    }
}
