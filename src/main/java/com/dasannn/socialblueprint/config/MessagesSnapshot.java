package com.dasannn.socialblueprint.config;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Immutable snapshot of translated messages across active and fallback languages (T-032, T-032a, T-032b).
 * All map references are immutable; no fields are assigned separately during request handling.
 */
public record MessagesSnapshot(
        String activeLanguage,
        String fallbackLanguage,
        Map<String, String> activeMessages,
        Map<String, String> fallbackMessages,
        Map<String, String> bundledActiveMessages,
        Map<String, String> bundledFallbackMessages
) {
    public MessagesSnapshot {
        Objects.requireNonNull(activeLanguage, "activeLanguage must not be null");
        Objects.requireNonNull(fallbackLanguage, "fallbackLanguage must not be null");
        activeMessages = Map.copyOf(activeMessages != null ? activeMessages : Collections.emptyMap());
        fallbackMessages = Map.copyOf(fallbackMessages != null ? fallbackMessages : Collections.emptyMap());
        bundledActiveMessages = Map.copyOf(bundledActiveMessages != null ? bundledActiveMessages : Collections.emptyMap());
        bundledFallbackMessages = Map.copyOf(bundledFallbackMessages != null ? bundledFallbackMessages : Collections.emptyMap());
    }

    /**
     * Resolves the raw translated string for a given key, applying fallback if missing.
     * Logs a warning naming the key ONCE on fallback.
     * Never returns the raw key if completely missing (T-032b).
     */
    public String resolveRaw(String key, Set<String> warnedKeys, Logger logger) {
        Objects.requireNonNull(key, "Message key must not be null");
        // Optional thoughts never fall back to a different language or a bundled list.
        if (CatalogueLines.LEVEL_PHRASES.stream().anyMatch(list -> key.equals(list) || key.startsWith(list + "."))) {
            String phrase = lookup(activeMessages, key);
            return phrase == null ? "" : phrase;
        }


        // 1. Try active language from disk/memory
        String val = lookup(activeMessages, key);
        if (val != null && (!val.isBlank() || key.equals(CatalogueLines.CUSTOM))) {
            return val;
        }

        // 2. Try fallback language from disk/memory
        String fallbackVal = lookup(fallbackMessages, key);
        if (fallbackVal != null && (!fallbackVal.isBlank() || key.equals(CatalogueLines.CUSTOM))) {
            if (warnedKeys.add(key) && logger != null) {
                logger.warning("[SocialBlueprint] Missing translation key '" + key
                        + "' in language '" + activeLanguage + "'; falling back to '" + fallbackLanguage + "'.");
            }
            return fallbackVal;
        }

        // 3. Try bundled active language from jar
        String bundledVal = lookup(bundledActiveMessages, key);
        if (bundledVal != null && (!bundledVal.isBlank() || key.equals(CatalogueLines.CUSTOM))) {
            if (warnedKeys.add(key) && logger != null) {
                logger.warning("[SocialBlueprint] Missing translation key '" + key
                        + "' in disk file; falling back to bundled jar default for '" + activeLanguage + "'.");
            }
            return bundledVal;
        }

        // 4. Try bundled fallback language from jar
        String bundledFallbackVal = lookup(bundledFallbackMessages, key);
        if (bundledFallbackVal != null && (!bundledFallbackVal.isBlank() || key.equals(CatalogueLines.CUSTOM))) {
            if (warnedKeys.add(key) && logger != null) {
                logger.warning("[SocialBlueprint] Missing translation key '" + key
                        + "' in disk file; falling back to bundled jar fallback for '" + fallbackLanguage + "'.");
            }
            return bundledFallbackVal;
        }

        // 5. Completely missing - never show raw key to player (T-032b)
        if (warnedKeys.add(key) && logger != null) {
            logger.severe("[SocialBlueprint] Translation key '" + key + "' not found in any message file!");
        }
        return "";
    }

    public java.util.List<String> lineKeys(String key) {
        String lines = resolveRaw(key, java.util.concurrent.ConcurrentHashMap.newKeySet(), null);
        if (lines.isBlank()) return java.util.List.of();
        return java.util.stream.IntStream.range(0, lines.split("\n", -1).length)
                .mapToObj(index -> key + "." + index).toList();
    }

    private static String lookup(Map<String, String> messages, String key) {
        for (String listKey : CatalogueLines.LISTS) {
            if (key.startsWith(listKey + ".")) {
                String raw = messages.get(listKey);
                if (raw == null) return null;
                try {
                    int index = Integer.parseInt(key.substring(listKey.length() + 1));
                    String[] lines = raw.split("\n", -1);
                    return index >= 0 && index < lines.length ? lines[index] : null;
                } catch (NumberFormatException ignored) { return null; }
            }
        }
        String listKey = key.startsWith("effects.sign.lines.") ? "effects.sign.lines" : ScreenLines.KEY;
        String prefix = listKey + ".";
        if (key.startsWith(prefix)) {
            String lines = messages.get(listKey);
            if (lines == null) return null;
            try {
                int index = Integer.parseInt(key.substring(prefix.length()));
                String[] values = lines.split("\n", -1);
                return index >= 0 && index < values.length ? values[index] : null;
            } catch (NumberFormatException ignored) { return null; }
        }
        return messages.get(key);
    }

    public boolean isKnownKey(String key) {
        return activeMessages.containsKey(key)
                || fallbackMessages.containsKey(key)
                || bundledActiveMessages.containsKey(key)
                || bundledFallbackMessages.containsKey(key);
    }
}
