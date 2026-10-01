package com.dasannn.socialblueprint.feature.legacy;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Report containing the results of a legacy import operation per T-092.
 * Records how many players were read, imported, skipped and why.
 */
public record LegacyImportReport(
        int readCount,
        int importedCount,
        int skippedCount,
        List<SkippedEntry> skippedEntries
) {
    public LegacyImportReport {
        Objects.requireNonNull(skippedEntries, "skippedEntries must not be null");
        skippedEntries = Collections.unmodifiableList(skippedEntries);
    }

    public enum SkipReason {
        UNRESOLVED_UUID,
        UNVERIFIED_NAME,
        ALREADY_IMPORTED,
        INVALID_SCORE
    }

    public record SkippedEntry(
            String playerName,
            SkipReason reason,
            String details,
            Integer keptScore,
            Integer ignoredScore
    ) {
        public SkippedEntry(String playerName, SkipReason reason, String details) {
            this(playerName, reason, details, null, null);
        }

        public SkippedEntry {
            Objects.requireNonNull(playerName, "playerName must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
