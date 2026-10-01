package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merges missing configuration sections, keys, and comments from bundled defaults
 * into disk configuration on server upgrade (T-104). Message files additionally
 * use stored shipped defaults to update untouched translations safely.
 *
 * <p>Config merge rules:
 * <ul>
 *   <li>Never change a value the server already has.</li>
 *   <li>Never reorder or delete anything.</li>
 *   <li>Never touch a key or translation the owner has customised.</li>
 *   <li>Carry over preceding comments and section banners for added keys.</li>
 *   <li>Perform writes atomically via temporary files in the same directory.</li>
 *   <li>Back up config.yml once per upgrade as {@code config.yml.bak-<version>} before writing.</li>
 *   <li>Log one line naming count and file when entries are added; log nothing and write nothing when current.</li>
 * </ul>
 */
public final class ConfigMerger {

    private static final Pattern KEY_LINE_PATTERN = Pattern.compile("^(\\s*)(['\"]?[a-zA-Z0-9_.-]+['\"]?)\\s*:(.*)$");

    private ConfigMerger() {
    }

    /**
     * Represents a parsed YAML entry with its hierarchical path, indentation,
     * preceding comments, key line, direct value lines (e.g. lists), and children.
     */
    public static final class YamlEntry {
        private final String fullPath;
        private final String parentPath;
        private final String key;
        private final int indent;
        private final List<String> leadingComments;
        private final String keyLine;
        private final List<String> valueLines;
        private final List<YamlEntry> children = new ArrayList<>();

        public YamlEntry(
                String fullPath,
                String parentPath,
                String key,
                int indent,
                List<String> leadingComments,
                String keyLine,
                List<String> valueLines
        ) {
            this.fullPath = fullPath;
            this.parentPath = parentPath;
            this.key = key;
            this.indent = indent;
            this.leadingComments = new ArrayList<>(leadingComments);
            this.keyLine = keyLine;
            this.valueLines = new ArrayList<>(valueLines);
        }

        public String fullPath() {
            return fullPath;
        }

        public String parentPath() {
            return parentPath;
        }

        public String key() {
            return key;
        }

        public int indent() {
            return indent;
        }

        public List<String> leadingComments() {
            return leadingComments;
        }

        public String keyLine() {
            return keyLine;
        }

        public List<String> valueLines() {
            return valueLines;
        }

        public List<YamlEntry> children() {
            return children;
        }

        /**
         * Renders all lines comprising this entry and its entire subtree as they appear in the source YAML.
         */
        public List<String> renderAllLines() {
            List<String> rendered = new ArrayList<>(leadingComments);
            if (keyLine != null && !keyLine.isEmpty()) {
                rendered.add(keyLine);
            }
            rendered.addAll(valueLines);
            for (YamlEntry child : children) {
                rendered.addAll(child.renderAllLines());
            }
            return rendered;
        }
    }

    /**
     * Merges missing defaults into config.yml and all messages_*.yml files in the data directory.
     *
     * @param configFile the config.yml file on disk
     * @param dataFolder the plugin data directory
     * @param version    the current plugin version for the backup filename
     * @param logger     logger for upgrade summaries
     * @return the number of entries added to config.yml
     */
    public static int mergeMissingDefaults(File configFile, File dataFolder, String version, Logger logger) {
        String effectiveVersion = (version != null && !version.isBlank()) ? version.trim() : "1.0";
        int configAdded = mergeConfigFile(configFile, effectiveVersion, logger);
        if (dataFolder != null && dataFolder.exists()) {
            mergeMessageFiles(dataFolder, logger);
        }
        return configAdded;
    }

    /**
     * Merges missing defaults into config.yml.
     */
    public static int mergeConfigFile(File configFile, String version, Logger logger) {
        if (configFile == null || !configFile.exists()) {
            return 0;
        }
        String bundledContent = readResourceString("config.yml");
        if (bundledContent == null || bundledContent.isBlank()) {
            return 0;
        }
        return mergeFile(configFile, bundledContent, version, true, logger);
    }

    /**
     * Three-way merges translations in all messages_*.yml files present in the data folder.
     */
    public static int mergeMessageFiles(File dataFolder, Logger logger) {
        if (dataFolder == null || !dataFolder.exists()) {
            return 0;
        }
        File[] messageFiles = dataFolder.listFiles((dir, name) -> name.matches("messages_[a-zA-Z0-9_-]+\\.yml"));
        if (messageFiles == null) {
            return 0;
        }

        int totalAdded = 0;
        for (File msgFile : messageFiles) {
            String name = msgFile.getName();
            String lang = name.substring("messages_".length(), name.length() - ".yml".length());

            String bundled = readResourceString("messages_" + lang + ".yml");
            if (bundled == null) {
                // Fallback to English bundled resource if specific language file is not in jar
                bundled = readResourceString("messages_en.yml");
            }

            if (bundled != null && !bundled.isBlank()) {
                totalAdded += mergeFile(msgFile, bundled, null, false, logger);
            }
        }
        return totalAdded;
    }

    /**
     * Core merge implementation: parses bundled and disk YAML, identifies absent sections/keys,
     * performs atomic backup and write if modifications are needed, and logs a summary line.
     */
    public static int mergeFile(File diskFile, String bundledYamlContent, String version, boolean isConfigFile, Logger logger) {
        Objects.requireNonNull(diskFile, "diskFile must not be null");
        Objects.requireNonNull(bundledYamlContent, "bundledYamlContent must not be null");

        if (!isConfigFile) {
            return mergeMessageFile(diskFile, bundledYamlContent, logger);
        }

        if (!diskFile.exists()) {
            return 0;
        }

        try {
            String diskContent = Files.readString(diskFile.toPath(), StandardCharsets.UTF_8);
            YamlConfiguration diskYaml = new YamlConfiguration();
            diskYaml.load(new StringReader(diskContent));

            List<String> bundledLines = Arrays.asList(bundledYamlContent.split("\\r?\\n", -1));
            List<YamlEntry> bundledTree = parseYamlEntries(bundledLines);

            List<YamlEntry> diskTree = parseYamlEntries(Arrays.asList(diskContent.split("\\r?\\n", -1)));
            List<YamlEntry> missingEntries = findMissingEntries(bundledTree, diskTree);
            if (missingEntries.isEmpty()) {
                // Already current: no writes, no backup, no log output
                return 0;
            }

            // Backup config.yml once per upgrade before the first write
            if (isConfigFile) {
                String ver = (version != null && !version.isBlank()) ? version.trim() : "1.0";
                File backupFile = new File(diskFile.getParentFile(), "config.yml.bak-" + ver);
                if (!backupFile.exists()) {
                    Files.copy(diskFile.toPath(), backupFile.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                }
            }

            // Apply missing entries onto disk lines
            String lineSeparator = "\n";
            List<String> diskLines = new ArrayList<>(Arrays.asList(diskContent.split("\\r?\\n", -1)));

            for (YamlEntry missing : missingEntries) {
                insertEntry(diskLines, missing, bundledTree);
            }

            String updatedContent = String.join(lineSeparator, diskLines);

            // Validate that the merged document is well-formed YAML
            YamlConfiguration check = new YamlConfiguration();
            check.load(new StringReader(updatedContent));

            // Write atomically via sibling temporary file
            Path targetPath = diskFile.toPath();
            Path tempPath = targetPath.resolveSibling(diskFile.getName() + ".tmp." + UUID.randomUUID());
            try {
                Files.writeString(tempPath, updatedContent, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempPath);
            }

            int count = missingEntries.size();
            if (logger != null) {
                logger.info(String.format("[SocialBlueprint] Added %d missing %s to %s",
                        count,
                        count == 1 ? "entry" : "entries",
                        diskFile.getName()));
            }

            return count;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to merge missing configuration defaults into " + diskFile.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Stores plugin-owned baselines separately from editable messages. Without a
     * baseline, existing values are preserved because their origin is unknown.
     * Returns the number of added keys, retaining the existing merge API contract.
     */
    private static int mergeMessageFile(File diskFile, String bundledContent, Logger logger) {
        if (!diskFile.exists()) {
            return 0;
        }
        Path baselinePath = diskFile.toPath().resolveSibling(".defaults").resolve(diskFile.getName());
        try {
            String diskContent = Files.readString(diskFile.toPath(), StandardCharsets.UTF_8);
            Map<String, String> current = messageValues(diskContent);
            Map<String, String> shipped = messageValues(bundledContent);
            Map<String, String> expected = new HashMap<>(current);
            boolean firstRun = !Files.exists(baselinePath);
            Map<String, String> baseline = firstRun ? shipped
                    : messageValues(Files.readString(baselinePath, StandardCharsets.UTF_8));

            List<YamlEntry> bundledTree = parseYamlEntries(Arrays.asList(bundledContent.split("\\r?\\n", -1)));
            List<String> lines = new ArrayList<>(Arrays.asList(diskContent.split("\\r?\\n", -1)));
            List<YamlEntry> missing = findMissingEntries(bundledTree, parseYamlEntries(lines));
            for (YamlEntry entry : missing) {
                insertEntry(lines, entry, bundledTree);
            }

            int added = 0;
            int updated = 0;
            List<String> conflicts = new ArrayList<>();
            for (String key : shipped.keySet().stream().sorted().toList()) {
                String newValue = shipped.get(key);
                if (!current.containsKey(key)) {
                    added++;
                    expected.put(key, newValue);
                } else if (!firstRun && !Objects.equals(newValue, baseline.get(key))) {
                    if (baseline.containsKey(key) && Objects.equals(current.get(key), baseline.get(key))) {
                        // Copy the shipped YAML representation, including block scalar lines.
                        // This avoids reinterpreting strings such as "true" or "[text]".
                        YamlEntry replacement = findNodeInTree(bundledTree, key);
                        int start = findKeyLine(lines, key);
                        if (replacement == null || start < 0) {
                            throw new IllegalStateException("Cannot locate message key " + key);
                        }
                        int indentShift = leadingSpaces(lines.get(start)) - replacement.indent();
                        int end = findEntryEndLine(lines, start);
                        List<String> replacementLines = new ArrayList<>();
                        replacementLines.add(replacement.keyLine());
                        replacementLines.addAll(replacement.valueLines());
                        lines.subList(start, end).clear();
                        lines.addAll(start, replacementLines.stream()
                                .map(line -> line.isBlank() ? line : " ".repeat(Math.max(0, leadingSpaces(line) + indentShift)) + line.stripLeading())
                                .toList());
                        updated++;
                        expected.put(key, newValue);
                    } else {
                        conflicts.add(key);
                    }
                }
            }

            String merged = String.join(diskContent.contains("\r\n") ? "\r\n" : "\n", lines);
            Map<String, String> mergedValues = messageValues(merged);
            // Reject unsupported YAML layouts rather than overwrite the baseline with a failed merge.
            if (!expected.equals(mergedValues)) {
                throw new IllegalStateException("Merged messages do not match the expected values");
            }
            if (added > 0 || updated > 0) {
                writeMessageFile(diskFile.toPath(), merged);
            }
            Files.createDirectories(baselinePath.getParent());
            writeMessageFile(baselinePath, "# Plugin-managed shipped defaults. DO NOT EDIT.\n" + bundledContent);
            if (logger != null) {
                logger.info("[SocialBlueprint] Added " + added + " missing entries to " + diskFile.getName()
                        + "; updated " + updated + " untouched entries; conflicting keys: "
                        + (conflicts.isEmpty() ? "none" : String.join(", ", conflicts))
                        + (firstRun ? "; no baseline: cannot know what was customised on this first upgrade; "
                        + "preserved existing values and added missing keys only" : ""));
            }
            return added;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to merge message defaults into " + diskFile.getName() + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, String> messageValues(String content) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new StringReader(content));
        return MessageRegistry.flattenKeys(yaml);
    }

    private static void writeMessageFile(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp." + UUID.randomUUID());
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Parses YAML lines into a hierarchical tree of {@link YamlEntry} nodes preserving comments and formatting.
     */
    public static List<YamlEntry> parseYamlEntries(List<String> lines) {
        List<YamlEntry> rootEntries = new ArrayList<>();
        List<String> pendingComments = new ArrayList<>();
        List<YamlEntry> stack = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                pendingComments.add(line);
                continue;
            }

            // List items or continuation lines attach to the current entry's valueLines
            if (trimmed.startsWith("-") || !KEY_LINE_PATTERN.matcher(line).matches()) {
                if (!stack.isEmpty()) {
                    stack.get(stack.size() - 1).valueLines().addAll(pendingComments);
                    pendingComments.clear();
                    stack.get(stack.size() - 1).valueLines().add(line);
                } else {
                    pendingComments.add(line);
                }
                continue;
            }

            Matcher m = KEY_LINE_PATTERN.matcher(line);
            if (m.matches()) {
                int indent = m.group(1).length();
                String key = m.group(2).replace("'", "").replace("\"", "");

                while (!stack.isEmpty() && stack.get(stack.size() - 1).indent() >= indent) {
                    stack.remove(stack.size() - 1);
                }

                String parentPath = stack.isEmpty() ? null : stack.get(stack.size() - 1).fullPath();
                String fullPath = parentPath == null ? key : parentPath + "." + key;

                YamlEntry entry = new YamlEntry(
                        fullPath,
                        parentPath,
                        key,
                        indent,
                        pendingComments,
                        line,
                        Collections.emptyList()
                );
                pendingComments.clear();

                if (stack.isEmpty()) {
                    rootEntries.add(entry);
                } else {
                    stack.get(stack.size() - 1).children().add(entry);
                }
                stack.add(entry);
            }
        }

        return rootEntries;
    }

    /**
     * Traverses the bundled entry tree and returns missing entries where the key is absent on disk.
     * When an entire section is absent, only the root of that section is collected (incorporating all its children).
     */
    public static List<YamlEntry> findMissingEntries(List<YamlEntry> bundledEntries, List<YamlEntry> diskEntries) {
        List<YamlEntry> missing = new ArrayList<>();
        findMissingRecursive(bundledEntries, diskEntries, missing);
        return missing;
    }

    private static void findMissingRecursive(List<YamlEntry> entries, List<YamlEntry> diskEntries, List<YamlEntry> missing) {
        for (YamlEntry entry : entries) {
            YamlEntry onDisk = diskEntries.stream().filter(e -> e.key().equals(entry.key())).findFirst().orElse(null);
            if (onDisk == null) {
                // Entire entry or section is missing from disk
                missing.add(entry);
            } else if (!entry.children().isEmpty() && onDisk.keyLine().trim().endsWith(":")) {
                // An empty section header is still present even when the YAML loader treats it as null.
                findMissingRecursive(entry.children(), onDisk.children(), missing);
            }
        }
    }

    private static void insertEntry(List<String> diskLines, YamlEntry entry, List<YamlEntry> bundledTree) {
        List<String> linesToInsert = entry.renderAllLines();
        if (linesToInsert.isEmpty()) {
            return;
        }

        if (entry.parentPath() == null) {
            int insertIdx = findTopLevelInsertionIndex(diskLines, entry, bundledTree);
            insertLinesAt(diskLines, insertIdx, linesToInsert, true);
        } else {
            int insertIdx = findNestedInsertionIndex(diskLines, entry, bundledTree);
            YamlEntry parent = findNodeInTree(bundledTree, entry.parentPath());
            int parentLine = findKeyLine(diskLines, entry.parentPath());
            if (parent != null && parentLine >= 0) {
                int targetIndent = entry.indent() + leadingSpaces(diskLines.get(parentLine)) - parent.indent();
                for (YamlEntry sibling : parent.children()) {
                    int siblingLine = findKeyLine(diskLines, sibling.fullPath());
                    if (siblingLine >= 0) {
                        targetIndent = leadingSpaces(diskLines.get(siblingLine));
                        break;
                    }
                }
                int shift = targetIndent - entry.indent();
                if (shift != 0) {
                    linesToInsert = linesToInsert.stream()
                            .map(line -> line.isBlank() ? line : " ".repeat(Math.max(0, leadingSpaces(line) + shift)) + line.stripLeading())
                            .toList();
                }
            }
            while (!linesToInsert.isEmpty() && insertIdx < diskLines.size()
                    && linesToInsert.get(0).equals(diskLines.get(insertIdx))) {
                linesToInsert = linesToInsert.subList(1, linesToInsert.size());
                insertIdx++;
            }
            insertLinesAt(diskLines, insertIdx, linesToInsert, false);
        }
    }

    private static int leadingSpaces(String line) {
        return line.length() - line.stripLeading().length();
    }

    private static int findTopLevelInsertionIndex(List<String> diskLines, YamlEntry entry, List<YamlEntry> bundledTree) {
        int entryIndexInBundled = -1;
        for (int i = 0; i < bundledTree.size(); i++) {
            if (bundledTree.get(i).key().equals(entry.key())) {
                entryIndexInBundled = i;
                break;
            }
        }

        // 1. Look backwards in bundledTree for the nearest preceding top-level section that exists on disk
        for (int i = entryIndexInBundled - 1; i >= 0; i--) {
            YamlEntry prec = bundledTree.get(i);
            int precKeyLine = findKeyLine(diskLines, prec.fullPath());
            if (precKeyLine >= 0) {
                return findEntryEndLine(diskLines, precKeyLine);
            }
        }

        // 2. Look forward in bundledTree for the nearest following top-level section that exists on disk
        for (int i = entryIndexInBundled + 1; i < bundledTree.size(); i++) {
            YamlEntry foll = bundledTree.get(i);
            int follKeyLine = findKeyLine(diskLines, foll.fullPath());
            if (follKeyLine >= 0) {
                return findLeadingCommentStartLine(diskLines, follKeyLine);
            }
        }

        // 3. Fallback: append at end of file
        int end = diskLines.size();
        if (end > 0 && diskLines.get(end - 1).isEmpty()) {
            return end - 1;
        }
        return end;
    }

    private static int findNestedInsertionIndex(List<String> diskLines, YamlEntry entry, List<YamlEntry> bundledTree) {
        YamlEntry parentInBundled = findNodeInTree(bundledTree, entry.parentPath());
        int parentKeyLine = findKeyLine(diskLines, entry.parentPath());
        if (parentKeyLine < 0) {
            int end = diskLines.size();
            return (end > 0 && diskLines.get(end - 1).isEmpty()) ? end - 1 : end;
        }

        if (parentInBundled != null) {
            int entryIdxInParent = -1;
            for (int i = 0; i < parentInBundled.children().size(); i++) {
                if (parentInBundled.children().get(i).key().equals(entry.key())) {
                    entryIdxInParent = i;
                    break;
                }
            }

            // Look backwards among siblings in parent
            for (int i = entryIdxInParent - 1; i >= 0; i--) {
                YamlEntry precSibling = parentInBundled.children().get(i);
                int precSiblingLine = findKeyLine(diskLines, precSibling.fullPath());
                if (precSiblingLine >= 0) {
                    return findEntryEndLine(diskLines, precSiblingLine);
                }
            }
        }

        // No preceding sibling found on disk: insert right after parent key line
        return parentKeyLine + 1;
    }

    private static void insertLinesAt(List<String> diskLines, int insertIdx, List<String> linesToInsert, boolean isTopLevel) {
        List<String> toAdd = new ArrayList<>(linesToInsert);

        if (isTopLevel) {
            if (insertIdx > 0 && !diskLines.get(insertIdx - 1).trim().isEmpty()) {
                if (!toAdd.isEmpty() && !toAdd.get(0).trim().isEmpty()) {
                    toAdd.add(0, "");
                }
            } else if (insertIdx > 0 && diskLines.get(insertIdx - 1).trim().isEmpty()) {
                while (!toAdd.isEmpty() && toAdd.get(0).trim().isEmpty()) {
                    toAdd.remove(0);
                }
            }

            if (insertIdx < diskLines.size() && !diskLines.get(insertIdx).trim().isEmpty()) {
                if (!toAdd.isEmpty() && !toAdd.get(toAdd.size() - 1).trim().isEmpty()) {
                    toAdd.add("");
                }
            }
        }

        diskLines.addAll(insertIdx, toAdd);
    }

    private static int findKeyLine(List<String> diskLines, String fullPath) {
        String[] segments = fullPath.split("\\.");
        record Frame(int indent, String key) {}
        List<Frame> stack = new ArrayList<>();

        for (int i = 0; i < diskLines.size(); i++) {
            String line = diskLines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }

            Matcher m = KEY_LINE_PATTERN.matcher(line);
            if (!m.matches()) {
                continue;
            }

            int indent = m.group(1).length();
            String key = m.group(2).replace("'", "").replace("\"", "");

            while (!stack.isEmpty() && stack.get(stack.size() - 1).indent() >= indent) {
                stack.remove(stack.size() - 1);
            }
            stack.add(new Frame(indent, key));

            if (stack.size() == segments.length) {
                boolean match = true;
                for (int s = 0; s < segments.length; s++) {
                    if (!stack.get(s).key().equals(segments[s])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findEntryEndLine(List<String> diskLines, int keyLineIdx) {
        String keyLine = diskLines.get(keyLineIdx);
        Matcher m = KEY_LINE_PATTERN.matcher(keyLine);
        int baseIndent = m.matches() ? m.group(1).length() : 0;

        int lastContentLine = keyLineIdx;
        for (int i = keyLineIdx + 1; i < diskLines.size(); i++) {
            String line = diskLines.get(i);
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.startsWith("#")) {
                int commentIndent = line.indexOf('#');
                if (commentIndent > baseIndent) {
                    lastContentLine = i;
                    continue;
                } else {
                    break;
                }
            }

            Matcher keyMatcher = KEY_LINE_PATTERN.matcher(line);
            int lineIndent = 0;
            if (keyMatcher.matches()) {
                lineIndent = keyMatcher.group(1).length();
            } else {
                lineIndent = line.indexOf(trimmed.charAt(0));
            }

            if (lineIndent > baseIndent) {
                lastContentLine = i;
            } else {
                break;
            }
        }

        return lastContentLine + 1;
    }

    private static int findLeadingCommentStartLine(List<String> diskLines, int keyLineIdx) {
        int start = keyLineIdx;
        for (int i = keyLineIdx - 1; i >= 0; i--) {
            String line = diskLines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                start = i;
            } else if (trimmed.isEmpty()) {
                if (i - 1 >= 0 && diskLines.get(i - 1).trim().startsWith("#")) {
                    start = i;
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        return start;
    }

    private static YamlEntry findNodeInTree(List<YamlEntry> tree, String fullPath) {
        if (fullPath == null || tree == null) {
            return null;
        }
        for (YamlEntry entry : tree) {
            if (fullPath.equals(entry.fullPath())) {
                return entry;
            }
            YamlEntry childResult = findNodeInTree(entry.children(), fullPath);
            if (childResult != null) {
                return childResult;
            }
        }
        return null;
    }

    private static String readResourceString(String resourcePath) {
        try (InputStream in = ConfigMerger.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int read;
                while ((read = reader.read(buf)) != -1) {
                    sb.append(buf, 0, read);
                }
                return sb.toString();
            }
        } catch (IOException ignored) {
            return null;
        }
    }
}
