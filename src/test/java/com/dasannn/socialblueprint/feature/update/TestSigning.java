package com.dasannn.socialblueprint.feature.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

/** Generated test trust, never shipped as a plugin resource. */
public final class TestSigning {
    private static final KeyPair KEY = newKey();

    private TestSigning() {}

    private static KeyPair newKey() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static InputStream trustedKeys() {
        return new ByteArrayInputStream(Base64.getEncoder().encode(KEY.getPublic().getEncoded()));
    }

    public static String sign(byte[] bytes) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(KEY.getPrivate());
            signer.update(bytes);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static String attachSignature(HttpServer server, String baseUrl, String releaseJson, byte[] jar) {
        JsonObject release = JsonParser.parseString(releaseJson).getAsJsonObject();
        String jarName = release.getAsJsonArray("assets").get(0).getAsJsonObject().get("name").getAsString();
        byte[] signature = sign(jar).getBytes(StandardCharsets.US_ASCII);
        String path = "/signatures/" + jarName + ".sig";
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, signature.length);
            try (var out = exchange.getResponseBody()) { out.write(signature); }
        });
        JsonObject asset = new JsonObject();
        asset.addProperty("name", jarName + ".sig");
        asset.addProperty("browser_download_url", baseUrl + path);
        asset.addProperty("size", signature.length);
        release.getAsJsonArray("assets").add(asset);
        return release.toString();
    }
}
