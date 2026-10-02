package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.WordFilter;
import org.bukkit.configuration.ConfigurationSection;
import java.util.ArrayList;
import java.util.List;

public record ChatFilterConfig(boolean enabled, WordFilter filter) {
    public static ChatFilterConfig defaults() {
        return new ChatFilterConfig(true, new WordFilter(List.of(
                "idiota", "imbecil", "estupido", "mierda", "puta", "cabron",
                "idiot", "asshole", "bastard", "shit", "fuck", "bitch")));
    }

    public static ChatFilterConfig load(ConfigurationSection root) {
        if (!root.contains("chat-filter")) return defaults();
        if (!root.isBoolean("chat-filter.enabled"))
            throw new ConfigValidationException("chat-filter.enabled", "Must be a boolean");
        List<String> words = new ArrayList<>();
        for (String language : List.of("es", "en")) {
            String key = "chat-filter.words." + language;
            if (!root.isList(key)) throw new ConfigValidationException(key, "Must be a list");
            List<?> list = root.getList(key);
            for (int i = 0; i < list.size(); i++) {
                Object entry = list.get(i);
                if (!(entry instanceof String word) || word.isBlank()
                        || !word.matches("[\\p{L}\\p{M}\\p{N}_]+")
                        || WordFilter.normalize(word).isBlank())
                    throw new ConfigValidationException(key + "[" + i + "]", "Must be a nonempty word");
                words.add(word);
            }
        }
        return new ChatFilterConfig(root.getBoolean("chat-filter.enabled"), new WordFilter(words));
    }

    public String apply(String text, String replacement) {
        return enabled ? filter.apply(text, replacement) : text;
    }
}
