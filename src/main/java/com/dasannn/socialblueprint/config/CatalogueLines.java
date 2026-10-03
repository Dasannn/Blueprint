package com.dasannn.socialblueprint.config;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Single-line catalogue validation shared by load, edit and delivery. */
public final class CatalogueLines {
    public static final String CUSTOM = "effects.private-chat.custom-lines";
    public static final Set<String> LISTS = Set.of("effects.private-chat.lines", CUSTOM,
            "effects.advancement-toast.lines", "effects.boss-bar.lines", "effects.false-death.lines",
            "effects.serenity.warm-phrases.lines", "effects.subliminal.words");
    public static final Set<String> TEMPLATES = Set.of("effects.fake-connection.join",
            "effects.fake-connection.leave");
    // ponytail: conservative English/Spanish keywords; extend for additional server languages.
    private static final Pattern NOTICE = Pattern.compile(
            "(?iu)\\b(join(?:ed)?|left|leave|disconnect(?:ed)?|connect(?:ed)?|died|death|slain|killed|"
            + "advancement|achievement|banned|ban|kick(?:ed)?|mute(?:d)?|warn(?:ed|ing)?|moderation|"
            + "permission|balance|money|economy|paid|payment|coins?|credits?|currency|reward|"
            + "entro|salio|conect(?:ado|o)|desconect(?:ado|o)|murio|muerte|asesinad[oa]|matado|"
            + "avance|logro|expulsad[oa]|silenciad[oa]|advertencia|moderacion|permiso|saldo|dinero|"
            + "economia|pag[oa]|monedas?|credito|recompensa)\\b|[$\u20ac\u00a3\u00a5]|\\[\\s*(server|servidor|system|sistema)\\s*\\]");
    private static final Pattern ACTION = Pattern.compile("(?i)\\b(click(?:_event|event)?|hover(?:_event|event)?|run_command|suggest_command|open_url|insertion)\\b");
    private CatalogueLines() {}

    public static void validateLine(String key, int index, String line, Map<String, String> values, int max, boolean custom) {
        String path = index < 0 ? key : key + "[" + index + "]";
        ColorParser.validate(line, path);
        if (line.isBlank() || line.codePoints().anyMatch(c -> Character.isISOControl(c)
                || c == 0x2028 || c == 0x2029) || ACTION.matcher(line).find())
            throw new ConfigValidationException(path, "Expected one noninteractive line");
        String visible = PlainTextComponentSerializer.plainText().serialize(ColorParser.renderTemplate(line, values));
        if (visible.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)
                || visible.codePointCount(0, visible.length()) > max)
            throw new ConfigValidationException(path, "Line exceeds visible length or contains a line break");
        String normalized = Normalizer.normalize(visible, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        if (custom && NOTICE.matcher(normalized).find())
            throw new ConfigValidationException(path, "Custom line impersonates a server notice");
    }

    public static List<String> validateList(String key, Object raw, int max) {
        if (!(raw instanceof List<?> list) || list.isEmpty() && !key.equals(CUSTOM))
            throw new ConfigValidationException(key, "Expected a list of lines");
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof String line))
                throw new ConfigValidationException(key + "[" + i + "]", "Expected text");
            if (key.equals("effects.false-death.lines") && !line.contains("{player}"))
                throw new ConfigValidationException(key + "[" + i + "]", "Must contain {player}");
            // Reserve the full vanilla username width on load/edit; delivery checks the actual name.
            validateLine(key, i, line, Map.of("player", "x".repeat(16)), key.equals("effects.subliminal.words") ? 32 : max, key.equals(CUSTOM));
            if (key.equals("effects.subliminal.words")) {
                String visible = PlainTextComponentSerializer.plainText().serialize(ColorParser.renderTemplate(line, Map.of("player", "x".repeat(16))));
                if (visible.isBlank() || visible.codePoints().anyMatch(Character::isWhitespace))
                    throw new ConfigValidationException(key + "[" + i + "]", "Expected a single word");
            }
        }
        return list.stream().map(String.class::cast).toList();
    }

    public static String editValue(String key, String raw, int max) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString("lines: " + raw); }
        catch (org.bukkit.configuration.InvalidConfigurationException error) {
            throw new ConfigValidationException(key, "Expected a YAML list");
        }
        List<String> lines = validateList(key, yaml.get("lines"), max);
        return "[" + String.join(", ", lines.stream().map(s -> "'" + s.replace("'", "''") + "'").toList()) + "]";
    }

    public static void validateSnapshot(MessagesSnapshot messages, int max) {
        List<Map<String, String>> maps = List.of(messages.activeMessages(), messages.fallbackMessages(),
                messages.bundledActiveMessages(), messages.bundledFallbackMessages());
        for (int m = 0; m < maps.size(); m++) {
            Map<String, String> map = maps.get(m);
            for (String key : LISTS) {
                String raw = map.get(key);
                if (raw == null || raw.isEmpty() && key.equals(CUSTOM)) continue;
                validateList(key, List.of(raw.split("\n", -1)), m < 2 && key.startsWith("effects.private-chat.") ? max : 160);
            }
            for (String key : TEMPLATES) if (map.containsKey(key))
                validateLine(key, -1, map.get(key), Map.of(), 160, false);
        }
    }
}
