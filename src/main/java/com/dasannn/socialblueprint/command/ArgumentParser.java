package com.dasannn.socialblueprint.command;

import java.util.OptionalInt;

/**
 * Centralised argument parser per T-051 and SB-065.
 * Subcommands never call Integer.parseInt on raw input; malformed arguments
 * produce user-facing messages rather than uncaught stack traces or NumberFormatExceptions.
 */
public final class ArgumentParser {

    private ArgumentParser() {
    }

    public static java.util.OptionalDouble parseMindValue(String input) {
        if (input == null || input.isBlank()) return java.util.OptionalDouble.empty();
        try {
            double value = Double.parseDouble(input);
            return Double.isFinite(value) && value >= -100 && value <= 100
                    ? java.util.OptionalDouble.of(value) : java.util.OptionalDouble.empty();
        } catch (NumberFormatException error) { return java.util.OptionalDouble.empty(); }
    }

    public static java.util.OptionalLong parsePositiveLong(String input) {
        if (input == null || input.isBlank()) return java.util.OptionalLong.empty();
        try {
            long value = Long.parseLong(input.trim());
            return value > 0 ? java.util.OptionalLong.of(value) : java.util.OptionalLong.empty();
        } catch (NumberFormatException error) {
            return java.util.OptionalLong.empty();
        }
    }

    /**
     * Safely parses a strictly positive integer (> 0).
     * Returns empty if the input is null, blank, not a valid integer, or <= 0.
     */
    public static OptionalInt parsePositiveInt(String input) {
        if (input == null || input.isBlank()) {
            return OptionalInt.empty();
        }
        try {
            long val = Long.parseLong(input.trim());
            if (val <= 0 || val > Integer.MAX_VALUE) {
                return OptionalInt.empty();
            }
            return OptionalInt.of((int) val);
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /**
     * Safely parses any integer.
     * Returns empty if the input is null, blank, or not a valid 32-bit integer.
     */
    public static OptionalInt parseInt(String input) {
        if (input == null || input.isBlank()) {
            return OptionalInt.empty();
        }
        try {
            long val = Long.parseLong(input.trim());
            if (val < Integer.MIN_VALUE || val > Integer.MAX_VALUE) {
                return OptionalInt.empty();
            }
            return OptionalInt.of((int) val);
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
