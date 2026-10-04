package com.dasannn.socialblueprint.feature.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureVerifierTest {
    @TempDir Path directory;

    private static String publicKey(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static String sign(KeyPair pair, byte[] bytes) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(pair.getPrivate());
        signature.update(bytes);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static SignatureVerifier keys(String text) {
        return SignatureVerifier.fromKeyFile(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void exactBytesAndBackupAcceptedButTamperingAndUnknownKeyRejected() throws Exception {
        KeyPair primary = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair backup = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair unknown = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SignatureVerifier verifier = keys("# primary\n" + publicKey(primary) + "\n# backup\n" + publicKey(backup) + "\n");
        byte[] bytes = {0, 1, 2, (byte) 255};
        Path jar = directory.resolve("release.jar");
        Files.write(jar, bytes);
        String valid = sign(primary, bytes);
        assertThat(verifier.verify(jar, valid + "\n")).isTrue();
        assertThat(verifier.verify(jar, sign(backup, bytes))).isTrue();
        assertThat(verifier.verify(jar, sign(unknown, bytes))).isFalse();
        bytes[1] ^= 1;
        Files.write(jar, bytes);
        assertThat(verifier.verify(jar, valid)).isFalse();
    }

    @Test
    void missingEmptyGarbledOrUnreadableKeyFileFailsClosed() throws Exception {
        Path jar = directory.resolve("release.jar");
        Files.writeString(jar, "jar bytes");
        for (String text : new String[]{"", "# placeholders only\n", "not base64!\n", "YWJj\n"}) {
            SignatureVerifier verifier = keys(text);
            assertThat(verifier.hasTrustedKeys()).isFalse();
            assertThat(verifier.verify(jar, TestSigning.sign(Files.readAllBytes(jar)))).isFalse();
        }
        assertThat(SignatureVerifier.fromKeyFile(null).hasTrustedKeys()).isFalse();
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("unreadable"); }
        };
        assertThat(SignatureVerifier.fromKeyFile(broken).hasTrustedKeys()).isFalse();
    }

    @Test
    void missingMalformedOrWrongLengthSignatureRejected() throws Exception {
        SignatureVerifier verifier = SignatureVerifier.fromKeyFile(TestSigning.trustedKeys());
        Path jar = directory.resolve("release.jar");
        Files.writeString(jar, "jar bytes");
        for (String signature : new String[]{null, "", "garbled!", "YWJj", "A".repeat(88)}) {
            assertThat(verifier.verify(jar, signature)).isFalse();
        }
    }

    @Test
    void invalidKeyLineDoesNotHideValidBackup() throws Exception {
        SignatureVerifier verifier = SignatureVerifier.fromKeyFile(new ByteArrayInputStream(
                ("garbled!\n" + new String(TestSigning.trustedKeys().readAllBytes(), StandardCharsets.US_ASCII)
                        + " # backup\n").getBytes(StandardCharsets.US_ASCII)));
        Path jar = directory.resolve("release.jar");
        Files.writeString(jar, "jar bytes");
        assertThat(verifier.verify(jar, TestSigning.sign(Files.readAllBytes(jar)))).isTrue();
    }
}
