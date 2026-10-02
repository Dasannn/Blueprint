package com.dasannn.socialblueprint.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.regex.Pattern;

/** Pure text transformation. No platform objects, storage, clock or unseeded randomness. */
public final class ChatCorruption {
    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz";

    private ChatCorruption() {}

    public static long seed(String message, long speakerSeed, long sequence) {
        long hash = 0xcbf29ce484222325L ^ speakerSeed ^ sequence;
        for (int cp : message.codePoints().toArray()) hash = (hash ^ cp) * 0x100000001b3L;
        return hash;
    }

    public static String plain(String message) {
        // Keep code-looking input literal and harmless even for clients interpreting legacy markers.
        return message.replace('&', '\uff06').replace('\u00a7', '\ufffd');
    }

    public static String corrupt(String message, PsychosisLevel level, long speakerSeed,
                                 long sequence, ChatCorruptionConfig config) {
        if (!isEpisode(message, level, speakerSeed, sequence, config)) return plain(message);
        return corruptEpisode(message, level, speakerSeed, sequence, config);
    }

    public static boolean isEpisode(String message, PsychosisLevel level, long speakerSeed,
                                    long sequence, ChatCorruptionConfig config) {
        if (level.ordinal() < PsychosisLevel.MEDIUM.ordinal() || (sequence & 1) != 0) return false;
        Random random = new Random(seed(message, speakerSeed, sequence));
        int rate = switch (level) {
            case LOW, NEUTRAL, SERENITY -> 0;
            case MEDIUM -> config.mediumRate();
            case HIGH -> config.highRate();
            case EXTREME -> config.extremeRate();
        };
        return random.nextInt(50) < rate;
    }

    /** Transform an already chosen episode; readability can still leave short text intact. */
    public static String corruptEpisode(String message, PsychosisLevel level, long speakerSeed,
                                        long sequence, ChatCorruptionConfig config) {
        String safe = plain(message);
        Random random = new Random(seed(message, speakerSeed, sequence));
        random.nextInt(50); // Advance past the shared episode roll.
        var words = new ArrayList<int[]>();
        var matcher = WORD.matcher(safe);
        int letters = 0;
        while (matcher.find()) {
            words.add(new int[]{matcher.start(), matcher.end()});
            letters += safe.codePointCount(matcher.start(), matcher.end());
        }
        if (words.isEmpty() || letters < config.minLetters()) return safe;
        int budget = (int) ((long) letters * config.extent(level) / 100);
        if (budget == 0) return safe; // No substitution fits the percentage bound.
        int wordBudget = (int) Math.min(words.size(), ((long) words.size() * config.extent(level) * 2 + 99) / 100);
        var candidates = new ArrayList<int[]>(words);
        Collections.shuffle(candidates, random);
        StringBuilder result = new StringBuilder(safe);
        // Select words first, then visit them in rounds so long words cannot monopolise the budget.
        candidates.removeIf(word -> safe.substring(word[0], word[1]).chars()
                .noneMatch(c -> c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'));
        int selected = Math.min(candidates.size(), Math.min(wordBudget, budget));
        int[] positions = new int[selected];
        for (int w = 0; w < selected; w++) positions[w] = candidates.get(w)[0];
        boolean progress = true;
        while (budget > 0 && progress) {
            progress = false;
            for (int w = 0; w < selected && budget > 0; w++) {
                int[] word = candidates.get(w);
                int i = positions[w];
                while (i < word[1] && !((safe.charAt(i) >= 'a' && safe.charAt(i) <= 'z')
                        || (safe.charAt(i) >= 'A' && safe.charAt(i) <= 'Z'))) i++;
                if (i >= word[1]) continue;
                progress = true;
                positions[w] = i + 1;
                char original = safe.charAt(i);
                if (budget > selected - w && budget >= 2 && i + 1 < word[1] && random.nextBoolean()) {
                    char next = safe.charAt(i + 1);
                    if (next != original && ((next >= 'a' && next <= 'z') || (next >= 'A' && next <= 'Z'))) {
                        result.setCharAt(i, next);
                        result.setCharAt(++i, original);
                        positions[w] = i + 1;
                        budget -= 2;
                        continue;
                    }
                }
                char replacement;
                do { replacement = ALPHABET.charAt(random.nextInt(ALPHABET.length())); }
                while (replacement == Character.toLowerCase(original));
                result.setCharAt(i, Character.isUpperCase(original) ? Character.toUpperCase(replacement) : replacement);
                budget--;
            }
        }
        return result.toString();
    }
}
