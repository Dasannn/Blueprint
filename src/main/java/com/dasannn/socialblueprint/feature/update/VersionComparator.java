package com.dasannn.socialblueprint.feature.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class VersionComparator {

    private static final Pattern QUALIFIER_SPLIT_PATTERN = Pattern.compile("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)|[.\\-_]+");

    private VersionComparator() {}

    /**
     * Compares the running version against the latest release version per SB-070 and T-081.
     * Returns {@link VersionComparison#UNKNOWN} if either version is null, blank, or unparseable.
     *
     * @param running running version (e.g. "1.0")
     * @param latest  latest release version or tag (e.g. "v1.1")
     * @return comparison result
     */
    public static VersionComparison compare(String running, String latest) {
        if (running == null || running.isBlank() || latest == null || latest.isBlank()) {
            return VersionComparison.UNKNOWN;
        }

        ParsedVersion p1 = parseVersion(running);
        ParsedVersion p2 = parseVersion(latest);
        if (p1 == null || p2 == null) {
            return VersionComparison.UNKNOWN;
        }

        int cmp = p1.compareTo(p2);
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
     * Throws {@link IllegalArgumentException} if either version is unparseable.
     */
    public static int compareVersions(String v1, String v2) {
        ParsedVersion p1 = parseVersion(v1);
        ParsedVersion p2 = parseVersion(v2);
        if (p1 == null || p2 == null) {
            throw new IllegalArgumentException("Cannot compare unparseable versions: '" + v1 + "' and '" + v2 + "'");
        }
        return p1.compareTo(p2);
    }

    private static ParsedVersion parseVersion(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Strip leading 'v' or 'V' only if followed by a digit
        if ((s.startsWith("v") || s.startsWith("V")) && s.length() > 1 && Character.isDigit(s.charAt(1))) {
            s = s.substring(1).trim();
        }

        String core;
        String qualifier;
        int dash = s.indexOf('-');
        int plus = s.indexOf('+');
        int qualStart = -1;
        if (dash >= 0 && plus >= 0) {
            qualStart = Math.min(dash, plus);
        } else if (dash >= 0) {
            qualStart = dash;
        } else if (plus >= 0) {
            qualStart = plus;
        }

        if (qualStart >= 0) {
            core = s.substring(0, qualStart).trim();
            qualifier = s.substring(qualStart + 1).trim();
        } else {
            core = s.trim();
            qualifier = "";
        }

        if (core.isEmpty()) {
            return null;
        }

        String[] parts = core.split("\\.", -1);
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                return null;
            }
            for (int c = 0; c < part.length(); c++) {
                if (!Character.isDigit(part.charAt(c))) {
                    return null;
                }
            }
            try {
                numbers[i] = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return new ParsedVersion(numbers, qualifier);
    }

    private static final class ParsedVersion implements Comparable<ParsedVersion> {
        private final int[] numbers;
        private final String qualifier;

        private ParsedVersion(int[] numbers, String qualifier) {
            this.numbers = Objects.requireNonNull(numbers);
            this.qualifier = Objects.requireNonNull(qualifier);
        }

        @Override
        public int compareTo(ParsedVersion o) {
            int maxLen = Math.max(numbers.length, o.numbers.length);
            for (int i = 0; i < maxLen; i++) {
                int n1 = i < numbers.length ? numbers[i] : 0;
                int n2 = i < o.numbers.length ? o.numbers[i] : 0;
                if (n1 != n2) {
                    return Integer.compare(n1, n2);
                }
            }

            // Numeric parts identical: compare qualifiers
            boolean empty1 = qualifier.isEmpty();
            boolean empty2 = o.qualifier.isEmpty();

            // Final release (empty qualifier) is newer than pre-release (non-empty qualifier)
            if (empty1 && !empty2) {
                return 1;
            }
            if (!empty1 && empty2) {
                return -1;
            }
            if (empty1 && empty2) {
                return 0;
            }

            return compareQualifiers(qualifier, o.qualifier);
        }

        private static int compareQualifiers(String q1, String q2) {
            if (q1.equalsIgnoreCase(q2)) {
                return 0;
            }

            List<String> tokens1 = tokenizeQualifier(q1);
            List<String> tokens2 = tokenizeQualifier(q2);

            int minLen = Math.min(tokens1.size(), tokens2.size());
            for (int i = 0; i < minLen; i++) {
                String t1 = tokens1.get(i);
                String t2 = tokens2.get(i);

                boolean isNum1 = isAllDigits(t1);
                boolean isNum2 = isAllDigits(t2);

                if (isNum1 && isNum2) {
                    try {
                        long num1 = Long.parseLong(t1);
                        long num2 = Long.parseLong(t2);
                        if (num1 != num2) {
                            return Long.compare(num1, num2);
                        }
                    } catch (NumberFormatException ignored) {
                        int c = t1.compareTo(t2);
                        if (c != 0) return c;
                    }
                } else if (isNum1 != isNum2) {
                    // Numeric identifier has lower precedence than alphanumeric identifier
                    return isNum1 ? -1 : 1;
                } else {
                    int c = t1.compareToIgnoreCase(t2);
                    if (c != 0) {
                        return c;
                    }
                }
            }

            return Integer.compare(tokens1.size(), tokens2.size());
        }

        private static List<String> tokenizeQualifier(String q) {
            List<String> tokens = new ArrayList<>();
            String[] parts = QUALIFIER_SPLIT_PATTERN.split(q);
            for (String part : parts) {
                if (!part.isEmpty()) {
                    tokens.add(part);
                }
            }
            return tokens;
        }

        private static boolean isAllDigits(String s) {
            if (s.isEmpty()) return false;
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
    }
}

