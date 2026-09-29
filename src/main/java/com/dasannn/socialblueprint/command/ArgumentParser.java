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
