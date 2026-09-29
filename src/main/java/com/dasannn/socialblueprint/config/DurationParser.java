package com.dasannn.socialblueprint.config;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses duration strings (e.g. "30d", "24h", "1h", "30m", "45s", "PT24H")
 * for configuration fields per T-031.
 * Validates that durations are strictly positive or non-negative and names the offending key on failure.
 */
public final class DurationParser {

    private static final Pattern UNIT_PATTERN = Pattern.compile("^([+-]?\\d+)\\s*([a-zA-Z]+)$");

    private DurationParser() {
    }

    /**
     * Parses a duration string, enforcing that it must be strictly positive (> 0).
     */
    public static Duration parsePositive(String raw, String key) {
        Duration duration = parseInternal(raw, key);
        if (duration.isNegative() || duration.isZero()) {
            throw new ConfigValidationException(key, "Duration must be strictly positive (> 0), got: " + raw);
        }
        return duration;
    }

    /**
     * Parses a duration string, enforcing that it must be non-negative (>= 0).
     * Rejects negative cooldowns naming the offending key per T-031.
     */
    public static Duration parseNonNegative(String raw, String key) {
        Duration duration = parseInternal(raw, key);
        if (duration.isNegative()) {
            throw new ConfigValidationException(key, "Duration must not be negative, got: " + raw);
        }
        return duration;
    }

    private static Duration parseInternal(String raw, String key) {
        if (raw == null || raw.isBlank()) {
            throw new ConfigValidationException(key, "Duration value must not be empty or null");
        }
        String trimmed = raw.trim();

        // Check if raw is negative integer directly (e.g. "-5")
        if (trimmed.startsWith("-")) {
            throw new ConfigValidationException(key, "Duration must not be negative, got: " + raw);
        }

        // Try standard ISO-8601 duration first (e.g. PT24H, P30D)
        if (trimmed.startsWith("P") || trimmed.startsWith("p")) {
            try {
                return Duration.parse(trimmed.toUpperCase(Locale.ROOT));
            } catch (DateTimeParseException e) {
                throw new ConfigValidationException(key, "Malformed ISO-8601 duration format: " + raw);
            }
        }

        // Try unit format (e.g. 30d, 24h, 60m, 30s)
        Matcher matcher = UNIT_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                throw new ConfigValidationException(key, "Invalid duration numeric value: " + matcher.group(1));
            }

            if (amount < 0) {
                throw new ConfigValidationException(key, "Duration must not be negative, got: " + raw);
            }

            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            return switch (unit) {
                case "d", "day", "days" -> Duration.ofDays(amount);
                case "h", "hr", "hrs", "hour", "hours" -> Duration.ofHours(amount);
                case "m", "min", "mins", "minute", "minutes" -> Duration.ofMinutes(amount);
                case "s", "sec", "secs", "second", "seconds" -> Duration.ofSeconds(amount);
                case "ms", "milli", "millis", "millisecond", "milliseconds" -> Duration.ofMillis(amount);
                default -> throw new ConfigValidationException(key, "Unknown duration unit '" + unit + "' in: " + raw);
            };
        }

        // Plain number without unit defaults to seconds
        try {
            long amount = Long.parseLong(trimmed);
            if (amount < 0) {
                throw new ConfigValidationException(key, "Duration must not be negative, got: " + raw);
            }
            return Duration.ofSeconds(amount);
        } catch (NumberFormatException ignored) {
            // Not a plain number
        }

        throw new ConfigValidationException(key, "Invalid duration format: '" + raw + "'. Expected e.g. 30d, 24h, 1h, 30m, 45s");
    }
}
