package com.subhub.app.pack;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Optional merchant-only transfer. No payer tokens, verification, or payment authority. */
public final class PackPayPalCipher {
    public static final int ITERATIONS = 600_000;
    private static final int MAX_CIPHERTEXT = 8192;
    private static final String ALGORITHM = "AES-256-GCM";
    private static final String KDF = "PBKDF2-HMAC-SHA256";

    private PackPayPalCipher() {}

    public static JSONObject encrypt(String id, String origin, Payload payload, char[] password)
            throws GeneralSecurityException {
        byte[] plain = null;
        try {
            checkPassword(password);
            payload.validate();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(1);
                out.writeUTF(payload.environment);
                out.writeUTF(payload.clientId);
                out.writeUTF(payload.secret);
                out.writeUTF(payload.recipientLink);
            }
            plain = bytes.toByteArray();
            byte[] salt = new byte[16];
            byte[] nonce = new byte[12];
            SecureRandom random = new SecureRandom();
            random.nextBytes(salt);
            random.nextBytes(nonce);
            byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, id, origin, password, salt, nonce, plain);
            return new JSONObject().put("version", 1).put("algorithm", ALGORITHM)
                    .put("kdf", KDF).put("iterations", ITERATIONS)
                    .put("salt", encode(salt)).put("nonce", encode(nonce))
                    .put("ciphertext", encode(encrypted));
        } catch (Exception ignored) {
            throw failure();
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    public static Payload decrypt(String id, String origin, JSONObject envelope, char[] password)
            throws GeneralSecurityException {
        byte[] plain = null;
        try {
            checkPassword(password);
            validateEnvelope(envelope);
            plain = crypt(Cipher.DECRYPT_MODE, id, origin, password,
                    decode(envelope.getString("salt")), decode(envelope.getString("nonce")),
                    decode(envelope.getString("ciphertext")));
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plain))) {
                if (input.readInt() != 1) throw failure();
                Payload payload = new Payload(input.readUTF(), input.readUTF(),
                        input.readUTF(), input.readUTF());
                if (input.available() != 0) { payload.close(); throw failure(); }
                return payload;
            }
        } catch (Exception ignored) {
            // Never propagate parser/provider messages containing decrypted values.
            throw failure();
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    /** Cheap structural validation only. Untrusted files never trigger password derivation. */
    public static void validateEnvelope(JSONObject value) throws GeneralSecurityException {
        try {
            if (value == null || value.length() != 7 || value.toString().length() > 12000
                    || !Integer.valueOf(1).equals(value.get("version"))
                    || !ALGORITHM.equals(value.get("algorithm"))
                    || !KDF.equals(value.get("kdf"))
                    || !Integer.valueOf(ITERATIONS).equals(value.get("iterations"))
                    || !(value.get("salt") instanceof String)
                    || !(value.get("nonce") instanceof String)
                    || !(value.get("ciphertext") instanceof String)) throw failure();
            if (decode(value.getString("salt")).length != 16
                    || decode(value.getString("nonce")).length != 12) throw failure();
            int length = decode(value.getString("ciphertext")).length;
            if (length < 32 || length > MAX_CIPHERTEXT) throw failure();
        } catch (Exception ignored) { throw failure(); }
    }

    private static byte[] crypt(int mode, String id, String origin, char[] password,
            byte[] salt, byte[] nonce, byte[] input) throws Exception {
        String identity = "subhub-pack:2:paypal:1:" + UUID.fromString(id)
                + ":" + UUID.fromString(origin);
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        byte[] key = null;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } finally {
            spec.clearPassword();
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    public static void checkPassword(char[] password) throws GeneralSecurityException {
        if (password == null || password.length < 12 || password.length > 256) throw failure();
    }

    private static String encode(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private static byte[] decode(String value) { return Base64.getDecoder().decode(value); }
    private static GeneralSecurityException failure() {
        return new GeneralSecurityException("PayPal attachment could not be unlocked or validated");
    }

    public static final class Payload implements AutoCloseable {
        private String environment;
        private String clientId;
        private String secret;
        private String recipientLink;

        public Payload(String environment, String clientId, String secret, String recipientLink)
                throws GeneralSecurityException {
            this.environment = environment;
            this.clientId = clientId;
            this.secret = secret;
            this.recipientLink = recipientLink;
            validate();
        }

        private void validate() throws GeneralSecurityException {
            if (!("SANDBOX".equals(environment) || "LIVE".equals(environment))
                    || !credential(clientId) || !credential(secret)
                    || recipientLink == null || recipientLink.length() > 2048) throw failure();
            if (!recipientLink.isEmpty()) try {
                URI uri = URI.create(recipientLink);
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                        || (uri.getPort() != -1 && uri.getPort() != 443)
                        || !(host.equals("paypal.me") || host.equals("paypal.com")
                        || host.endsWith(".paypal.com"))) throw failure();
            } catch (Exception ignored) { throw failure(); }
        }

        private static boolean credential(String value) {
            return value != null && !value.isEmpty() && value.length() <= 1024
                    && value.chars().allMatch(c -> c >= 33 && c <= 126);
        }

        public String environment() { return environment; }
        public String clientId() { return clientId; }
        public String secret() { return secret; }
        public String recipientLink() { return recipientLink; }
        public String summary() {
            return environment + " · merchant …" + clientId.substring(Math.max(0, clientId.length() - 4))
                    + (recipientLink.isEmpty() ? " · no fallback link" : " · PayPal fallback link included");
        }
        @Override public String toString() { return "PayPal payload [redacted]"; }
        @Override public void close() { environment = clientId = secret = recipientLink = ""; }
    }
}
