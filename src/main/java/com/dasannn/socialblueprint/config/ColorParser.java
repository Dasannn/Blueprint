package com.dasannn.socialblueprint.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;
import java.util.Objects;

/**
 * Adventure colour parsing per T-033 and Constitution §2.8.
 * One interpretation only: Adventure's legacy serializer with '&' as the character, hex supported (&#rrggbb).
 * Never also parses as MiniMessage — supporting both makes '&' sequences ambiguous.
 */
public final class ColorParser {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private static final Map<Character, NamedTextColor> NAMED_COLORS = Map.ofEntries(
            Map.entry('0', NamedTextColor.BLACK),
            Map.entry('1', NamedTextColor.DARK_BLUE),
            Map.entry('2', NamedTextColor.DARK_GREEN),
            Map.entry('3', NamedTextColor.DARK_AQUA),
            Map.entry('4', NamedTextColor.DARK_RED),
            Map.entry('5', NamedTextColor.DARK_PURPLE),
            Map.entry('6', NamedTextColor.GOLD),
            Map.entry('7', NamedTextColor.GRAY),
            Map.entry('8', NamedTextColor.DARK_GRAY),
            Map.entry('9', NamedTextColor.BLUE),
            Map.entry('a', NamedTextColor.GREEN),
            Map.entry('b', NamedTextColor.AQUA),
            Map.entry('c', NamedTextColor.RED),
            Map.entry('d', NamedTextColor.LIGHT_PURPLE),
            Map.entry('e', NamedTextColor.YELLOW),
            Map.entry('f', NamedTextColor.WHITE)
    );

    private static final java.util.regex.Pattern PLACEHOLDER_PATTERN =
            java.util.regex.Pattern.compile("\\{([a-zA-Z0-9_.-]+)\\}");

    private ColorParser() {
    }

    /**
     * Validates that legacy colour and formatting codes in the text are well-formed.
     * Rejects trailing '&', truncated hex codes, invalid hex digits, and invalid formatting letters.
     */
    public static void validate(String legacyText, String key) {
        if (legacyText == null || legacyText.isEmpty()) {
            return;
        }
        int len = legacyText.length();
        for (int i = 0; i < len; i++) {
            if (legacyText.charAt(i) == '&') {
                if (i == len - 1) {
                    throw new ConfigValidationException(key, "Trailing '&' color code at end of: " + legacyText);
                }
                char next = legacyText.charAt(i + 1);
                if (next == '#') {
                    if (i + 7 >= len) {
                        throw new ConfigValidationException(key, "Truncated hex color code in: " + legacyText);
                    }
                    for (int j = i + 2; j <= i + 7; j++) {
                        char h = legacyText.charAt(j);
                        if (!isHexDigit(h)) {
                            throw new ConfigValidationException(key, "Invalid hex character '" + h + "' in: " + legacyText);
                        }
                    }
                    i += 7;
                } else if (Character.isLetterOrDigit(next)) {
                    char lower = Character.toLowerCase(next);
                    if (!NAMED_COLORS.containsKey(lower)
                            && lower != 'k' && lower != 'l' && lower != 'm' && lower != 'n' && lower != 'o' && lower != 'r') {
                        throw new ConfigValidationException(key, "Invalid color/formatting code '&" + next + "' in: " + legacyText);
                    }
                    i++;
                }
            }
        }
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Parses a legacy formatted string using '&' colour codes and hex codes (&#rrggbb)
     * into an Adventure {@link Component}.
     */
    public static Component parse(String legacyText) {
        if (legacyText == null || legacyText.isEmpty()) {
            return Component.empty();
        }
        return SERIALIZER.deserialize(legacyText);
    }

    /**
     * Renders a message template by parsing fixed spans for colour and appending
     * placeholder values as literal {@link Component#text(String)}, preventing placeholders
     * from injecting formatting or recolouring subsequent spans.
     */
    public static Component renderTemplate(String template, Map<String, String> placeholders) {
        if (template == null || template.isEmpty()) {
            return Component.empty();
        }
        if (placeholders == null || placeholders.isEmpty()) {
            return parse(template);
        }

        java.util.regex.Matcher m = PLACEHOLDER_PATTERN.matcher(template);
        if (!m.find()) {
            return parse(template);
        }

        net.kyori.adventure.text.TextComponent.Builder builder = Component.text();
        net.kyori.adventure.text.format.Style currentStyle = net.kyori.adventure.text.format.Style.empty();
        int cursor = 0;
        m.reset();

        while (m.find()) {
            String spanBefore = template.substring(cursor, m.start());
            currentStyle = appendStyledSpan(builder, spanBefore, currentStyle);

            String token = m.group(1);
            if (placeholders.containsKey(token)) {
                String val = placeholders.get(token);
                if (val != null && !val.isEmpty()) {
                    builder.append(Component.text(val, currentStyle));
                }
            } else {
                builder.append(Component.text(m.group(0), currentStyle));
            }

            cursor = m.end();
        }

        if (cursor < template.length()) {
            String trailingSpan = template.substring(cursor);
            appendStyledSpan(builder, trailingSpan, currentStyle);
        }

        return builder.build();
    }

    private static net.kyori.adventure.text.format.Style appendStyledSpan(
            net.kyori.adventure.text.TextComponent.Builder builder,
            String span,
            net.kyori.adventure.text.format.Style initialStyle
    ) {
        if (span.isEmpty()) {
            return initialStyle;
        }

        net.kyori.adventure.text.format.Style style = initialStyle;
        StringBuilder textBuf = new StringBuilder();
        int len = span.length();

        for (int i = 0; i < len; i++) {
            char c = span.charAt(i);
            if (c == '&' && i + 1 < len) {
                char next = span.charAt(i + 1);
                if (next == '#') {
                    if (i + 7 < len) {
                        String hex = span.substring(i + 2, i + 8);
                        boolean validHex = true;
                        for (int j = 0; j < 6; j++) {
                            if (!isHexDigit(hex.charAt(j))) {
                                validHex = false;
                                break;
                            }
                        }
                        if (validHex) {
                            if (textBuf.length() > 0) {
                                builder.append(Component.text(textBuf.toString(), style));
                                textBuf.setLength(0);
                            }
                            net.kyori.adventure.text.format.TextColor hexColor =
                                    net.kyori.adventure.text.format.TextColor.fromHexString("#" + hex);
                            style = net.kyori.adventure.text.format.Style.style(hexColor);
                            i += 7;
                            continue;
                        }
                    }
                } else {
                    char lower = Character.toLowerCase(next);
                    NamedTextColor namedColor = NAMED_COLORS.get(lower);
                    if (namedColor != null) {
                        if (textBuf.length() > 0) {
                            builder.append(Component.text(textBuf.toString(), style));
                            textBuf.setLength(0);
                        }
                        style = net.kyori.adventure.text.format.Style.style(namedColor);
                        i++;
                        continue;
                    } else if (lower == 'r') {
                        if (textBuf.length() > 0) {
                            builder.append(Component.text(textBuf.toString(), style));
                            textBuf.setLength(0);
                        }
                        style = net.kyori.adventure.text.format.Style.empty();
                        i++;
                        continue;
                    } else if (lower == 'l') {
                        if (textBuf.length() > 0) {
                            builder.append(Component.text(textBuf.toString(), style));
                            textBuf.setLength(0);
                        }
                        style = style.decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);
                        i++;
                        continue;
                    } else if (lower == 'o') {
                        if (textBuf.length() > 0) {
                            builder.append(Component.text(textBuf.toString(), style));
                            textBuf.setLength(0);
                        }
                        style = style.decorate(net.kyori.adventure.text.format.TextDecoration.ITALIC);
                        i++;
                        continue;
                    } else if (lower == 'n') {
                        if (textBuf.length() > 0) {
                            builder.append(Component.text(textBuf.toString(), style));
                            textBuf.setLength(0);
                        }
                        style = style.decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED);
                        i++;
                        continue;
                    } else if (lower == 'm') {
                        if (textBuf.length() > 0) {
                            builder.append(Component.text(textBuf.toString(), style));
                            textBuf.setLength(0);
                        }
                        style = style.decorate(net.kyori.adventure.text.format.TextDecoration.STRIKETHROUGH);
                        i++;
                        continue;
                    } else if (lower == 'k') {
                        if (textBuf.length() > 0) {
                            builder.append(Component.text(textBuf.toString(), style));
                            textBuf.setLength(0);
                        }
                        style = style.decorate(net.kyori.adventure.text.format.TextDecoration.OBFUSCATED);
                        i++;
                        continue;
                    }
                }
            }
            textBuf.append(c);
        }

        if (textBuf.length() > 0) {
            builder.append(Component.text(textBuf.toString(), style));
        }

        return style;
    }

    /**
     * Serializes an Adventure {@link Component} back to an ampersand-formatted legacy string.
     */
    public static String serialize(Component component) {
        if (component == null) {
            return "";
        }
        return SERIALIZER.serialize(component);
    }
}
