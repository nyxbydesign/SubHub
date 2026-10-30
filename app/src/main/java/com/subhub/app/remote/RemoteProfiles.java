package com.subhub.app.remote;

import com.subhub.app.people.ProfileData;
import com.subhub.app.people.ProfileImages;
import com.subhub.app.people.ProfileNames;
import com.subhub.app.security.SubFeaturePolicy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Optional profile snapshots use the authenticated relationship, never the configuration writer. */
public final class RemoteProfiles {
    private RemoteProfiles() { }

    public static JSONObject encode(ProfileData profile, RemoteProtocol.Role role) throws JSONException {
        return new JSONObject().put("role", role.name()).put("name", profile.name)
                .put("about", profile.about).put("avatar", profile.avatar);
    }

    public static ProfileData parse(JSONObject value, RemoteProtocol.Role expected) throws JSONException {
        if (value.length() != 4 || !expected.name().equals(value.get("role"))
                || !(value.get("name") instanceof String) || !(value.get("about") instanceof String)
                || !(value.get("avatar") instanceof String)) throw new JSONException("Invalid profile fields");
        String name = value.getString("name"), about = value.getString("about");
        if (!name.equals(ProfileNames.clean(name)) || name.length() > ProfileNames.MAX_LENGTH
                || !about.equals(ProfileData.cleanAbout(about)) || about.length() > ProfileData.MAX_ABOUT_LENGTH)
            throw new JSONException("Invalid profile text");
        try { return new ProfileData(name, about, value.getString("avatar")); }
        catch (IllegalArgumentException invalid) { throw new JSONException("Invalid profile image"); }
    }

    static void receive(RemoteStore store, RemoteStore.Peer peer, JSONObject value) throws JSONException {
        ProfileData profile = parse(value, peer.remoteRole());
        ProfileImages.requireValid(profile.avatar);
        store.updateProfile(peer.identity, peer.generation, value, profile.name);
    }

    public static ProfileData read(RemoteStore store, RemoteStore.Peer peer) {
        try {
            RemoteStore.ProfileState current = store.profileState(peer.identity, peer.generation);
            if (current == null) return ProfileData.empty(peer.name);
            if (current.record.isEmpty()) return ProfileData.empty(current.name);
            ProfileData saved = parse(new JSONObject(current.record), peer.remoteRole());
            return new ProfileData(current.name, saved.about, saved.avatar);
        } catch (JSONException | IllegalArgumentException invalid) { return ProfileData.empty(peer.name); }
    }

    public static Map<String, Boolean> allowances(JSONObject runtime) {
        if (!RemoteTelemetry.validRuntime(runtime)) return Collections.emptyMap();
        JSONObject consent = runtime.optJSONObject("featureConsent");
        if (consent == null) return Collections.emptyMap();
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String key : SubFeaturePolicy.FEATURES) result.put(key, consent.optBoolean(key, false));
        return result;
    }
}
