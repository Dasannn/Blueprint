package com.dasannn.socialblueprint.domain;

/** Shared plain-data prefix/name layout for chat and vanilla tab. */
public final class PlayerNameFormat {
    private PlayerNameFormat() {}
    public static String prefix(String prefix) { return prefix == null ? "" : prefix; }
    public static String name(String prefix, String name) {
        String token = prefix(prefix);
        return token.isEmpty() ? name : token + " " + name;
    }
}
