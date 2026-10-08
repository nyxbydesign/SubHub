package com.subhub.app.update;

import org.json.JSONException;
import org.json.JSONObject;

/** A compatible GitHub release and its verified-shape update manifest. */
public final class UpdateCandidate {
    public final UpdateManifest manifest;
    public final String notes;
    public final String releaseUrl;
    public final boolean prerelease;

    public UpdateCandidate(UpdateManifest manifest, String notes, String releaseUrl) {
        this(manifest, notes, releaseUrl, SemanticVersion.parse(manifest.versionName).isPrerelease());
    }

    public UpdateCandidate(UpdateManifest manifest, String notes, String releaseUrl, boolean prerelease) {
        this.manifest = manifest;
        this.notes = notes == null ? "" : notes;
        this.releaseUrl = releaseUrl == null ? "" : releaseUrl;
        this.prerelease = prerelease || SemanticVersion.parse(manifest.versionName).isPrerelease();
    }

    public String json() throws JSONException {
        return new JSONObject().put("manifest", new JSONObject(manifest.json()))
                .put("notes", notes).put("releaseUrl", releaseUrl)
                .put("prerelease", prerelease).toString();
    }

    public static UpdateCandidate parse(String value) throws JSONException {
        JSONObject json = new JSONObject(value);
        return new UpdateCandidate(UpdateManifest.parse(json.getJSONObject("manifest").toString()),
                json.optString("notes", ""), json.optString("releaseUrl", ""),
                json.optBoolean("prerelease", false));
    }
}
