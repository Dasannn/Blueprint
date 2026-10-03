package com.dasannn.socialblueprint.domain;

import java.util.Locale;

/** Mental-state display values with at most one decimal. */
public final class MindNumbers {
    private MindNumbers() {}

    public static String format(double value) {
        String formatted = String.format(Locale.ROOT, "%.1f", value);
        if (formatted.endsWith(".0")) formatted = formatted.substring(0, formatted.length() - 2);
        return formatted.equals("-0") ? "0" : formatted;
    }
}
