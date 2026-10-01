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

    /**
     * Parses a delay tick value from an object (String or Number) per T-138.
     * Absent/empty value defaults to 0. Supports tick suffixes (e.g. "4t", "4ticks", "4")
     * and duration units (e.g. "200ms", "1s"). Rejects negative values naming the offending key.
     */
    public static long parseTicks(Object raw, String key) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Number num) {
            long ticks = num.longValue();
            if (ticks < 0) {
                throw new ConfigValidationException(key, "Delay ticks must not be negative, got: " + ticks);
            }
            return ticks;
        }
        if (raw instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) {
                return 0L;
            }
            if (trimmed.startsWith("-")) {
                throw new ConfigValidationException(key, "Delay ticks must not be negative, got: " + str);
            }
            Matcher matcher = UNIT_PATTERN.matcher(trimmed);
            if (matcher.matches()) {
                long amount;
                try {
                    amount = Long.parseLong(matcher.group(1));
                } catch (NumberFormatException e) {
                    throw new ConfigValidationException(key, "Invalid duration numeric value: " + matcher.group(1));
                }
                if (amount < 0) {
                    throw new ConfigValidationException(key, "Delay ticks must not be negative, got: " + str);
                }
                String unit = matcher.group(2).toLowerCase(Locale.ROOT);
                try {
                    return switch (unit) {
                        case "t", "tick", "ticks" -> amount;
                        case "d", "day", "days" -> Math.multiplyExact(amount, 20L * 60 * 60 * 24);
                        case "h", "hr", "hrs", "hour", "hours" -> Math.multiplyExact(amount, 20L * 60 * 60);
                        case "m", "min", "mins", "minute", "minutes" -> Math.multiplyExact(amount, 20L * 60);
                        case "s", "sec", "secs", "second", "seconds" -> Math.multiplyExact(amount, 20L);
                        case "ms", "milli", "millis", "millisecond", "milliseconds" -> amount / 50L;
                        default -> throw new ConfigValidationException(key, "Unknown duration unit '" + unit + "' in: " + str);
                    };
                } catch (ArithmeticException e) {
                    throw new ConfigValidationException(key, "Duration value causes arithmetic overflow: " + str);
                }
            }
            try {
                long amount = Long.parseLong(trimmed);
                if (amount < 0) {
                    throw new ConfigValidationException(key, "Delay ticks must not be negative, got: " + str);
                }
                return amount;
            } catch (NumberFormatException ignored) {
                // Not a plain number
            }
            throw new ConfigValidationException(key, "Invalid delay tick format: '" + str + "'");
        }
        throw new ConfigValidationException(key, "Invalid delay tick value: " + raw);
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
            try {
                return switch (unit) {
                    case "d", "day", "days" -> Duration.ofDays(amount);
                    case "h", "hr", "hrs", "hour", "hours" -> Duration.ofHours(amount);
                    case "m", "min", "mins", "minute", "minutes" -> Duration.ofMinutes(amount);
                    case "s", "sec", "secs", "second", "seconds" -> Duration.ofSeconds(amount);
                    case "ms", "milli", "millis", "millisecond", "milliseconds" -> Duration.ofMillis(amount);
                    case "t", "tick", "ticks" -> Duration.ofMillis(Math.multiplyExact(amount, 50L));
                    default -> throw new ConfigValidationException(key, "Unknown duration unit '" + unit + "' in: " + raw);
                };
            } catch (ArithmeticException e) {
                throw new ConfigValidationException(key, "Duration value causes arithmetic overflow: " + raw);
            }
        }

        // Plain number without unit defaults to seconds
        try {
            long amount = Long.parseLong(trimmed);
            if (amount < 0) {
                throw new ConfigValidationException(key, "Duration must not be negative, got: " + raw);
            }
            try {
                return Duration.ofSeconds(amount);
            } catch (ArithmeticException e) {
                throw new ConfigValidationException(key, "Duration value causes arithmetic overflow: " + raw);
            }
        } catch (NumberFormatException ignored) {
            // Not a plain number
        }

        throw new ConfigValidationException(key, "Invalid duration format: '" + raw + "'. Expected e.g. 30d, 24h, 1h, 30m, 45s");
    }
}
