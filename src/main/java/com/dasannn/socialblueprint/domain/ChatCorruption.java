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
        String safe = plain(message);
        if (level == PsychosisLevel.LOW || (sequence & 1) != 0) return safe;
        Random random = new Random(seed(message, speakerSeed, sequence));
        int rate = switch (level) {
            case LOW -> 0;
            case MEDIUM -> config.mediumRate();
            case HIGH -> config.highRate();
            case EXTREME -> config.extremeRate();
        };
        if (random.nextInt(50) >= rate) return safe;
        var words = new ArrayList<int[]>();
        var matcher = WORD.matcher(safe);
        int letters = 0;
        while (matcher.find()) {
            words.add(new int[]{matcher.start(), matcher.end()});
            letters += safe.codePointCount(matcher.start(), matcher.end());
        }
        if (words.size() < 4 || letters < 12) return safe;
        int budget = letters * config.extent() / 100;
        int wordBudget = Math.max(1, words.size() * config.extent() / 100);
        // Protect first/last words and at least three quarters of all words/letters.
        var candidates = new ArrayList<int[]>(words.subList(1, words.size() - 1));
        Collections.shuffle(candidates, random);
        StringBuilder result = new StringBuilder(safe);
        for (int[] word : candidates) {
            if (wordBudget-- <= 0 || budget <= 0) break;
            for (int i = word[0]; i < word[1] && budget > 0; i++) {
                char original = safe.charAt(i);
                if (original < 'a' || original > 'z') {
                    if (original < 'A' || original > 'Z') continue;
                }
                if (budget >= 2 && i + 1 < word[1] && random.nextBoolean()) {
                    char next = safe.charAt(i + 1);
                    if (next != original && ((next >= 'a' && next <= 'z') || (next >= 'A' && next <= 'Z'))) {
                        result.setCharAt(i, next);
                        result.setCharAt(++i, original);
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
