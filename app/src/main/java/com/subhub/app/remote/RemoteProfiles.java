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
        return encode(profile, role, false);
    }

    public static JSONObject encode(ProfileData profile, RemoteProtocol.Role role, boolean details) throws JSONException {
        return encode(profile, role, details, false);
    }

    public static JSONObject encode(ProfileData profile, RemoteProtocol.Role role, boolean details, boolean roleIdentity) throws JSONException {
        JSONObject value = new JSONObject().put("role", role.name()).put("name", profile.name)
                .put("about", profile.about).put("avatar", profile.avatar);
        if (details) value.put("gender", profile.gender).put("age", profile.age);
        if (roleIdentity) value.put("isSwitch", profile.isSwitch);
        return value;
    }

    public static ProfileData parse(JSONObject value, RemoteProtocol.Role expected) throws JSONException {
        return parse(value, expected, value.has("gender") || value.has("age"), value.has("isSwitch"));
    }

    private static ProfileData parse(JSONObject value, RemoteProtocol.Role expected, boolean details, boolean roleIdentity) throws JSONException {
        if (roleIdentity && !(value.get("isSwitch") instanceof Boolean)) throw new JSONException("Invalid profile role identity");
        if (value.length() != (details ? 6 : 4) + (roleIdentity ? 1 : 0) || !expected.name().equals(value.get("role"))
                || !(value.get("name") instanceof String) || !(value.get("about") instanceof String)
                || !(value.get("avatar") instanceof String)) throw new JSONException("Invalid profile fields");
        String name = value.getString("name"), about = value.getString("about");
        if (!name.equals(ProfileNames.clean(name)) || name.length() > ProfileNames.MAX_LENGTH
                || !about.equals(ProfileData.cleanAbout(about)) || about.length() > ProfileData.MAX_ABOUT_LENGTH)
            throw new JSONException("Invalid profile text");
        String gender = "";
        int age = 0;
        if (details) {
            if (!(value.get("gender") instanceof String)) throw new JSONException("Invalid profile gender");
            gender = value.getString("gender");
            if (!gender.equals(ProfileData.cleanGender(gender))) throw new JSONException("Invalid profile gender");
            long enteredAge = RemoteProtocol.integer(value, "age");
            if (enteredAge < 0 || enteredAge > ProfileData.MAX_AGE) throw new JSONException("Invalid profile age");
            age = (int) enteredAge;
        }
        try { return new ProfileData(name, about, value.getString("avatar"), gender, age, roleIdentity && value.getBoolean("isSwitch")); }
        catch (IllegalArgumentException invalid) { throw new JSONException("Invalid profile image"); }
    }

    static void receive(RemoteStore store, RemoteStore.Peer peer, JSONObject value) throws JSONException {
        receive(store, peer, value, false);
    }

    static void receive(RemoteStore store, RemoteStore.Peer peer, JSONObject value, boolean details) throws JSONException {
        receive(store, peer, value, details, false);
    }

    static boolean receive(RemoteStore store, RemoteStore.Peer peer, JSONObject value, boolean details, boolean roleIdentity) throws JSONException {
        ProfileData profile = parse(value, peer.remoteRole(), details, roleIdentity);
        ProfileImages.requireValid(profile.avatar);
        return store.updateProfile(peer.identity, peer.generation, value, profile.name);
    }

    public static ProfileData read(RemoteStore store, RemoteStore.Peer peer) {
        try {
            RemoteStore.ProfileState current = store.profileState(peer.identity, peer.generation);
            if (current == null) return ProfileData.empty(peer.name);
            if (current.record.isEmpty()) return ProfileData.empty(current.name);
            ProfileData saved = parse(new JSONObject(current.record), peer.remoteRole());
            return new ProfileData(current.name, saved.about, saved.avatar, saved.gender, saved.age, saved.isSwitch);
        } catch (JSONException | IllegalArgumentException invalid) { return ProfileData.empty(peer.name); }
    }

    /** The applied command identifies its author; a newly paired Dom must not inherit that attribution. */
    static ProfileData contractDom(android.content.Context context, RemoteStore store, RemoteContracts contracts) {
        return contractOwner(context, store, contracts).profile;
    }

    static final class ContractOwner {
        final ProfileData profile;
        final com.subhub.app.people.PersonId id;
        final com.subhub.app.people.PersonSnapshot.PeerContact contact;
        ContractOwner(ProfileData profile, com.subhub.app.people.PersonId id) { this(profile, id, null); }
        ContractOwner(ProfileData profile, com.subhub.app.people.PersonId id,
                com.subhub.app.people.PersonSnapshot.PeerContact contact) {
            this.profile = profile; this.id = id; this.contact = contact;
        }
    }

    private static ContractOwner owner(RemoteStore store, RemoteStore.Peer peer) {
        com.subhub.app.people.PersonId id = RemotePersonRepository.id(peer);
        PeerPresence.Observation presence = PeerPresence.read(store,peer);
        com.subhub.app.people.PersonSnapshot.Connection state = peer.state == RemoteStore.State.REVOKED
                ? com.subhub.app.people.PersonSnapshot.Connection.REVOKED : peer.state == RemoteStore.State.PENDING
                ? com.subhub.app.people.PersonSnapshot.Connection.PENDING
                : RemotePersonRepository.activePresence(presence);
        return new ContractOwner(read(store, peer), id,
                new com.subhub.app.people.PersonSnapshot.PeerContact(id, state, presence.lastOnlineAt));
    }

    static ContractOwner contractOwner(android.content.Context context, RemoteStore store, RemoteContracts contracts) {
        ContractOwner unknown = new ContractOwner(ProfileData.empty(context.getString(com.subhub.app.R.string.header_subtitle_dom)), null);
        synchronized (store) {
            android.content.SharedPreferences main = context.getSharedPreferences(
                    com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0);
            String retained = main.getString("remote_active_configuration", "");
            try {
                JSONObject applied = retained.isEmpty() ? null : contracts.localApplied();
                String commandId = main.getString("remote_active_command", "");
                for (RemoteStore.Peer peer : store.peers(false)) {
                    if (peer.localRole != RemoteProtocol.Role.SUB || peer.state != RemoteStore.State.ACTIVE) continue;
                    if (retained.isEmpty()) return owner(store, peer);
                    if (applied == null || commandId.isEmpty()) return unknown;
                    RemoteStore.Command command = store.command(peer.identity, peer.generation, commandId);
                    if (command != null && command.state == RemoteStore.CommandState.APPLIED
                            && RemoteProtocol.canonical(RemoteConfiguration.active(
                                    new JSONObject(command.body).getJSONObject("configuration")))
                                    .equals(RemoteProtocol.canonical(applied))) return owner(store, peer);
                }
            } catch (Exception unavailable) { return unknown; }
            return unknown;
        }
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
