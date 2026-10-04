import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/** JDK-only source launcher: java tools/SignRelease.java <command> ... */
public class SignRelease {
    private static final String PBE = "PBEWithHmacSHA256AndAES_256";
    private static final int ITERATIONS = 600_000;

    public static void main(String[] args) {
        try {
            if (args.length != 3) throw new IllegalArgumentException(
                    "Usage: keygen <out-dir> <name> | sign <private-key-file> <jar> | verify <public-key-base64-or-file> <jar>");
            switch (args[0]) {
                case "keygen" -> keygen(Path.of(args[1]), args[2]);
                case "sign" -> sign(Path.of(args[1]), Path.of(args[2]));
                case "verify" -> {
                    if (!verify(args[1], Path.of(args[2]))) System.exit(1);
                    System.out.println("Signature verified.");
                }
                default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
            }
        } catch (Exception e) {
            System.err.println("SignRelease: " + e.getMessage());
            System.exit(1);
        }
    }

    private static char[] password() {
        char[] password;
        if (System.console() != null) {
            password = System.console().readPassword("Signing key password: ");
        } else {
            String value = System.getenv("SB_SIGN_PASSWORD");
            password = value == null ? null : value.toCharArray();
        }
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("A nonempty password is required (console or SB_SIGN_PASSWORD).");
        }
        return password;
    }

    private static SecretKey passwordKey(char[] password, String algorithm) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password);
        try {
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec);
        } finally {
            spec.clearPassword();
        }
    }

    private static void keygen(Path directory, String name) throws Exception {
        if (!name.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Name must contain only letters, digits, _ or -.");
        // Resolve links before checking every ancestor, including worktrees whose .git is a file.
        Files.createDirectories(directory);
        Path realDirectory = directory.toRealPath();
        for (Path ancestor = realDirectory; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Refusing to write a private key inside a git work tree.");
            }
        }
        Path privateFile = realDirectory.resolve(name + ".key");
        Path publicFile = realDirectory.resolve(name + ".pub");
        if (Files.exists(privateFile, LinkOption.NOFOLLOW_LINKS) || Files.exists(publicFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Key files already exist; choose a new name.");
        }
        char[] password = password();
        byte[] privateBytes = null;
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            privateBytes = pair.getPrivate().getEncoded();
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[32];
            byte[] iv = new byte[16];
            random.nextBytes(salt);
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(PBE);
            cipher.init(Cipher.ENCRYPT_MODE, passwordKey(password, PBE),
                    new PBEParameterSpec(salt, ITERATIONS, new IvParameterSpec(iv)));
            // PKCS#8 AlgorithmIdentifier must be PBES2, with the cipher's full KDF/AES parameters.
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("PBES2");
            parameters.init(cipher.getParameters().getEncoded());
            byte[] encrypted = new EncryptedPrivateKeyInfo(parameters, cipher.doFinal(privateBytes)).getEncoded();
            String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            Files.write(privateFile, encrypted, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(publicFile, publicKey + "\n", StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.println(publicKey);
        } finally {
            Arrays.fill(password, '\0');
            if (privateBytes != null) Arrays.fill(privateBytes, (byte) 0);
        }
    }

    private static void sign(Path privateFile, Path jar) throws Exception {
        EncryptedPrivateKeyInfo encrypted = new EncryptedPrivateKeyInfo(Files.readAllBytes(privateFile));
        String algorithm = encrypted.getAlgName();
        if (!"PBES2".equals(algorithm) && !algorithm.matches("PBEWithHmacSHA[0-9]+AndAES_(128|256)")) {
            throw new IllegalArgumentException("Expected a PBES2 encrypted PKCS#8 key.");
        }
        char[] password = password();
        try {
            Cipher cipher = Cipher.getInstance(algorithm);
            cipher.init(Cipher.DECRYPT_MODE, passwordKey(password, algorithm), encrypted.getAlgParameters());
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(encrypted.getKeySpec(cipher));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            update(signer, jar);
            Files.writeString(Path.of(jar + ".sig"), Base64.getEncoder().encodeToString(signer.sign()) + "\n",
                    StandardCharsets.US_ASCII);
            System.out.println("Signed " + jar);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static boolean verify(String keyOrFile, Path jar) throws Exception {
        // A base64 SPKI may contain '/' and need not be a valid filesystem path.
        String encoded = keyOrFile;
        try {
            Path candidate = Path.of(keyOrFile);
            if (Files.isRegularFile(candidate)) encoded = Files.readString(candidate, StandardCharsets.US_ASCII).trim();
        } catch (java.nio.file.InvalidPathException ignored) {}
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(encoded.trim()))));
        Path signatureFile = Path.of(jar + ".sig");
        if (Files.size(signatureFile) > 1024) return false;
        byte[] signature = Base64.getDecoder().decode(Files.readString(signatureFile, StandardCharsets.US_ASCII).trim());
        if (signature.length != 64) return false;
        update(verifier, jar);
        return verifier.verify(signature);
    }

    private static void update(Signature signature, Path jar) throws Exception {
        try (InputStream in = Files.newInputStream(jar)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) signature.update(buffer, 0, read);
        }
    }
}
