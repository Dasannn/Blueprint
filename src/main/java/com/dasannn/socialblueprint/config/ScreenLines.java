package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;

/** A YAML list is one editable message leaf; individual lines are rendered by index. */
public final class ScreenLines {
    public static final String KEY = "effects.screen-flash.lines";
    private ScreenLines() {}

    public static List<String> validate(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty())
            throw new ConfigValidationException(KEY, "Must be a nonempty list of short lines");
        for (Object entry : list) {
            if (!(entry instanceof String line) || line.isBlank() || line.codePoints().anyMatch(Character::isISOControl))
                throw new ConfigValidationException(KEY, "Each entry must be a nonempty single line");
            ColorParser.validate(line, KEY);
            String visible = line.replaceAll("(?i)&(?:#[0-9a-f]{6}|[0-9a-fk-or])", "");
            if (visible.codePointCount(0, visible.length()) > 160)
                throw new ConfigValidationException(KEY, "Lines must not exceed 160 visible code points");
        }
        return list.stream().map(String.class::cast).toList();
    }

    public static String editValue(String raw) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString("lines: " + raw); }
        catch (org.bukkit.configuration.InvalidConfigurationException error) {
            throw new ConfigValidationException(KEY, "Expected a YAML list");
        }
        List<String> lines = validate(yaml.get("lines"));
        return "[" + String.join(", ", lines.stream().map(line -> "'" + line.replace("'", "''") + "'").toList()) + "]";
    }
}
