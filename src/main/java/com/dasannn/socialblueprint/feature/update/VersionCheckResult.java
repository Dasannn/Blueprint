package com.dasannn.socialblueprint.feature.update;

import java.time.Instant;
import java.util.Objects;

/**
 * Result of a GitHub version check execution per T-080 and T-081.
 */
public record VersionCheckResult(
        VersionComparison comparison,
        String runningVersion,
        String latestVersion,
        ReleaseInfo releaseInfo,
        String errorMessage,
        Instant checkedAt
) {
    public VersionCheckResult {
        Objects.requireNonNull(comparison, "comparison must not be null");
        Objects.requireNonNull(runningVersion, "runningVersion must not be null");
        Objects.requireNonNull(latestVersion, "latestVersion must not be null");
        Objects.requireNonNull(checkedAt, "checkedAt must not be null");
    }

    public static VersionCheckResult unknown(String runningVersion, String errorMessage) {
        return new VersionCheckResult(
                VersionComparison.UNKNOWN,
                runningVersion != null ? runningVersion : "unknown",
                "unknown",
                null,
                errorMessage,
                Instant.now()
        );
    }

    public static VersionCheckResult evaluated(
            VersionComparison comparison,
            String runningVersion,
            String latestVersion,
            ReleaseInfo releaseInfo
    ) {
        return new VersionCheckResult(
                comparison,
                runningVersion,
                latestVersion,
                releaseInfo,
                null,
                Instant.now()
        );
    }
}
