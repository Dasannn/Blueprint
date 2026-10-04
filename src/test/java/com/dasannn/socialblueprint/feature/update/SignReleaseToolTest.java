package com.dasannn.socialblueprint.feature.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.spec.PBEParameterSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Source-launch the durable tool without adding it to the plugin's production jar. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class SignReleaseToolTest {
    @TempDir Path directory;

    private record Result(int exit, String output) {}

    private Result run(String password, String... arguments) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(java, Path.of("tools/SignRelease.java").toAbsolutePath().toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("SB_SIGN_PASSWORD", password);
        // Redirecting stdout avoids a full pipe blocking the process.
        Path output = Files.createTempFile(directory, "tool-", ".log");
        Process process = builder.redirectOutput(output.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("SignRelease did not finish within 30 seconds");
        }
        return new Result(process.exitValue(), Files.readString(output));
    }

    @Test
    void encryptedKeyRoundTripBothPublicKeyFormsAndFailureExits() throws Exception {
        String password = "test-password-for-generated-key";
        Result generated = run(password, "keygen", directory.toString(), "primary");
        assertThat(generated.exit()).withFailMessage(generated.output()).isZero();
        Path privateKey = directory.resolve("primary.key");
        Path publicKey = directory.resolve("primary.pub");
        String publicBase64 = Files.readString(publicKey).trim();
        assertThat(generated.output().trim()).isEqualTo(publicBase64);
        EncryptedPrivateKeyInfo encrypted = new EncryptedPrivateKeyInfo(Files.readAllBytes(privateKey));
        // JDK 25 names the PBES2 scheme by its concrete PRF and cipher.
        assertThat(encrypted.getAlgName()).isIn("PBES2", "PBEWithHmacSHA256AndAES_256");
        PBEParameterSpec parameters = encrypted.getAlgParameters().getParameterSpec(PBEParameterSpec.class);
        assertThat(parameters.getIterationCount()).isEqualTo(600_000);
        assertThat(parameters.getSalt()).hasSize(32);
        Path jar = directory.resolve("release.jar");
        byte[] bytes = {0, 1, 2, (byte) 255};
        Files.write(jar, bytes);
        Result signed = run(password, "sign", privateKey.toString(), jar.toString());
        assertThat(signed.exit()).withFailMessage(signed.output()).isZero();
        assertThat(run("", "verify", publicKey.toString(), jar.toString()).exit()).isZero();
        assertThat(run("", "verify", publicBase64, jar.toString()).exit()).isZero();
        SignatureVerifier verifier = SignatureVerifier.fromKeyFile(Files.newInputStream(publicKey));
        assertThat(verifier.verify(jar, Files.readString(Path.of(jar + ".sig")))).isTrue();
        byte[] savedKey = Files.readAllBytes(privateKey);
        assertThat(run(password, "keygen", directory.toString(), "primary").exit()).isEqualTo(1);
        assertThat(Files.readAllBytes(privateKey)).isEqualTo(savedKey);
        String savedSignature = Files.readString(Path.of(jar + ".sig"));
        assertThat(run("wrong-password", "sign", privateKey.toString(), jar.toString()).exit()).isEqualTo(1);
        assertThat(Files.readString(Path.of(jar + ".sig"))).isEqualTo(savedSignature);
        Files.write(jar, new byte[]{0, 1, 3, (byte) 255});
        assertThat(run("", "verify", publicKey.toString(), jar.toString()).exit()).isEqualTo(1);
        Files.writeString(Path.of(jar + ".sig"), "garbled!", StandardCharsets.US_ASCII);
        assertThat(run("", "verify", publicKey.toString(), jar.toString()).exit()).isEqualTo(1);
        Files.delete(Path.of(jar + ".sig"));
        assertThat(run("", "verify", publicKey.toString(), jar.toString()).exit()).isEqualTo(1);
    }

    @Test
    void refusesUnencryptedAndNonPbes2PrivateKeys() throws Exception {
        Path jar = directory.resolve("release.jar");
        Files.write(jar, new byte[]{0, 1, 2});
        Path privateKey = directory.resolve("unencrypted.key");
        Files.write(privateKey, KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded());
        Result unencrypted = run("test-password", "sign", privateKey.toString(), jar.toString());
        assertThat(unencrypted.exit()).withFailMessage(unencrypted.output()).isEqualTo(1);
        assertThat(Path.of(jar + ".sig")).doesNotExist();
        Path legacyKey = directory.resolve("legacy.key");
        Files.write(legacyKey, new EncryptedPrivateKeyInfo("PBEWithMD5AndDES", new byte[16]).getEncoded());
        Result legacy = run("test-password", "sign", legacyKey.toString(), jar.toString());
        assertThat(legacy.exit()).isEqualTo(1);
        assertThat(legacy.output()).contains("Expected a PBES2 encrypted PKCS#8 key.");
        assertThat(Path.of(jar + ".sig")).doesNotExist();
    }

    @Test
    void refusesGitDirectoriesWorktreesTraversalAndEmptyPasswords() throws Exception {
        Path repo = Files.createDirectory(directory.resolve("repo"));
        Files.createDirectory(repo.resolve(".git"));
        Path nested = repo.resolve("nested/keys");
        Result refused = run("test-password", "keygen", nested.toString(), "primary");
        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.output()).contains("inside a git work tree");
        assertThat(nested.resolve("primary.key")).doesNotExist();
        Path worktree = Files.createDirectory(directory.resolve("worktree"));
        Files.writeString(worktree.resolve(".git"), "gitdir: /elsewhere");
        assertThat(run("test-password", "keygen", worktree.toString(), "backup").exit()).isEqualTo(1);
        assertThat(worktree.resolve("backup.key")).doesNotExist();
        assertThat(run("test-password", "keygen", directory.toString(), "../escaped").exit()).isEqualTo(1);
        assertThat(run("", "keygen", directory.toString(), "empty-password").exit()).isEqualTo(1);
        assertThat(directory.resolve("empty-password.key")).doesNotExist();
    }
}
