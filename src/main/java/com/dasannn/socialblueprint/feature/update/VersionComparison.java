package com.dasannn.socialblueprint.feature.update;

/**
 * Result of comparing running plugin version against the latest GitHub release per SB-070 and T-081.
 */
public enum VersionComparison {
    /**
     * Running version is identical or equal to the latest release version.
     */
    UP_TO_DATE,

    /**
     * Running version is older than the latest release version (update available).
     */
    OUTDATED,

    /**
     * Running version is newer than the latest release version (e.g. dev build or snapshot).
     */
    AHEAD,

    /**
     * Version comparison could not be determined (check not run, hung, or failed).
     */
    UNKNOWN
}
