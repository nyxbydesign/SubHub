package com.subhub.app.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 SHA-1, six digits, 30-second steps; interoperable with ordinary authenticators. */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    public static final long STEP_MILLIS = 30_000L;
    private Totp() { }
    public static String newSecret() {
        byte[] bytes = new byte[20]; new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(); int bits = 0, buffer = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 255); bits += 8;
            while (bits >= 5) { bits -= 5; result.append(ALPHABET.charAt((buffer >> bits) & 31)); }
        }
        return result.toString();
    }
    public static String code(String secret, long step) throws GeneralSecurityException {
        if (step < 0) throw new IllegalArgumentException("Invalid time step");
        byte[] key = decode(secret);
        try {
            Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 15;
            int number = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16)
                    | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
            return String.format(Locale.ROOT, "%06d", number % 1_000_000);
        } finally { java.util.Arrays.fill(key, (byte) 0); }
    }
    public static long verify(String secret, String supplied, long nowMillis, long lastUsedStep)
            throws GeneralSecurityException {
        String candidate = supplied == null ? "" : supplied.replace(" ", "").trim();
        if (!candidate.matches("[0-9]{6}") || nowMillis < 0) return -1;
        long current = nowMillis / STEP_MILLIS; long matched = -1;
        for (int offset = -1; offset <= 1; offset++) {
            long step = current + offset;
            if (step < 0) continue;
            boolean equals = MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII),
                    candidate.getBytes(StandardCharsets.US_ASCII));
            if (equals && step > lastUsedStep) matched = step;
        }
        return matched;
    }
    private static byte[] decode(String value) {
        String secret = value.toUpperCase(Locale.ROOT).replace(" ", "");
        if (!secret.matches("[A-Z2-7]{32}")) throw new IllegalArgumentException("Invalid authenticator key");
        byte[] output = new byte[20]; int buffer = 0, bits = 0, index = 0;
        for (int i = 0; i < secret.length(); i++) {
            buffer = (buffer << 5) | ALPHABET.indexOf(secret.charAt(i)); bits += 5;
            if (bits >= 8) { bits -= 8; output[index++] = (byte) (buffer >> bits); }
        }
        return output;
    }
}
