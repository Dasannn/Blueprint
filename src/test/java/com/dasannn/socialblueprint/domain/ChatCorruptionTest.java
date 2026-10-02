package com.dasannn.socialblueprint.domain;

import org.junit.jupiter.api.Test;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatCorruptionTest {
    private static final String MESSAGE = "Please bring wooden supplies to the village before sunset";
    private static final ChatCorruptionConfig DEFAULT = ChatCorruptionConfig.DEFAULT;

    @Test
    void deterministicOneResultForAllReaders() {
        var config = new ChatCorruptionConfig(10, 25, 50, 20);
        assertThat(ChatCorruption.corrupt(MESSAGE, PsychosisLevel.EXTREME, 0, 0, config)).isNotEqualTo(MESSAGE);
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
    void maximumConfigurationCanTouchEveryWordButPreservesHalfOfLetters() {
        var config = new ChatCorruptionConfig(10, 25, 50, 50);
        for (String input : new String[]{MESSAGE, "meet at home tonight", "aaa bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb ccc ddd",
                "Please BRING wooden supplies to the VILLAGE before sunset", "Trae madera al pueblo antes de anochecer"}) {
            var matcher = Pattern.compile("\\p{L}+").matcher(input);
            int letters = 0;
            while (matcher.find()) letters += input.codePointCount(matcher.start(), matcher.end());
            String[] words = input.split(" ");
            for (int sequence = 0; sequence < 200; sequence++) {
                String output = ChatCorruption.corrupt(input, PsychosisLevel.EXTREME, 123, sequence, config);
                assertThat(output.length()).isEqualTo(input.length());
                assertThat(output.length()).isEqualTo(input.length());
                int changed = 0;
                for (int i = 0; i < input.length(); i++) if (input.charAt(i) != output.charAt(i)) changed++;
                assertThat(changed).isLessThanOrEqualTo(letters / 2);
                String[] resultWords = output.split(" ");
                int changedWords = 0;
                for (int i = 0; i < words.length; i++) if (!words[i].equals(resultWords[i])) changedWords++;
                if ((sequence & 1) == 0) assertThat(changedWords).isEqualTo(words.length);
                else assertThat(changedWords).isZero();
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
                assertThat(changed).isLessThanOrEqualTo(letters * config.extent(PsychosisLevel.EXTREME) / 100);
                if ((sequence & 1) == 0) assertThat(changed).isPositive();
                else assertThat(output).isEqualTo(input);
                String[] words = input.split(" ");
                String[] resultWords = output.split(" ");
                int touched = 0;
                for (int i = 0; i < words.length; i++) if (!words[i].equals(resultWords[i])) touched++;
                assertThat(touched).isLessThanOrEqualTo(Math.min(words.length,
                        (words.length * config.extent(PsychosisLevel.EXTREME) * 2 + 99) / 100));
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
    void extentsIncreaseAndSpreadTheBudgetAcrossWords() {
        String input = "edge abcdefghij klmnopqrst uvwxyzabcd efghijklmn opqrstuvwx yzabcdefgh ijklmnopqr stuvwxyzab last";
        var config = new ChatCorruptionConfig(10, 25, 50, 20, 35, 50, 6);
        int letters = (int) input.chars().filter(Character::isLetter).count();
        String[] words = input.split(" ");
        int previous = 0;
        for (PsychosisLevel level : new PsychosisLevel[]{PsychosisLevel.MEDIUM, PsychosisLevel.HIGH, PsychosisLevel.EXTREME}) {
            int total = 0;
            for (int sequence = 0; sequence < 1000; sequence += 2) {
                String result = ChatCorruption.corrupt(input, level, 123, sequence, config);
                if (result.equals(input)) continue;
                String[] changedWords = result.split(" ");
                int touched = 0, changed = 0;
                for (int i = 0; i < words.length; i++) if (!words[i].equals(changedWords[i])) touched++;
                for (int i = 0; i < input.length(); i++) if (input.charAt(i) != result.charAt(i)) changed++;
                assertThat(touched).isEqualTo(Math.min(words.length, (words.length * config.extent(level) * 2 + 99) / 100));
                assertThat(changed).isEqualTo(letters * config.extent(level) / 100);
                assertThat(changed).isLessThanOrEqualTo(letters / 2);
                total += changed;
            }
            assertThat(total).isGreaterThan(previous);
            previous = total;
        }
        assertThatThrownBy(() -> new ChatCorruptionConfig(10, 25, 40, 35, 20, 50, 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatCorruptionConfig(10, 25, 40, 20, 50, 35, 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fourWordSentenceSpreadsAcrossTwoThreeAndFourWordsByLevel() {
        String input = "meet at home tonight";
        String[] words = input.split(" ");
        var config = new ChatCorruptionConfig(10, 25, 50, 20, 35, 50, 6);
        int expected = 2;
        for (PsychosisLevel level : new PsychosisLevel[]{PsychosisLevel.MEDIUM, PsychosisLevel.HIGH, PsychosisLevel.EXTREME}) {
            boolean seen = false;
            for (int sequence = 0; sequence < 1000; sequence += 2) {
                if (!ChatCorruption.isEpisode(input, level, 123, sequence, config)) continue;
                seen = true;
                String output = ChatCorruption.corrupt(input, level, 123, sequence, config);
                String[] result = output.split(" ");
                int touched = 0, changed = 0;
                for (int i = 0; i < words.length; i++) if (!words[i].equals(result[i])) touched++;
                for (int i = 0; i < input.length(); i++) if (input.charAt(i) != output.charAt(i)) changed++;
                assertThat(touched).isEqualTo(expected);
                assertThat(changed).isLessThanOrEqualTo(17 * config.extent(level) / 100);
                if (level == PsychosisLevel.EXTREME) {
                    assertThat(result[0]).isNotEqualTo(words[0]);
                    assertThat(result[3]).isNotEqualTo(words[3]);
                }
            }
            assertThat(seen).isTrue();
            expected++;
        }
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
                {10, 25, 51, 20}, {10, 25, 40, 0}, {10, 25, 40, 51}}) {
            assertThatThrownBy(() -> new ChatCorruptionConfig(values[0], values[1], values[2], values[3]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
