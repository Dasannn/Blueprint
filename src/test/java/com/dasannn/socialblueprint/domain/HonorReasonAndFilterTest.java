package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class HonorReasonAndFilterTest {
    @Test void reasonsCountVisibleCodePointsRatherThanFormattingOrWhitespace() {
        for (String reason : List.of("", "  ", "-", "ab", "&a<red>ab", "\u200B\u202E"))
            assertThat(ReasonRules.accepts(reason, 3)).isFalse();
        assertThat(ReasonRules.accepts(null, 3)).isFalse();
        assertThat(ReasonRules.accepts("&a<red>abc</red>", 3)).isTrue();
        assertThat(ReasonRules.visibleLength("a\u0301 b c")).isEqualTo(3);
        assertThat(ReasonRules.visibleLength("😀ab")).isEqualTo(3);
        assertThat(ReasonRules.accepts("abcd", 5)).isFalse();
        assertThat(ReasonRules.accepts("a".repeat(101), 3)).isFalse();
    }

    @Test void filterUsesBothLanguagesCaseAccentsWholeWordsAndPunctuation() {
        WordFilter filter = new WordFilter(List.of("imbecil", "idiot", "cabron"));
        String input = "¡IMBÉCIL! idiot, CaBrÓn; imbe\u0301cil. idiotic xidiot idiot_x 2idiot";
        assertThat(filter.apply(input, "bobba"))
                .isEqualTo("¡bobba! bobba, bobba; bobba. idiotic xidiot idiot_x 2idiot");
        assertThat(input).contains("IMBÉCIL");
        assertThat(filter.apply("idiot", "$1\\literal")).isEqualTo("$1\\literal");
        assertThat(new WordFilter(List.of()).apply(input, "bobba")).isEqualTo(input);
    }
}
