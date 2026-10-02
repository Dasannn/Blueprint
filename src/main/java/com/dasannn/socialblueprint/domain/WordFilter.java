package com.dasannn.socialblueprint.domain;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Pure display transformation: the input and stored reasons are never changed. */
public final class WordFilter {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}_]+");
    private final Set<String> words;

    public WordFilter(Collection<String> words) {
        this.words = words.stream().map(WordFilter::normalize).collect(Collectors.toUnmodifiableSet());
    }

    public static String normalize(String word) {
        return Normalizer.normalize(word, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    public String apply(String text, String replacement) {
        if (text == null || text.isEmpty()) return text;
        var matcher = WORD.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(
                    words.contains(normalize(matcher.group())) ? replacement : matcher.group()));
        }
        return matcher.appendTail(result).toString();
    }
}
