package com.dasannn.socialblueprint.domain;

public final class ReasonRules {
    private ReasonRules() {}

    public static int visibleLength(String reason) {
        return (int) CommentSanitizer.toPlainText(reason).codePoints()
                .filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)
                        && Character.getType(cp) != Character.NON_SPACING_MARK
                        && !Character.isISOControl(cp)).count();
    }

    public static boolean accepts(String reason, int minimum) {
        return reason != null && reason.length() <= ReputationEvent.MAX_REASON_LENGTH
                && visibleLength(reason) >= minimum;
    }
}
