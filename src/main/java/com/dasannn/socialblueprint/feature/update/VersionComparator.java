package com.dasannn.socialblueprint.feature.update;

public final class VersionComparator {

    private VersionComparator() {}

    /**
     * Compares the running version against the latest release version per SB-070 and T-081.
     *
     * @param running running version (e.g. "1.0")
     * @param latest  latest release version or tag (e.g. "v1.1")
     * @return comparison result
     */
    public static VersionComparison compare(String running, String latest) {
        if (running == null || running.isBlank() || latest == null || latest.isBlank()) {
            return VersionComparison.UNKNOWN;
        }

        int cmp = compareVersions(running, latest);
        if (cmp < 0) {
            return VersionComparison.OUTDATED;
        } else if (cmp > 0) {
            return VersionComparison.AHEAD;
        } else {
            return VersionComparison.UP_TO_DATE;
        }
    }

    /**
     * Compares two version strings. Returns negative if v1 < v2, zero if v1 == v2, positive if v1 > v2.
     */
    public static int compareVersions(String v1, String v2) {
        String clean1 = cleanVersion(v1);
        String clean2 = cleanVersion(v2);

        if (clean1.equalsIgnoreCase(clean2)) {
            return 0;
        }

        String[] parts1 = splitQualifier(clean1);
        String[] parts2 = splitQualifier(clean2);

        String[] nums1 = parts1[0].split("\\.");
        String[] nums2 = parts2[0].split("\\.");

        int maxLen = Math.max(nums1.length, nums2.length);
        for (int i = 0; i < maxLen; i++) {
            int n1 = i < nums1.length ? parseNumber(nums1[i]) : 0;
            int n2 = i < nums2.length ? parseNumber(nums2[i]) : 0;
            if (n1 != n2) {
                return Integer.compare(n1, n2);
            }
        }

        // Numeric parts are identical: compare qualifiers
        String q1 = parts1[1];
        String q2 = parts2[1];

        // An empty qualifier (final release) is newer than a non-empty qualifier (pre-release/snapshot)
        // e.g. "1.0" > "1.0-SNAPSHOT"
        if (q1.isEmpty() && !q2.isEmpty()) {
            return 1;
        }
        if (!q1.isEmpty() && q2.isEmpty()) {
            return -1;
        }

        return q1.compareToIgnoreCase(q2);
    }

    private static String cleanVersion(String v) {
        String s = v.trim();
        if ((s.startsWith("v") || s.startsWith("V")) && s.length() > 1 && Character.isDigit(s.charAt(1))) {
            s = s.substring(1).trim();
        }
        return s;
    }

    private static String[] splitQualifier(String v) {
        int dash = v.indexOf('-');
        if (dash >= 0) {
            return new String[]{v.substring(0, dash), v.substring(dash + 1)};
        }
        int plus = v.indexOf('+');
        if (plus >= 0) {
            return new String[]{v.substring(0, plus), v.substring(plus + 1)};
        }
        return new String[]{v, ""};
    }

    private static int parseNumber(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
