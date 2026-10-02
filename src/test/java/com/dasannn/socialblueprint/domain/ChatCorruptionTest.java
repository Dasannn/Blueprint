package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.Test;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatCorruptionTest {
    private static final String MESSAGE = "Please bring wooden supplies to the village before sunset";
    private static final ChatCorruptionConfig DEFAULT = ChatCorruptionConfig.DEFAULT;

    @Test
    void deterministicExactOutput() {
        var config = new ChatCorruptionConfig(10, 25, 50, 20);
        assertThat(ChatCorruption.corrupt(MESSAGE, PsychosisLevel.EXTREME, 0, 0, config))
                .isEqualTo("Please bring wooden supplies to jbk village before sunset");
        assertThat(ChatCorruption.corrupt(MESSAGE, PsychosisLevel.EXTREME, 0, 2, config))
                .isEqualTo("Please bring wooden supplies to the ivheieg before sunset");
        assertThat(ChatCorruption.corrupt(MESSAGE, PsychosisLevel.EXTREME, 0, 0, config))
                .isEqualTo(ChatCorruption.corrupt(MESSAGE, PsychosisLevel.EXTREME, 0, 0, config));
    }

    @Test
    void ratesIncreaseAndEveryEpisodeHasAnIntactMessageAfterIt() {
        int[] counts = new int[PsychosisLevel.values().length];
        for (PsychosisLevel level : PsychosisLevel.values()) {
            for (int sequence = 0; sequence < 10000; sequence++) {
                String output = ChatCorruption.corrupt(MESSAGE, level, 123, sequence, DEFAULT);
                if (!output.equals(MESSAGE)) counts[level.ordinal()]++;
                if ((sequence & 1) != 0 || !level.hasMadnessEffects()) assertThat(output).isEqualTo(MESSAGE);
            }
        }
        assertThat(counts[PsychosisLevel.SERENITY.ordinal()]).isZero();
        assertThat(counts[PsychosisLevel.NEUTRAL.ordinal()]).isZero();
        assertThat(counts[PsychosisLevel.LOW.ordinal()]).isZero();
        assertThat(counts[PsychosisLevel.MEDIUM.ordinal()]).isBetween(800, 1200);
        assertThat(counts[PsychosisLevel.HIGH.ordinal()]).isBetween(2300, 2700);
        assertThat(counts[PsychosisLevel.EXTREME.ordinal()]).isBetween(3800, 4200);
        assertThat(counts[PsychosisLevel.MEDIUM.ordinal()]).isLessThan(counts[PsychosisLevel.HIGH.ordinal()]);
        assertThat(counts[PsychosisLevel.HIGH.ordinal()]).isLessThan(counts[PsychosisLevel.EXTREME.ordinal()]);
    }

    @Test
    void maximumConfigurationStillPreservesThreeQuartersOfWordsAndLetters() {
        var config = new ChatCorruptionConfig(10, 25, 50, 25);
        for (String input : new String[]{MESSAGE, "meet at home tonight", "aaa bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb ccc ddd",
                "Please BRING wooden supplies to the VILLAGE before sunset", "Trae madera al pueblo antes de anochecer"}) {
            var matcher = Pattern.compile("\\p{L}+").matcher(input);
            int letters = 0;
            while (matcher.find()) letters += input.codePointCount(matcher.start(), matcher.end());
            String[] words = input.split(" ");
            for (int sequence = 0; sequence < 200; sequence++) {
                String output = ChatCorruption.corrupt(input, PsychosisLevel.EXTREME, 123, sequence, config);
                assertThat(output.length()).isEqualTo(input.length());
                assertThat(output).startsWith(words[0] + " ").endsWith(" " + words[words.length - 1]);
                assertThat(output.length()).isEqualTo(input.length());
                int changed = 0;
                for (int i = 0; i < input.length(); i++) if (input.charAt(i) != output.charAt(i)) changed++;
                assertThat(changed).isLessThanOrEqualTo(letters / 4);
                String[] resultWords = output.split(" ");
                int changedWords = 0;
                for (int i = 0; i < words.length; i++) if (!words[i].equals(resultWords[i])) changedWords++;
                assertThat(changedWords).isLessThanOrEqualTo(words.length / 4);
            }
        }
    }

    @Test
    void shortAndUnicodeMessagesStayReadableAtEveryLevel() {
        for (String input : new String[]{"", "hi", "a b c d", "\u4f60\u597d \u4e16\u754c", "e\u0301 a b c"}) {
            for (PsychosisLevel level : PsychosisLevel.values()) {
                for (int sequence = 0; sequence < 100; sequence++) {
                    assertThat(ChatCorruption.corrupt(input, level, 0, sequence,
                            new ChatCorruptionConfig(10, 25, 50, 25))).isEqualTo(input);
                }
            }
        }
    }

    @Test
    void oneTwoAndThreeWordPhrasesCorruptWithinBudgetAndMinimumIsConfigurable() {
        var config = new ChatCorruptionConfig(10, 25, 50, 20, 6);
        for (String input : new String[]{"abcdef", "go home", "help me now", "one extraordinarilylongword three", "\ud83d\ude00 help me now"}) {
            int letters = (int) input.codePoints().filter(Character::isLetter).count();
            for (int sequence = 0; sequence < 20; sequence++) {
                String output = ChatCorruption.corrupt(input, PsychosisLevel.EXTREME, 0, sequence, config);
                assertThat(output.length()).isEqualTo(input.length());
                int changed = 0;
                for (int i = 0; i < input.length(); i++) if (input.charAt(i) != output.charAt(i)) changed++;
                assertThat(changed).isLessThanOrEqualTo(letters * config.extent() / 100);
                if ((sequence & 1) == 0) assertThat(changed).isPositive();
                else assertThat(output).isEqualTo(input);
                String[] words = input.split(" ");
                if (words.length >= 3) assertThat(output).startsWith(words[0] + " ").endsWith(" " + words[words.length - 1]);
            }
            assertThat(ChatCorruption.corrupt(input, PsychosisLevel.EXTREME, 0, 0,
                    new ChatCorruptionConfig(10, 25, 50, 20, letters + 1))).isEqualTo(input);
        }
        assertThat(ChatCorruption.corrupt("hello", PsychosisLevel.EXTREME, 0, 0,
                new ChatCorruptionConfig(10, 25, 50, 20, 1))).isNotEqualTo("hello");
        assertThat(ChatCorruption.corrupt("hello", PsychosisLevel.EXTREME, 0, 0,
                new ChatCorruptionConfig(10, 25, 50, 1, 1))).isEqualTo("hello"); // No letter fits a 1% budget.
        assertThatThrownBy(() -> new ChatCorruptionConfig(10, 25, 50, 20, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void arbitraryLegacyMarkersAndTagSyntaxCannotBecomeFormatting() {
        String input = "Please bring &a &x&1&2&3&4&5&6 \u00a7c <click:run_command:'/op me'>supplies</click> now";
        for (PsychosisLevel level : PsychosisLevel.values()) {
            for (int sequence = 0; sequence < 200; sequence++) {
                String output = ChatCorruption.corrupt(input, level, 123, sequence, DEFAULT);
                assertThat(output).doesNotContain("&", "\u00a7");
                assertThat(output.length()).isEqualTo(input.length());
                String safe = ChatCorruption.plain(input);
                for (int i = 0; i < safe.length(); i++) {
                    if (safe.charAt(i) != output.charAt(i)) {
                        assertThat(Character.toLowerCase(output.charAt(i))).isBetween('a', 'z');
                    }
                }
            }
        }
    }

    @Test
    void validationCannotDisableReadabilityOrQuietMessageGuards() {
        for (int[] values : new int[][]{{0, 25, 40, 20}, {25, 25, 40, 20}, {10, 40, 25, 20},
                {10, 25, 51, 20}, {10, 25, 40, 0}, {10, 25, 40, 26}}) {
            assertThatThrownBy(() -> new ChatCorruptionConfig(values[0], values[1], values[2], values[3]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
