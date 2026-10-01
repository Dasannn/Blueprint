package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CommentSanitizerTest {

    @Test
    @DisplayName("T-125: Strips legacy '&' color and formatting codes")
    void stripsLegacyAmpersandCodes() {
        String input = "&a&lHelpful &r&cplayer &6in &4the &ksecret &mbase &oalways";
        String sanitized = CommentSanitizer.toPlainText(input);

        assertThat(sanitized).isEqualTo("Helpful player in the secret base always");
        assertThat(sanitized).doesNotContain("&");
    }

    @Test
    @DisplayName("T-125: Strips section sign '§' color and formatting codes")
    void stripsSectionSignCodes() {
        String input = "§c§lToxic §rbehavior §4during §kcombat §oencounter";
        String sanitized = CommentSanitizer.toPlainText(input);

        assertThat(sanitized).isEqualTo("Toxic behavior during combat encounter");
        assertThat(sanitized).doesNotContain("§");
    }

    @Test
    @DisplayName("T-125: Strips hex '&#' color codes")
    void stripsHexColorCodes() {
        String input = "&#ff0000Bright &#123456Hex Color Note";
        String sanitized = CommentSanitizer.toPlainText(input);

        assertThat(sanitized).isEqualTo("Bright Hex Color Note");
    }

    @Test
    @DisplayName("T-125: Strips MiniMessage tags including hover and click events")
    void stripsMiniMessageTagsAndEvents() {
        String input = "<red><bold>Warning</bold></red> <hover:show_text:'malicious'><click:run_command:'/op'>Click here</click></hover>";
        String sanitized = CommentSanitizer.toPlainText(input);

        assertThat(sanitized).isEqualTo("Warning Click here");
        assertThat(sanitized).doesNotContain("<").doesNotContain(">");
    }

    @Test
    @DisplayName("T-125: Test with reason full of '&' codes and a '§' character")
    void complexReasonWithBothAmpersandAndSectionCodes() {
        // Explicit requirement from prompt: "Test with a reason full of & codes and a § character."
        String hostileInput = "&4&lRaid &cleader §4griefed &6our &#abcdefbase &rcompletely &kxyz &o!";
        String sanitized = CommentSanitizer.toPlainText(hostileInput);

        assertThat(sanitized).isEqualTo("Raid leader griefed our base completely xyz !");
        assertThat(sanitized).doesNotContain("&4").doesNotContain("&l").doesNotContain("§4").doesNotContain("&#abcdef");
    }

    @Test
    @DisplayName("T-125: Reason length is bounded to ReputationEvent.MAX_REASON_LENGTH (100 characters)")
    void lengthIsBoundedToMaxReasonLength() {
        assertThat(ReputationEvent.MAX_REASON_LENGTH).isEqualTo(100);

        String longComment = "A".repeat(150);
        String sanitized = CommentSanitizer.toPlainText(longComment);

        assertThat(sanitized).hasSize(100);
        assertThat(sanitized).isEqualTo("A".repeat(100));
    }

    @Test
    @DisplayName("T-125: Stripping codes before length bounding prevents hidden payload overflow")
    void lengthBoundingAppliesAfterStripping() {
        // 10 color codes followed by 100 characters = 120 raw chars
        String input = "&a&b&c&d&e&1&2&3&4&5" + "B".repeat(105);
        String sanitized = CommentSanitizer.toPlainText(input);

        assertThat(sanitized).hasSize(100);
        assertThat(sanitized).isEqualTo("B".repeat(100));
    }

    @Test
    @DisplayName("T-125: Handles null, empty, and whitespace-only reasons safely")
    void handlesEmptyAndNullSafely() {
        assertThat(CommentSanitizer.toPlainText(null)).isEmpty();
        assertThat(CommentSanitizer.toPlainText("")).isEmpty();
        assertThat(CommentSanitizer.toPlainText("   ")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "&0", "&1", "&2", "&3", "&4", "&5", "&6", "&7", "&8", "&9",
            "&a", "&b", "&c", "&d", "&e", "&f", "&k", "&l", "&m", "&n", "&o", "&r",
            "§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9",
            "§a", "§b", "§c", "§d", "§e", "§f", "§k", "§l", "§m", "§n", "§o", "§r"
    })
    @DisplayName("T-125: All individual Minecraft color/format codes are stripped")
    void allIndividualCodesAreStripped(String code) {
        String test = "Text " + code + "Styled";
        assertThat(CommentSanitizer.toPlainText(test)).isEqualTo("Text Styled");
    }

    @Test
    @DisplayName("Finding 5: Strips Unicode format controls, bidi overrides (U+202E), and zero-width spaces")
    void stripsUnicodeFormatControlsAndBidiOverrides() {
        String hostileInput = "trusted\u202E\u200Bevil_action\u200E";
        String sanitized = CommentSanitizer.toPlainText(hostileInput);

        assertThat(sanitized).isEqualTo("trustedevil_action");
        assertThat(sanitized).doesNotContain("\u202E").doesNotContain("\u200B").doesNotContain("\u200E");
    }

    @Test
    @DisplayName("Finding 5: Reason consisting only of tags or format controls sanitizes to empty string")
    void onlyTagsSanitizesToEmptyString() {
        String tagOnly = "<bold><red></red></bold>\u202E\u200B";
        String sanitized = CommentSanitizer.toPlainText(tagOnly);

        assertThat(sanitized).isEmpty();
    }
}
