package com.subhub.app.update;

import org.json.JSONException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class UpdateManifestTest {
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test public void picksFirstSupportedAbiBeforeUniversal() throws Exception {
        UpdateManifest manifest = UpdateManifest.parse(manifest(
                asset("universal", "universal.apk"), asset("arm64-v8a", "arm64.apk")));
        assertEquals("arm64-v8a", manifest.selectAsset(new String[]{"arm64-v8a", "armeabi-v7a"}).abi);
    }

    @Test public void fallsBackToUniversal() throws Exception {
        UpdateManifest manifest = UpdateManifest.parse(manifest(asset("universal", "universal.apk")));
        assertEquals("universal", manifest.selectAsset(new String[]{"x86_64"}).abi);
    }

    @Test public void preservesGeneratedReleaseNotes() throws Exception {
        UpdateManifest manifest = UpdateManifest.parse(manifest(asset("universal", "universal.apk")));
        assertEquals("## What’s new\n\n- A useful fix", manifest.releaseNotes);
        assertEquals(manifest.releaseNotes, UpdateManifest.parse(manifest.json()).releaseNotes);
    }

    @Test public void returnsNullWithoutCompatibleAsset() throws Exception {
        UpdateManifest manifest = UpdateManifest.parse(manifest(asset("arm64-v8a", "arm64.apk")));
        assertNull(manifest.selectAsset(new String[]{"x86_64"}));
    }

    @Test(expected = JSONException.class)
    public void rejectsWrongPackageShape() throws Exception {
        UpdateManifest.parse(manifest(asset("universal", "not-an-apk.zip")));
    }

    @Test(expected = JSONException.class) public void rejectsParentPathInAssetName() throws Exception {
        UpdateManifest.parse(manifest(asset("universal", "../escape.apk")));
    }

    @Test(expected = JSONException.class) public void rejectsWindowsPathInAssetName() throws Exception {
        UpdateManifest.parse(manifest(asset("universal", "C:/escape.apk")));
    }

    private static String manifest(String... assets) {
        return "{\"schema\":1,\"packageName\":\"com.subhub.app\",\"versionName\":\"0.4.0\","
                + "\"versionCode\":4,\"minSdk\":26,\"tag\":\"v0.4.0\","
                + "\"releaseNotes\":\"## What’s new\\n\\n- A useful fix\",\"assets\":["
                + String.join(",", assets) + "]}";
    }

    private static String asset(String abi, String name) {
        return "{\"abi\":\"" + abi + "\",\"name\":\"" + name
                + "\",\"url\":\"https://github.com/bozo-industries/SubHub/releases/download/v0.4.0/"
                + name + "\",\"sha256\":\"" + HASH + "\",\"size\":42}";
    }
}
