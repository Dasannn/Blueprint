package com.dasannn.socialblueprint.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

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

    private ColorParser() {
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
     * Serializes an Adventure {@link Component} back to an ampersand-formatted legacy string.
     */
    public static String serialize(Component component) {
        if (component == null) {
            return "";
        }
        return SERIALIZER.serialize(component);
    }
}
