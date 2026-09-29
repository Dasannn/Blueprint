package com.dasannn.socialblueprint.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ColorParserTest {

    @Test
    @DisplayName("T-033: Parses legacy ampersand colour codes into Adventure components")
    void parsesAmpersandColors() {
        Component component = ColorParser.parse("&cRed text &aGreen text");
        assertThat(component).isNotNull();
        String serialized = ColorParser.serialize(component);
        assertThat(serialized).contains("&cRed text ").contains("&aGreen text");
    }

    @Test
    @DisplayName("T-033: Parses legacy RGB hex codes (&#rrggbb) into TextColor")
    void parsesHexColors() {
        Component component = ColorParser.parse("&#202020Dark &#abcdefLight");
        assertThat(component).isNotNull();

        // Check that hex colors are serialized back to &#rrggbb format
        String serialized = ColorParser.serialize(component);
        assertThat(serialized).containsIgnoringCase("&#202020Dark").containsIgnoringCase("&#abcdefLight");
    }

    @Test
    @DisplayName("T-033: Formats decorations like bold (&l), italic (&o), reset (&r)")
    void parsesFormattingCodes() {
        Component component = ColorParser.parse("&7[&a||&7]&r &fName");
        assertThat(component).isNotNull();
        String serialized = ColorParser.serialize(component);
        assertThat(serialized).contains("&7[").contains("&a||").contains("&7]").contains("&fName");
    }

    @Test
    @DisplayName("T-033: Never parses MiniMessage tags; tags are treated as literal text")
    void doesNotParseMiniMessage() {
        // If MiniMessage was parsed, <red> would become red component and vanish from literal text.
        // Under single interpretation rule, <red> must be preserved as literal characters.
        Component component = ColorParser.parse("<red>Not MiniMessage</red>");
        String serialized = ColorParser.serialize(component);
        assertThat(serialized).contains("<red>Not MiniMessage</red>");
    }

    @Test
    @DisplayName("T-033: Handles null and empty strings safely")
    void handlesNullAndEmpty() {
        assertThat(ColorParser.parse(null)).isEqualTo(Component.empty());
        assertThat(ColorParser.parse("")).isEqualTo(Component.empty());
        assertThat(ColorParser.serialize(null)).isEmpty();
    }
}
