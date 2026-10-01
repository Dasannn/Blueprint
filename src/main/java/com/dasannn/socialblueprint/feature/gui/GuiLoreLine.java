package com.dasannn.socialblueprint.feature.gui;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Plain-data representation of a single line of lore in a GUI item slot.
 * May be a message key with placeholders or a sanitized plain-text string.
 */
public record GuiLoreLine(
        String key,
        Map<String, String> placeholders,
        String plainText
) {
    public GuiLoreLine {
        placeholders = placeholders != null ? Collections.unmodifiableMap(placeholders) : Collections.emptyMap();
    }

    public static GuiLoreLine ofKey(String key) {
        return new GuiLoreLine(Objects.requireNonNull(key, "key must not be null"), Collections.emptyMap(), null);
    }

    public static GuiLoreLine ofKey(String key, Map<String, String> placeholders) {
        return new GuiLoreLine(Objects.requireNonNull(key, "key must not be null"), placeholders, null);
    }

    public static GuiLoreLine ofPlain(String plainText) {
        return new GuiLoreLine(null, Collections.emptyMap(), plainText != null ? plainText : "");
    }

    public boolean isPlain() {
        return plainText != null;
    }
}
