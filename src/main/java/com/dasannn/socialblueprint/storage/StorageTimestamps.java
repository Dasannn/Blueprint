package com.dasannn.socialblueprint.storage;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Objects;

/**
 * Fixed-width UTC timestamp formatter and parser for SQLite storage.
 * Ensures consistent 30-character lexicographical sorting (nanosecond precision)
 * so SQLite text comparisons match chronological order at sub-second boundaries.
 */
final class StorageTimestamps {

    private static final DateTimeFormatter FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    private StorageTimestamps() {}

    public static String format(Instant instant) {
        Objects.requireNonNull(instant, "Instant must not be null");
        return FORMATTER.format(instant);
    }

    public static Instant parse(String text) {
        Objects.requireNonNull(text, "Timestamp text must not be null");
        return Instant.parse(text);
    }
}
