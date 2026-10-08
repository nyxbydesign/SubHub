package com.subhub.app.pack;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SubHubPackArchiveTest {
    @Test public void roundTripPreservesPortableSectionsRecommendationsAndAssetsWithoutLocks()
            throws Exception {
        JSONObject censor = new JSONObject()
                .put("censor_type", "box")
                .put("show_border", true);
        JSONObject recommendations = new JSONObject()
                .put("hardcoreSuggested", true)
                .put("serviceDurationMillis", 86_400_000L);
        byte[] art = "private-art".getBytes(StandardCharsets.UTF_8);
        SubHubPack source = new SubHubPack(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Night Rules",
                "Keeper", "Portable scene", "2.0.0", 10L, 20L, "0.6.0",
                Map.of(SubHubPackSchema.CENSOR, censor),
                recommendations, Map.of("assets/censor/image-00.png", art));

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        SubHubPackArchive.write(source, encoded);
        SubHubPack decoded = SubHubPackArchive.read(
                new ByteArrayInputStream(encoded.toByteArray()));

        assertEquals("Night Rules", decoded.getName());
        assertEquals(source.getOriginDeviceId(), decoded.getOriginDeviceId());
        assertEquals("box", decoded.getSection(SubHubPackSchema.CENSOR)
                .getString("censor_type"));
        assertFalse(decoded.manifestWithoutIntegrity(Map.of()).has("lockGroups"));
        assertEquals(86_400_000L,
                decoded.getRecommendations().getLong("serviceDurationMillis"));
        assertArrayEquals(art, decoded.getAssets().get("assets/censor/image-00.png"));
    }

    @Test public void secretAndRuntimeFieldsCannotEnterPortableSection() throws Exception {
        JSONObject wallet = new JSONObject()
                .put("enabled", true)
                .put("daily_cap_cents", 1000)
                .put("paypal_client_id", "must-not-export")
                .put("saved_wallet_id", "must-not-export")
                .put("history", "must-not-export");
        JSONObject clean = SubHubPackSchema.sanitizeSection(SubHubPackSchema.WALLET, wallet);
        assertTrue(clean.getBoolean("enabled"));
        assertTrue(clean.has("daily_cap_cents"));
        assertFalse(clean.has("paypal_client_id"));
        assertFalse(clean.has("saved_wallet_id"));
        assertFalse(clean.has("history"));
    }

    @Test public void unsupportedRecommendationsAreDropped() throws Exception {
        JSONObject clean = SubHubPackSchema.sanitizeRecommendations(new JSONObject()
                .put("hardcoreSuggested", true)
                .put("serviceDurationMillis", 1234L)
                .put("enableDeviceAdmin", true));
        assertTrue(clean.getBoolean("hardcoreSuggested"));
        assertFalse(clean.has("serviceDurationMillis"));
        assertFalse(clean.has("enableDeviceAdmin"));
    }

    @Test public void unsafeZipPathIsRejected() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("../manifest.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThrows(java.io.IOException.class, () -> SubHubPackArchive.read(
                new ByteArrayInputStream(output.toByteArray())));
    }

    @Test public void invalidAssetNamespaceIsNeverAccepted() {
        assertTrue(SubHubPackArchive.isSafeAssetPath("assets/censor/one.png"));
        assertTrue(SubHubPackArchive.isSafeAssetPath("assets/popup/one.webp"));
        assertFalse(SubHubPackArchive.isSafeAssetPath("assets/../../secret"));
        assertFalse(SubHubPackArchive.isSafeAssetPath("assets/censor/.."));
        assertFalse(SubHubPackArchive.isSafeAssetPath("assets/censor/./one.png"));
        assertFalse(SubHubPackArchive.isSafeAssetPath("assets/censor/"));
        assertFalse(SubHubPackArchive.isSafeAssetPath("sections/censor.json"));
        assertFalse(SubHubPackArchive.isSafeAssetPath("C:/secret"));
    }

    @Test public void oldArchivesAreRejectedWithoutMigrationEvenWithValidIntegrity() throws Exception {
        SubHubPack identity = SubHubPack.blank("synthetic-old-creator");
        JSONObject manifest = identity.manifestWithoutIntegrity(Map.of());
        manifest.put("schemaVersion", 1);
        manifest.put("includedSections", new org.json.JSONArray().put("wallet").put("censor"));
        manifest.put("lockGroups", new org.json.JSONArray().put("wallet").put("censor"));
        Map<String, JSONObject> sections = new java.util.LinkedHashMap<>();
        sections.put("wallet", new JSONObject().put("rule_cents_new_detection", 321)
                .put("rule_enabled_new_detection", true).put("strike_cents", 999));
        sections.put("censor", new JSONObject().put("custom_phrases", new org.json.JSONArray().put("Synthetic phrase"))
                .put("old_unknown_field", "preserved only for digest verification"));
        StringBuilder canonical = new StringBuilder(PackVerifier.canonicalize(manifest));
        for (String section : new java.util.TreeSet<>(sections.keySet())) {
            canonical.append('\n').append(section).append(':').append(PackVerifier.canonicalize(sections.get(section)));
        }
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        manifest.put("integrity", hex.toString());
        byte[] original = legacyArchive(manifest, sections);
        assertThrows(java.io.IOException.class, () -> SubHubPackArchive.read(new ByteArrayInputStream(original)));
        sections.get("wallet").put("rule_cents_new_detection", 322);
        assertThrows(java.io.IOException.class, () -> SubHubPackArchive.read(
                new ByteArrayInputStream(legacyArchive(manifest, sections))));
    }

    @Test public void integralAndFractionalFloatPreferencesHashTheirWireRepresentation() throws Exception {
        SubHubPack source = SubHubPack.blank("synthetic");
        source.setSection("popup", new JSONObject().put("popup_storm_spawn_rate", 2f)
                .put("popup_storm_display_duration", .7f));
        source.setSection("censor", new JSONObject().put("reverse_strength", 1f)
                .put("censor_size_padding", .25f));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SubHubPackArchive.write(source, bytes);
        SubHubPack imported = SubHubPackArchive.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(2, imported.getSection("popup").getDouble("popup_storm_spawn_rate"), .00001);
        assertEquals(.7, imported.getSection("popup").getDouble("popup_storm_display_duration"), .00001);
        assertEquals(1, imported.getSection("censor").getDouble("reverse_strength"), .00001);
        assertEquals(.25, imported.getSection("censor").getDouble("censor_size_padding"), .00001);
    }

    private static byte[] legacyArchive(JSONObject manifest, Map<String, JSONObject> sections) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Map.Entry<String, JSONObject> section : sections.entrySet()) {
                zip.putNextEntry(new ZipEntry("sections/" + section.getKey() + ".json"));
                zip.write(section.getValue().toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
