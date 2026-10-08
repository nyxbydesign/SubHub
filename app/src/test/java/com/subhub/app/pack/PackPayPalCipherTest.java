package com.subhub.app.pack;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

public final class PackPayPalCipherTest {
    private static final String ID = "12345678-1234-1234-1234-123456789012";
    private static final String ORIGIN = "87654321-4321-4321-4321-210987654321";
    private static final char[] PASSWORD = "synthetic long test passphrase".toCharArray();

    private PackPayPalCipher.Payload payload() throws Exception {
        return new PackPayPalCipher.Payload("SANDBOX", "synthetic-client-id", "synthetic-secret",
                "https://paypal.me/synthetic-recipient");
    }

    @Test public void authenticatedRoundTrip() throws Exception {
        JSONObject envelope = PackPayPalCipher.encrypt(ID, ORIGIN, payload(), PASSWORD);
        try (PackPayPalCipher.Payload value = PackPayPalCipher.decrypt(ID, ORIGIN, envelope, PASSWORD)) {
            assertEquals("synthetic-client-id", value.clientId());
            assertEquals("synthetic-secret", value.secret());
            assertEquals("SANDBOX", value.environment());
            assertEquals("https://paypal.me/synthetic-recipient", value.recipientLink());
            assertFalse(value.toString().contains("synthetic"));
            assertFalse(value.summary().contains("synthetic-secret"));
        }
    }

    @Test public void independentlySaltedEachTime() throws Exception {
        JSONObject one = PackPayPalCipher.encrypt(ID, ORIGIN, payload(), PASSWORD);
        JSONObject two = PackPayPalCipher.encrypt(ID, ORIGIN, payload(), PASSWORD);
        assertNotEquals(one.getString("salt"), two.getString("salt"));
        assertNotEquals(one.getString("nonce"), two.getString("nonce"));
        assertNotEquals(one.getString("ciphertext"), two.getString("ciphertext"));
    }

    @Test public void wrongPasswordAndIdentityFailClosed() throws Exception {
        JSONObject envelope = PackPayPalCipher.encrypt(ID, ORIGIN, payload(), PASSWORD);
        assertThrows(GeneralSecurityException.class, () -> PackPayPalCipher.decrypt(ID, ORIGIN,
                envelope, "different passphrase".toCharArray()));
        assertThrows(GeneralSecurityException.class, () -> PackPayPalCipher.decrypt(ORIGIN, ORIGIN,
                envelope, PASSWORD));
        assertThrows(GeneralSecurityException.class, () -> PackPayPalCipher.decrypt(ID, ID,
                envelope, PASSWORD));
    }

    @Test public void everyCryptographicPartIsAuthenticated() throws Exception {
        JSONObject envelope = PackPayPalCipher.encrypt(ID, ORIGIN, payload(), PASSWORD);
        for (String key : new String[]{"salt", "nonce", "ciphertext"}) {
            JSONObject modified = new JSONObject(envelope.toString());
            byte[] bytes = Base64.getDecoder().decode(modified.getString(key));
            bytes[0] ^= 1;
            modified.put(key, Base64.getEncoder().encodeToString(bytes));
            assertThrows(GeneralSecurityException.class,
                    () -> PackPayPalCipher.decrypt(ID, ORIGIN, modified, PASSWORD));
        }
    }

    @Test public void resourceAndProtocolLimitsCheckedWithoutDerivingKey() throws Exception {
        JSONObject envelope = PackPayPalCipher.encrypt(ID, ORIGIN, payload(), PASSWORD);
        for (Object iterations : new Object[]{1, 600001, Integer.MAX_VALUE, "600000", 600000.0}) {
            JSONObject value = new JSONObject(envelope.toString()).put("iterations", iterations);
            assertThrows(GeneralSecurityException.class, () -> PackPayPalCipher.validateEnvelope(value));
        }
        for (String field : new String[]{"salt", "nonce", "ciphertext", "algorithm", "kdf"}) {
            JSONObject value = new JSONObject(envelope.toString()).put(field, "invalid");
            assertThrows(GeneralSecurityException.class, () -> PackPayPalCipher.validateEnvelope(value));
        }
        JSONObject extra = new JSONObject(envelope.toString()).put("clientSecret", "not-allowed");
        assertThrows(GeneralSecurityException.class, () -> PackPayPalCipher.validateEnvelope(extra));
        JSONObject huge = new JSONObject(envelope.toString()).put("ciphertext", "A".repeat(12000));
        assertThrows(GeneralSecurityException.class, () -> PackPayPalCipher.validateEnvelope(huge));
    }

    @Test public void passphraseAndPayloadLimits() throws Exception {
        assertThrows(GeneralSecurityException.class, () -> PackPayPalCipher.checkPassword(new char[11]));
        assertThrows(GeneralSecurityException.class, () -> PackPayPalCipher.checkPassword(new char[257]));
        assertThrows(GeneralSecurityException.class,
                () -> new PackPayPalCipher.Payload("EVIL", "id", "secret", ""));
        assertThrows(GeneralSecurityException.class,
                () -> new PackPayPalCipher.Payload("LIVE", "", "secret", ""));
        assertThrows(GeneralSecurityException.class,
                () -> new PackPayPalCipher.Payload("LIVE", "id", "a".repeat(1025), ""));
        for (String url : new String[]{"http://paypal.me/name", "https://paypal.com.evil.test/x",
                "https://evil.test", "https://user@paypal.com/x", "https://paypal.com:8443/x"}) {
            assertThrows(GeneralSecurityException.class,
                    () -> new PackPayPalCipher.Payload("LIVE", "id", "secret", url));
        }
    }

    @Test public void zipDraftRoundTripNeverContainsPlainCredentialsOrRecipient() throws Exception {
        SubHubPack pack = wallet();
        pack.setEncryptedPayPal(PackPayPalCipher.encrypt(pack.getId(), pack.getOriginDeviceId(), payload(), PASSWORD));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SubHubPackArchive.write(pack, out);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            while (zip.getNextEntry() != null) {
                String raw = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(raw.contains("synthetic-client-id"));
                assertFalse(raw.contains("synthetic-secret"));
                assertFalse(raw.contains("synthetic-recipient"));
                assertFalse(raw.contains(new String(PASSWORD)));
            }
        }
        SubHubPack decoded = SubHubPackArchive.read(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(decoded.hasEncryptedPayPal());
        assertEquals(2, decoded.manifestWithoutIntegrity(Map.of()).getInt("schemaVersion"));
        assertEquals(pack.getUpdatedAt(), decoded.getUpdatedAt());
        try (PackPayPalCipher.Payload value = PackPayPalCipher.decrypt(decoded.getId(),
                decoded.getOriginDeviceId(), decoded.getEncryptedPayPal(), PASSWORD)) {
            assertEquals("synthetic-secret", value.secret());
        }
    }

    @Test public void removingWalletAndDuplicatingNeverCarryInvalidCiphertext() throws Exception {
        SubHubPack pack = wallet();
        pack.setEncryptedPayPal(PackPayPalCipher.encrypt(pack.getId(), pack.getOriginDeviceId(), payload(), PASSWORD));
        assertFalse(pack.duplicate().hasEncryptedPayPal());
        assertTrue(pack.hasEncryptedPayPal());
        JSONObject returned = pack.getEncryptedPayPal();
        returned.put("ciphertext", "invalid");
        PackPayPalCipher.validateEnvelope(pack.getEncryptedPayPal());
        pack.setSection(SubHubPackSchema.WALLET, null);
        assertFalse(pack.hasEncryptedPayPal());
        assertEquals(1, pack.manifestWithoutIntegrity(Map.of()).getInt("schemaVersion"));
        assertThrows(GeneralSecurityException.class, () -> pack.setEncryptedPayPal(returned));
    }

    @Test public void schemaDowngradeAndMissingAttachmentRejected() throws Exception {
        SubHubPack pack = wallet();
        pack.setEncryptedPayPal(PackPayPalCipher.encrypt(pack.getId(), pack.getOriginDeviceId(), payload(), PASSWORD));
        JSONObject manifest = pack.manifestWithoutIntegrity(Map.of());
        manifest.put("schemaVersion", 1);
        assertThrows(org.json.JSONException.class, () -> SubHubPack.fromManifest(manifest,
                Map.of(SubHubPackSchema.WALLET, new JSONObject()), Map.of()));
        manifest.put("schemaVersion", 2);
        manifest.remove("encryptedPayPal");
        assertThrows(org.json.JSONException.class, () -> SubHubPack.fromManifest(manifest,
                Map.of(SubHubPackSchema.WALLET, new JSONObject()), Map.of()));
    }

    private SubHubPack wallet() {
        SubHubPack pack = SubHubPack.blank(UUID.randomUUID().toString());
        pack.setSection(SubHubPackSchema.WALLET, new JSONObject());
        return pack;
    }
}
