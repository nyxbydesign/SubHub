package com.subhub.app.update;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Versioned metadata published beside each signed release APK. */
public final class UpdateManifest {
    public static final int SCHEMA = 1;
    public static final String PACKAGE = "com.subhub.app";

    public static final class Asset {
        public final String abi;
        public final String name;
        public final String url;
        public final String sha256;
        public final long size;

        Asset(String abi, String name, String url, String sha256, long size) {
            this.abi = abi;
            this.name = name;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("abi", abi).put("name", name).put("url", url)
                    .put("sha256", sha256).put("size", size);
        }
    }

    public final String packageName;
    public final String versionName;
    public final long versionCode;
    public final int minSdk;
    public final String tag;
    public final String releaseNotes;
    public final List<Asset> assets;

    private UpdateManifest(String packageName, String versionName, long versionCode,
            int minSdk, String tag, String releaseNotes, List<Asset> assets) {
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.minSdk = minSdk;
        this.tag = tag;
        this.releaseNotes = releaseNotes;
        this.assets = Collections.unmodifiableList(assets);
    }

    public static UpdateManifest parse(String text) throws JSONException {
        JSONObject json = new JSONObject(text);
        if (json.optInt("schema", -1) != SCHEMA) throw new JSONException("Unsupported update schema");
        String packageName = required(json, "packageName");
        String versionName = required(json, "versionName");
        SemanticVersion.parse(versionName);
        long versionCode = json.optLong("versionCode", -1);
        int minSdk = json.optInt("minSdk", -1);
        String tag = required(json, "tag");
        String releaseNotes = json.optString("releaseNotes", "").trim();
        if (!tag.equals("v" + versionName) || versionCode < 1 || versionCode > 2_100_000_000L || minSdk < 1) {
            throw new JSONException("Invalid release identity");
        }
        if (releaseNotes.length() > 64 * 1024) throw new JSONException("Release notes too large");
        JSONArray values = json.optJSONArray("assets");
        if (values == null || values.length() == 0) throw new JSONException("Missing APK assets");
        List<Asset> assets = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.getJSONObject(index);
            String abi = required(value, "abi");
            String name = required(value, "name");
            String url = required(value, "url");
            String digest = required(value, "sha256").toLowerCase(Locale.ROOT);
            long size = value.optLong("size", -1);
            if (!(abi.equals("universal") || abi.matches("[A-Za-z0-9_-]+"))
                    || !name.endsWith(".apk") || !url.startsWith("https://github.com/")
                    || !digest.matches("[0-9a-f]{64}") || size < 1) {
                throw new JSONException("Invalid APK asset");
            }
            assets.add(new Asset(abi, name, url, digest, size));
        }
        return new UpdateManifest(packageName, versionName, versionCode, minSdk, tag,
                releaseNotes, assets);
    }

    private static String required(JSONObject json, String key) throws JSONException {
        String value = json.optString(key, "").trim();
        if (value.isEmpty()) throw new JSONException("Missing " + key);
        return value;
    }

    public boolean isCompatible(long installedVersionCode) {
        return PACKAGE.equals(packageName) && versionCode > installedVersionCode
                && minSdk <= Build.VERSION.SDK_INT;
    }

    public Asset selectAsset(String[] supportedAbis) {
        for (String supported : supportedAbis) {
            for (Asset asset : assets) if (asset.abi.equals(supported)) return asset;
        }
        for (Asset asset : assets) if (asset.abi.equals("universal")) return asset;
        return null;
    }

    public String json() throws JSONException {
        JSONArray values = new JSONArray();
        for (Asset asset : assets) values.put(asset.json());
        return new JSONObject().put("schema", SCHEMA).put("packageName", packageName)
                .put("versionName", versionName).put("versionCode", versionCode)
                .put("minSdk", minSdk).put("tag", tag).put("releaseNotes", releaseNotes)
                .put("assets", values).toString();
    }
}
