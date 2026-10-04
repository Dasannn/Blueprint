package com.dasannn.socialblueprint.feature.update;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Immutable JDK-only trust store and verifier; no Bukkit or remote key source. */
public final class SignatureVerifier {
    private final List<PublicKey> keys;

    private SignatureVerifier(List<PublicKey> keys) {
        this.keys = List.copyOf(keys);
    }

    /** Invalid lines are ignored; absent, unreadable or wholly invalid files fail closed. */
    public static SignatureVerifier fromKeyFile(InputStream input) {
        List<PublicKey> keys = new ArrayList<>();
        if (input == null) return new SignatureVerifier(keys);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String encoded = line.split("#", 2)[0].trim();
                if (encoded.isEmpty()) continue;
                try {
                    keys.add(KeyFactory.getInstance("Ed25519").generatePublic(
                            new X509EncodedKeySpec(Base64.getDecoder().decode(encoded))));
                } catch (GeneralSecurityException | IllegalArgumentException ignored) {
                    // A broken key never grants trust to an update.
                }
            }
        } catch (IOException e) {
            return new SignatureVerifier(List.of());
        }
        return new SignatureVerifier(keys);
    }

    public boolean hasTrustedKeys() {
        return !keys.isEmpty();
    }

    /** Detached signature is base64 of exactly 64 Ed25519 signature bytes. */
    public boolean verify(Path jar, String encodedSignature) throws IOException {
        if (keys.isEmpty() || encodedSignature == null) return false;
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(encodedSignature.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (signature.length != 64) return false;
        for (PublicKey key : keys) {
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key);
                try (InputStream in = Files.newInputStream(jar)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) verifier.update(buffer, 0, read);
                }
                if (verifier.verify(signature)) return true;
            } catch (GeneralSecurityException ignored) {
                // Try the backup key too.
            }
        }
        return false;
    }
}
