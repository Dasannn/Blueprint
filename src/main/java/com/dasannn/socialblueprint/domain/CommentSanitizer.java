package com.dasannn.socialblueprint.domain;

import java.util.regex.Pattern;

/**
 * Sanitizes written rating reasons to ensure comments are completely inert per T-125:
 * - Length-bounded by {@link ReputationEvent#MAX_REASON_LENGTH}.
 * - Stored as written, and rendered as plain text.
 * - Strips all section sign (§) and ampersand (&) colour and formatting codes (including hex).
 * - Strips any remaining stray formatting codes or XML/MiniMessage tags.
 * - Never produces click or hover actions.
 */
public final class CommentSanitizer {

    // Matches §x(§[0-9a-fA-F]){6} (legacy hex color sequences)
    private static final Pattern SECTION_HEX_PATTERN = Pattern.compile("§x(§[0-9a-fA-F]){6}");
    // Matches §[0-9a-fk-orA-FK-OR] (legacy color and formatting codes)
    private static final Pattern SECTION_COLOR_PATTERN = Pattern.compile("§[0-9a-fA-FK-ORk-or]");

    // Matches &#rrggbb and &x(&[0-9a-fA-F]){6} (ampersand hex sequences)
    private static final Pattern AMP_HEX_PATTERN_1 = Pattern.compile("&#[0-9a-fA-F]{6}");
    private static final Pattern AMP_HEX_PATTERN_2 = Pattern.compile("&x(&[0-9a-fA-F]){6}");
    // Matches &[0-9a-fk-orA-FK-OR] (ampersand color and formatting codes)
    private static final Pattern AMP_COLOR_PATTERN = Pattern.compile("&[0-9a-fA-FK-ORk-or]");

    // Matches MiniMessage / XML tags like <click:...>, <hover:...>, <red>, etc.
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private CommentSanitizer() {
    }

    /**
     * Sanitizes raw reason text into inert, plain text.
     * Strips all formatting, colour codes, and interaction tags.
     */
    public static String toPlainText(String rawReason) {
        if (rawReason == null || rawReason.isBlank()) {
            return "";
        }

        String text = rawReason;
        // Strip section sign formatting codes (both hex and standard)
        text = SECTION_HEX_PATTERN.matcher(text).replaceAll("");
        text = SECTION_COLOR_PATTERN.matcher(text).replaceAll("");

        // Strip ampersand color and formatting codes (both hex and standard)
        text = AMP_HEX_PATTERN_1.matcher(text).replaceAll("");
        text = AMP_HEX_PATTERN_2.matcher(text).replaceAll("");
        text = AMP_COLOR_PATTERN.matcher(text).replaceAll("");

        // Strip any XML/MiniMessage tags that could carry click/hover actions
        text = TAG_PATTERN.matcher(text).replaceAll("");

        // Strip any remaining stray section signs (§) so client never parses them
        text = text.replace("§", "");

        text = text.replaceAll("\\s+", " ").trim();
        return text.length() > ReputationEvent.MAX_REASON_LENGTH
                ? text.substring(0, ReputationEvent.MAX_REASON_LENGTH)
                : text;
    }
}
