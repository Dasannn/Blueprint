package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;

/** A YAML list is one editable message leaf; individual lines are rendered by index. */
public final class ScreenLines {
    public static final String KEY = "effects.screen-flash.lines";
    private ScreenLines() {}

    public static List<String> validate(Object raw) {
        return validate(raw, KEY, Integer.MAX_VALUE, 160);
    }

    public static List<String> validate(Object raw, String key, int maxLines, int maxLength) {
        if (!(raw instanceof List<?> list) || list.isEmpty())
            throw new ConfigValidationException(key, "Must be a nonempty list of short lines");
        if (list.size() > maxLines) throw new ConfigValidationException(key, "Too many lines: maximum " + maxLines);
        for (Object entry : list) {
            if (!(entry instanceof String line) || line.isBlank() || line.codePoints().anyMatch(Character::isISOControl))
                throw new ConfigValidationException(key, "Each entry must be a nonempty single line");
            ColorParser.validate(line, key);
            String visible = line.replaceAll("(?i)&(?:#[0-9a-f]{6}|[0-9a-fk-or])", "");
            if (visible.codePointCount(0, visible.length()) > maxLength)
                throw new ConfigValidationException(key, "Lines must not exceed " + maxLength + " visible code points");
        }
        return list.stream().map(String.class::cast).toList();
    }

    public static String editValue(String raw) {
        return editValue(raw, KEY);
    }

    public static String editValue(String raw, String key) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString("lines: " + raw); }
        catch (org.bukkit.configuration.InvalidConfigurationException error) {
            throw new ConfigValidationException(key, "Expected a YAML list");
        }
        List<String> lines = validate(yaml.get("lines"), key, key.equals("effects.sign.lines") ? 4 : Integer.MAX_VALUE,
                key.equals("effects.sign.lines") ? 80 : 160);
        return "[" + String.join(", ", lines.stream().map(line -> "'" + line.replace("'", "''") + "'").toList()) + "]";
    }

    public static String validateGhostLabel(String value) {
        String key = "effects.victim-ghost.label";
        validate(List.of(value), key, 1, 160);
        validate(List.of(value.replace("{victim}", "x".repeat(16))), key, 1, 160);
        if (!value.contains("{victim}") || value.replaceAll("(?i)&(?:#[0-9a-f]{6}|[0-9a-fk-or])", "")
                .replace("{victim}", "").isBlank())
            throw new ConfigValidationException(key, "Must include {victim} and an explicit ghost label");
        return value;
    }
}
