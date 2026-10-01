package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for atomically updating leaf keys in YAML files while preserving
 * comments, formatting, indentation, and document structure.
 */
public final class YamlFileUpdater {

    private static final Pattern KEY_LINE_PATTERN = Pattern.compile("^(\\s*)(['\"]?[a-zA-Z0-9_.-]+['\"]?)\\s*:(.*)$");

    private YamlFileUpdater() {
    }

    /**
     * Atomically updates a leaf key in a YAML file on disk, preserving comments,
     * section dividers, indentation, and formatting.
     */
    public static void updateLeafAndSave(File targetFile, String path, String rawValue) throws IOException {
        String existingContent = Files.readString(targetFile.toPath(), StandardCharsets.UTF_8);
        String updatedContent = updateLeafContent(existingContent, path, rawValue);

        // Sanity check: ensure updated content is valid YAML
        try {
            YamlConfiguration check = new YamlConfiguration();
            check.load(new StringReader(updatedContent));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to produce valid YAML when updating '" + path + "': " + e.getMessage(), e);
        }

        // Atomically replace file via sibling temporary file
        Path targetPath = targetFile.toPath();
        Path tempPath = targetPath.resolveSibling(targetFile.getName() + ".tmp." + UUID.randomUUID());
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
    }

    /** Removes an obsolete key and its children without reformatting the rest of the file. */
    public static void removeLeafAndSave(File targetFile, String path) throws IOException {
        updateLeafAndSave(targetFile, path, null);
    }

    public static String updateLeafContent(String yamlContent, String path, String rawValue) {
        String[] segments = path.split("\\.");
        List<String> lines = new ArrayList<>(Arrays.asList(yamlContent.split("\\r?\\n", -1)));

        String lineSeparator = yamlContent.contains("\r\n") ? "\r\n" : "\n";

        record Frame(int indent, String key) {}
        List<Frame> stack = new ArrayList<>();

        int matchedLineIndex = -1;
        String matchedIndent = "";
        String matchedKey = "";
        String inlineComment = "";

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            Matcher matcher = KEY_LINE_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }

            String indentStr = matcher.group(1);
            int indent = indentStr.length();
            String keyStr = matcher.group(2).replace("'", "").replace("\"", "");
            String afterColon = matcher.group(3);

            while (!stack.isEmpty() && stack.getLast().indent() >= indent) {
                stack.removeLast();
            }

            stack.add(new Frame(indent, keyStr));

            if (stack.size() == segments.length) {
                boolean match = true;
                for (int s = 0; s < segments.length; s++) {
                    if (!stack.get(s).key().equals(segments[s])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    matchedLineIndex = i;
                    matchedIndent = indentStr;
                    matchedKey = keyStr;
                    int hashIdx = findCommentStart(afterColon);
                    if (hashIdx >= 0) {
                        inlineComment = " " + afterColon.substring(hashIdx).trim();
                    }
                    break;
                }
            }
        }

        if (matchedLineIndex < 0) {
            throw new IllegalArgumentException("Key path '" + path + "' was not found in YAML content");
        }

        String formattedValue = rawValue != null ? formatYamlValue(rawValue) : "";
        String newLine = matchedIndent + matchedKey + ": " + formattedValue + inlineComment;
        lines.set(matchedLineIndex, rawValue != null ? newLine : "");

        // Remove any block child lines (e.g. list items or map entries) that followed this key
        int nextIdx = matchedLineIndex + 1;
        while (nextIdx < lines.size()) {
            String nextLine = lines.get(nextIdx);
            String trimmedNext = nextLine.trim();
            if (trimmedNext.isEmpty()) {
                break;
            }
            int nextIndent = getLineIndent(nextLine);
            if (nextIndent > matchedIndent.length()) {
                lines.remove(nextIdx);
            } else {
                break;
            }
        }

        return String.join(lineSeparator, lines);
    }

    private static int getLineIndent(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static int findCommentStart(String text) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                return i;
            }
        }
        return -1;
    }

    private static String formatYamlValue(String raw) {
        String trimmed = raw.trim();
        if ("true".equalsIgnoreCase(trimmed)) return "true";
        if ("false".equalsIgnoreCase(trimmed)) return "false";

        try {
            Long.parseLong(trimmed);
            return trimmed;
        } catch (NumberFormatException ignored) {}

        if (trimmed.matches("^[+-]?\\d+\\.\\d+$")) {
            return trimmed;
        }

        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed;
        }

        return "'" + raw.replace("'", "''") + "'";
    }
}
