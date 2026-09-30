package com.dasannn.socialblueprint.feature.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChecksumVerifierTest {

    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String HELLO_WORLD_SHA256 = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";

    @Test
    @DisplayName("T-082 / SB-074: computeSha256 computes standard SHA-256 hex string")
    void testComputeSha256() {
        assertThat(ChecksumVerifier.computeSha256(new byte[0])).isEqualTo(EMPTY_SHA256);

        byte[] helloBytes = "hello world".getBytes(StandardCharsets.UTF_8);
        assertThat(ChecksumVerifier.computeSha256(helloBytes)).isEqualTo(HELLO_WORLD_SHA256);
    }

    @Test
    @DisplayName("T-082 / SB-074: verify returns true for matching hashes (case-insensitive)")
    void testVerifyMatching() {
        byte[] helloBytes = "hello world".getBytes(StandardCharsets.UTF_8);
        assertThat(ChecksumVerifier.verify(helloBytes, HELLO_WORLD_SHA256)).isTrue();
        assertThat(ChecksumVerifier.verify(helloBytes, HELLO_WORLD_SHA256.toUpperCase())).isTrue();
        assertThat(ChecksumVerifier.verify(helloBytes, "  " + HELLO_WORLD_SHA256 + " \n")).isTrue();
    }

    @Test
    @DisplayName("T-082 / DoD 2: verify returns false for mismatched or corrupt hashes")
    void testVerifyMismatched() {
        byte[] helloBytes = "hello world".getBytes(StandardCharsets.UTF_8);
        // Alter single character in hash
        String badHash = HELLO_WORLD_SHA256.substring(0, 63) + (HELLO_WORLD_SHA256.endsWith("0") ? "1" : "0");
        assertThat(ChecksumVerifier.verify(helloBytes, badHash)).isFalse();

        // Corrupted payload bytes
        byte[] corruptBytes = "hello world!".getBytes(StandardCharsets.UTF_8);
        assertThat(ChecksumVerifier.verify(corruptBytes, HELLO_WORLD_SHA256)).isFalse();

        // Null or blank expected hash
        assertThat(ChecksumVerifier.verify(helloBytes, null)).isFalse();
        assertThat(ChecksumVerifier.verify(helloBytes, "")).isFalse();
        assertThat(ChecksumVerifier.verify(helloBytes, "   ")).isFalse();
    }

    @Test
    @DisplayName("T-082: extractHashFromText extracts SHA-256 from single hash string or companion file")
    void testExtractFromCompanionFile() {
        String shaFileContent = HELLO_WORLD_SHA256 + "\n";
        Optional<String> hash = ChecksumVerifier.extractHashFromText(shaFileContent, "SocialBlueprint.jar");
        assertThat(hash).isPresent().contains(HELLO_WORLD_SHA256);
    }

    @Test
    @DisplayName("T-082: extractHashFromText extracts specific asset hash when multiple files are in checksum list")
    void testExtractMultiFileChecksums() {
        String multiFile = """
                e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  OtherPlugin.jar
                b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9  SocialBlueprint.jar
                1111111111111111111111111111111111111111111111111111111111111111  ThirdPlugin.jar
                """;

        Optional<String> hash = ChecksumVerifier.extractHashFromText(multiFile, "SocialBlueprint.jar");
        assertThat(hash).isPresent().contains(HELLO_WORLD_SHA256);
    }

    @Test
    @DisplayName("T-082: extractHashFromText extracts labeled SHA256 from release description body")
    void testExtractFromReleaseBody() {
        String body1 = "Release notes for 1.1\n\nSHA256: " + HELLO_WORLD_SHA256 + "\nEnjoy!";
        assertThat(ChecksumVerifier.extractHashFromText(body1, "SocialBlueprint-1.1.jar"))
                .isPresent().contains(HELLO_WORLD_SHA256);

        String body2 = "sha-256 = " + HELLO_WORLD_SHA256;
        assertThat(ChecksumVerifier.extractHashFromText(body2, "SocialBlueprint.jar"))
                .isPresent().contains(HELLO_WORLD_SHA256);
    }

    @Test
    @DisplayName("T-082: extractHashFromText returns empty when no SHA-256 is found")
    void testExtractNotFound() {
        assertThat(ChecksumVerifier.extractHashFromText("No hash here", "SocialBlueprint.jar")).isEmpty();
        assertThat(ChecksumVerifier.extractHashFromText("", "SocialBlueprint.jar")).isEmpty();
        assertThat(ChecksumVerifier.extractHashFromText(null, "SocialBlueprint.jar")).isEmpty();
        assertThat(ChecksumVerifier.extractHashFromText("md5: 0123456789abcdef0123456789abcdef", "SocialBlueprint.jar")).isEmpty();
    }
}
