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

    @Test
    @DisplayName("Finding 11: ColorParser.validate rejects malformed color and formatting inputs")
    void validateRejectsMalformedColorInputs() {
        // Trailing &
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ColorParser.validate("Hello &", "key"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("Trailing '&'");

        // Truncated hex
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ColorParser.validate("&#1234", "key"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("Truncated hex");

        // Invalid hex characters
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ColorParser.validate("&#12345z", "key"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("Invalid hex character");

        // Invalid code letter
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ColorParser.validate("&zText", "key"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("Invalid color/formatting code '&z'");

        // Valid inputs pass without exception
        ColorParser.validate("&8[&bSocialBlueprint&8]&r ", "key");
        ColorParser.validate("&#abcdefHex color &lBold &rNormal", "key");
    }

    @Test
    @DisplayName("Finding 8: ColorParser.renderTemplate inserts placeholder values as literal text")
    void renderTemplateRendersPlaceholdersLiterally() {
        String template = "&aWelcome &e{name}&a! Reason: &f{reason}";
        java.util.Map<String, String> placeholders = java.util.Map.of(
                "name", "&cAttacker&#123456",
                "reason", "said {name} to someone"
        );

        Component rendered = ColorParser.renderTemplate(template, placeholders);
        String serialized = ColorParser.serialize(rendered);

        // Name is rendered with yellow style from {name}, literal text &cAttacker&#123456
        assertThat(serialized).contains("&e&cAttacker&#123456");
        // Reason contains literal {name}, NOT recursively substituted
        assertThat(serialized).contains("&fsaid {name} to someone");
    }
}
