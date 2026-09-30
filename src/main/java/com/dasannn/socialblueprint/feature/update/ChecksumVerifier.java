package com.dasannn.socialblueprint.feature.update;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes and verifies SHA-256 checksums per SB-074 and T-082.
 */
public final class ChecksumVerifier {

    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("(?i)\\b([a-f0-9]{64})\\b");

    private ChecksumVerifier() {}

    /**
     * Converts a byte array to a lowercase hexadecimal string.
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Computes the SHA-256 hexadecimal hash string of the given byte array.
     */
    public static String computeSha256(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return bytesToHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 MessageDigest not available", e);
        }
    }

    /**
     * Verifies that the computed SHA-256 of data matches expectedHash (case-insensitive).
     */
    public static boolean verify(byte[] data, String expectedHash) {
        if (data == null || expectedHash == null || expectedHash.isBlank()) {
            return false;
        }
        String computed = computeSha256(data);
        return computed.equalsIgnoreCase(expectedHash.trim());
    }

    /**
     * Extracts a 64-hex-character SHA-256 hash from text (checksum file or release body),
     * matching the specified asset name when multiple checksums are present.
     */
    public static Optional<String> extractHashFromText(String text, String assetName) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        // 1. If text contains lines and assetName is specified, check the line containing assetName
        // e.g. "e3b0c44...  SocialBlueprint.jar" or "SocialBlueprint.jar: e3b0c44..."
        if (assetName != null && !assetName.isBlank()) {
            String[] lines = text.split("\\r?\\n");
            for (String line : lines) {
                if (line.toLowerCase(Locale.ROOT).contains(assetName.toLowerCase(Locale.ROOT))) {
                    Matcher m = SHA256_HEX_PATTERN.matcher(line);
                    if (m.find()) {
                        return Optional.of(m.group(1).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        // 2. Look for labeled SHA-256 pattern: "SHA256: <hash>" or "sha-256 = <hash>"
        Pattern labeledPattern = Pattern.compile("(?i)sha[-_]?256\\s*[:=]?\\s*([a-f0-9]{64})\\b");
        Matcher labeledMatcher = labeledPattern.matcher(text);
        if (labeledMatcher.find()) {
            return Optional.of(labeledMatcher.group(1).toLowerCase(Locale.ROOT));
        }

        // 3. Fallback: first 64-hex character string found in text
        Matcher generalMatcher = SHA256_HEX_PATTERN.matcher(text);
        if (generalMatcher.find()) {
            return Optional.of(generalMatcher.group(1).toLowerCase(Locale.ROOT));
        }

        return Optional.empty();
    }
}
