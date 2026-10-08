package com.subhub.app.pack;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Portable settings with an optional, opaque passphrase-encrypted merchant attachment. */
public final class SubHubPack {
    public static final String FORMAT = "subhub-pack";
    public static final int SCHEMA_VERSION = 1;

    private final String id;
    private final String originDeviceId;
    private String name;
    private String author;
    private String description;
    private String packVersion;
    private final long createdAt;
    private long updatedAt;
    private String minimumSubHubVersion;
    private final Map<String, JSONObject> sections;
    private final Set<String> lockGroups;
    private JSONObject recommendations;
    private final Map<String, byte[]> assets;
    private JSONObject encryptedPayPal;

    public SubHubPack(String id, String name, String author, String description,
            String packVersion, long createdAt, long updatedAt, String minimumSubHubVersion,
            Map<String, JSONObject> sections, Set<String> lockGroups,
            JSONObject recommendations, Map<String, byte[]> assets) {
        this(id, legacyOriginId(id), name, author, description, packVersion, createdAt, updatedAt,
                minimumSubHubVersion, sections, lockGroups, recommendations, assets);
    }

    public SubHubPack(String id, String originDeviceId, String name, String author,
            String description, String packVersion, long createdAt, long updatedAt,
            String minimumSubHubVersion, Map<String, JSONObject> sections,
            Set<String> lockGroups, JSONObject recommendations, Map<String, byte[]> assets) {
        this.id = cleanId(id);
        this.originDeviceId = cleanId(originDeviceId);
        this.name = clean(name, "Untitled arrangement", 80);
        this.author = clean(author, "", 80);
        this.description = clean(description, "", 500);
        this.packVersion = clean(packVersion, "1.0.0", 24);
        this.createdAt = Math.max(1L, createdAt);
        this.updatedAt = Math.max(this.createdAt, updatedAt);
        this.minimumSubHubVersion = clean(minimumSubHubVersion, "0.6.0", 24);
        this.sections = new LinkedHashMap<>();
        if (sections != null) for (Map.Entry<String, JSONObject> item : sections.entrySet()) {
            if (SubHubPackSchema.SECTIONS.contains(item.getKey()) && item.getValue() != null) {
                this.sections.put(item.getKey(), copy(item.getValue()));
            }
        }
        this.lockGroups = new LinkedHashSet<>();
        if (lockGroups != null) for (String group : lockGroups) {
            if (SubHubPackSchema.SECTIONS.contains(group)) this.lockGroups.add(group);
        }
        this.recommendations = recommendations == null ? new JSONObject() : copy(recommendations);
        this.assets = new LinkedHashMap<>();
        if (assets != null) for (Map.Entry<String, byte[]> item : assets.entrySet()) {
            if (SubHubPackArchive.isSafeAssetPath(item.getKey()) && item.getValue() != null) {
                this.assets.put(item.getKey(), item.getValue().clone());
            }
        }
    }

    public static SubHubPack blank() {
        return blank(UUID.randomUUID().toString());
    }

    public static SubHubPack blank(String originDeviceId) {
        long now = System.currentTimeMillis();
        return new SubHubPack(UUID.randomUUID().toString(), originDeviceId,
                "Untitled arrangement", "", "", "1.0.0", now, now, "0.6.0",
                Map.of(), Set.of(), new JSONObject(), Map.of());
    }

    public SubHubPack duplicate() {
        long now = System.currentTimeMillis();
        // A new ID cannot authenticate the original attachment's AAD. UI explains its omission.
        return new SubHubPack(UUID.randomUUID().toString(), originDeviceId, name + " copy",
                author, description, packVersion, now, now, minimumSubHubVersion, sections, lockGroups,
                recommendations, assets);
    }

    public String getId() { return id; }
    public boolean hasEncryptedPayPal() { return encryptedPayPal != null; }
    public JSONObject getEncryptedPayPal() {
        return encryptedPayPal == null ? null : copy(encryptedPayPal);
    }
    public void setEncryptedPayPal(JSONObject value) throws java.security.GeneralSecurityException {
        if (value != null) {
            if (!sections.containsKey(SubHubPackSchema.WALLET)) {
                throw new java.security.GeneralSecurityException("Include Wallet before adding PayPal");
            }
            PackPayPalCipher.validateEnvelope(value);
        }
        encryptedPayPal = value == null ? null : copy(value);
        touch();
    }
    public String getOriginDeviceId() { return originDeviceId; }
    public String getName() { return name; }
    public String getAuthor() { return author; }
    public String getDescription() { return description; }
    public String getPackVersion() { return packVersion; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public String getMinimumSubHubVersion() { return minimumSubHubVersion; }
    public Set<String> getIncludedSections() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(sections.keySet()));
    }
    public Set<String> getLockGroups() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(lockGroups));
    }
    public JSONObject getRecommendations() { return copy(recommendations); }
    public Map<String, byte[]> getAssets() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> item : assets.entrySet()) {
            result.put(item.getKey(), item.getValue().clone());
        }
        return Collections.unmodifiableMap(result);
    }
    public JSONObject getSection(String section) {
        JSONObject value = sections.get(section);
        return value == null ? null : copy(value);
    }

    public void setMetadata(String name, String author, String description, String packVersion) {
        this.name = clean(name, "Untitled arrangement", 80);
        this.author = clean(author, "", 80);
        this.description = clean(description, "", 500);
        this.packVersion = clean(packVersion, "1.0.0", 24);
        touch();
    }

    public void setSection(String section, JSONObject values) {
        if (!SubHubPackSchema.SECTIONS.contains(section)) return;
        if (values == null) {
            sections.remove(section);
            if (SubHubPackSchema.WALLET.equals(section)) encryptedPayPal = null;
        }
        else sections.put(section, SubHubPackSchema.sanitizeSection(section, values));
        touch();
    }

    public void setGroupLocked(String section, boolean locked) {
        if (!SubHubPackSchema.SECTIONS.contains(section)) return;
        if (locked) lockGroups.add(section); else lockGroups.remove(section);
        touch();
    }

    public void setRecommendations(JSONObject values) {
        recommendations = SubHubPackSchema.sanitizeRecommendations(values);
        touch();
    }

    public void putAsset(String path, byte[] bytes) {
        if (!SubHubPackArchive.isSafeAssetPath(path) || bytes == null) return;
        assets.put(path, bytes.clone());
        touch();
    }

    public void removeAsset(String path) {
        assets.remove(path);
        touch();
    }

    JSONObject manifestWithoutIntegrity(Map<String, String> assetHashes) throws JSONException {
        JSONObject manifest = new JSONObject();
        manifest.put("format", FORMAT);
        manifest.put("schemaVersion", hasEncryptedPayPal() ? 2 : SCHEMA_VERSION);
        if (hasEncryptedPayPal()) manifest.put("encryptedPayPal", getEncryptedPayPal());
        manifest.put("id", id);
        manifest.put("originDeviceId", originDeviceId);
        manifest.put("name", name);
        manifest.put("author", author);
        manifest.put("description", description);
        manifest.put("packVersion", packVersion);
        manifest.put("createdAt", createdAt);
        manifest.put("updatedAt", updatedAt);
        manifest.put("minimumSubHubVersion", minimumSubHubVersion);
        manifest.put("includedSections", new JSONArray(new ArrayList<>(sections.keySet())));
        manifest.put("lockGroups", new JSONArray(new ArrayList<>(lockGroups)));
        manifest.put("recommendations", recommendations);
        JSONObject hashes = new JSONObject();
        for (Map.Entry<String, String> hash : assetHashes.entrySet()) {
            hashes.put(hash.getKey(), hash.getValue());
        }
        manifest.put("assetHashes", hashes);
        manifest.put("integrity", "");
        return manifest;
    }

    static SubHubPack fromManifest(JSONObject manifest, Map<String, JSONObject> sections,
            Map<String, byte[]> assets) throws JSONException {
        if (!FORMAT.equals(manifest.optString("format"))) {
            throw new JSONException("Not a SubHub pack");
        }
        int schema = manifest.optInt("schemaVersion", -1);
        if ((schema != SCHEMA_VERSION && schema != 2)
                || (schema == 1 && manifest.has("encryptedPayPal"))
                || (schema == 2 && !manifest.has("encryptedPayPal"))) {
            throw new JSONException("Unsupported SubHub pack schema");
        }
        Set<String> locks = jsonStrings(manifest.optJSONArray("lockGroups"));
        String id = manifest.optString("id");
        String origin = manifest.optString("originDeviceId", legacyOriginId(id));
        SubHubPack pack = new SubHubPack(id, origin, manifest.optString("name"),
                manifest.optString("author"), manifest.optString("description"),
                manifest.optString("packVersion"), manifest.optLong("createdAt", 1L),
                manifest.optLong("updatedAt", 1L),
                manifest.optString("minimumSubHubVersion", "0.6.0"), sections, locks,
                manifest.optJSONObject("recommendations"), assets);
        if (schema == 2) try {
            // Reject normalized/replaced identities before authenticated decryption is offered.
            if (!pack.id.equals(id) || !pack.originDeviceId.equals(origin)) {
                throw new IllegalArgumentException();
            }
            pack.setEncryptedPayPal(manifest.getJSONObject("encryptedPayPal"));
            pack.updatedAt = Math.max(pack.createdAt, manifest.optLong("updatedAt", 1L));
        } catch (Exception ignored) { throw new JSONException("Invalid encrypted PayPal attachment"); }
        return pack;
    }

    private void touch() { updatedAt = System.currentTimeMillis(); }

    private static Set<String> jsonStrings(JSONArray values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) for (int index = 0; index < values.length(); index++) {
            String value = values.optString(index, "");
            if (!value.isBlank()) result.add(value);
        }
        return result;
    }

    private static String cleanId(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (Exception ignored) { return UUID.randomUUID().toString(); }
    }

    private static String legacyOriginId(String packId) {
        String value = packId == null ? "" : packId.trim();
        return UUID.nameUUIDFromBytes(("subhub-pack-origin:" + value)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String clean(String value, String fallback, int maximum) {
        String safe = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (safe.isEmpty()) safe = fallback;
        return safe.substring(0, Math.min(maximum, safe.length()));
    }

    private static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); }
        catch (Exception ignored) { return new JSONObject(); }
    }
}
