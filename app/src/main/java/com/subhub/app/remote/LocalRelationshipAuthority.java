package com.subhub.app.remote;

import android.content.Context;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.people.People;
import com.subhub.app.security.ControllerAuthenticator;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;

/** Observes real local credentials outside SQL; a displayed or persisted review supplies no authorization. */
final class LocalRelationshipAuthority {
    private final Context context;
    private final RemoteStore store;
    private final FormalRelationshipClock.Source clock;
    static final class Observation {
        private final KeyholderProvider provider;
        private final String credentials, scope;
        private final Inputs inputs;
        private final boolean adminActive;
        private final FormalRelationshipClock.Sample clock;
        private Observation(KeyholderProvider provider, String credentials, Inputs inputs, boolean adminActive, FormalRelationshipClock.Sample clock) {
            this.provider = provider; this.credentials = credentials; this.inputs = inputs; this.adminActive = adminActive;
            this.scope = inputs.scope(adminActive); this.clock = clock;
        }
    }
    LocalRelationshipAuthority(Context context, RemoteStore store, FormalRelationshipClock.Source clock) {
        this.context = context.getApplicationContext(); this.store = store; this.clock = clock;
    }
    private String credentials() {
        android.content.SharedPreferences pin = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        android.content.SharedPreferences totp = context.getSharedPreferences("subhub_controller_auth", 0);
        return digest(new JSONArray().put(pin.getString("controller_pin_salt", ""))
                .put(pin.getString("controller_pin_hash", "")).put(totp.getString("encrypted_secret", "")).toString());
    }
    private static final class Inputs {
        final String chosen; final boolean consent, requested;
        Inputs(String chosen, boolean consent, boolean requested) { this.chosen = chosen; this.consent = consent; this.requested = requested; }
        String scope(boolean adminActive) {
            return digest(new JSONArray().put(chosen).put(consent).put(requested).put(consent && requested && adminActive).toString());
        }
        boolean same(Inputs other) { return consent == other.consent && requested == other.requested && chosen.equals(other.chosen); }
    }
    /** Only fenced local preference values; never calls the effective-policy/platform accessor. */
    private Inputs inputs() {
        JSONArray chosen = new JSONArray();
        for (String feature : new String[]{"censor", "limits", "wallet", "subliminal", "popup", "tasks"})
            chosen.put(feature).put(SubFeaturePolicy.chosen(context, feature));
        boolean consent = context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getBoolean("hardcore_locally_consented", false);
        boolean requested = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0)
                .getBoolean(com.subhub.app.security.HardcoreModeManager.KEY_REQUESTED, false);
        return new Inputs(chosen.toString(), consent, requested);
    }
    static String digest(String value) { return RemoteProtocol.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    Observation observe() {
        if (Thread.holdsLock(store) || Thread.holdsLock(SubFeaturePolicy.LOCK) || Thread.holdsLock(RuntimeSessionIdentity.LOCK))
            throw new IllegalStateException("Observe local authority outside locks");
        FormalRelationshipClock.Sample before = clock.observe();
        KeyholderProvider provider = People.keyholder();
        String witness = credentials();
        if (provider.state() != KeyholderProvider.State.NONE
                || !ControllerPinManager.isConfigured(context) && !new ControllerAuthenticator(context).isPaired())
            throw new SecurityException("Local controller credentials required");
        Inputs inputs;
        synchronized (SubFeaturePolicy.LOCK) { inputs = inputs(); }
        // DevicePolicyManager may cross a Binder boundary; acquire it outside every authority lock.
        boolean adminActive = inputs.consent && inputs.requested
                && new com.subhub.app.security.HardcoreModeManager(context).isAdminActive();
        Observation result = new Observation(provider, witness, inputs, adminActive, before);
        FormalRelationshipClock.Sample after = clock.observe();
        synchronized (SubFeaturePolicy.LOCK) {
            if (!inputs.same(inputs())) throw new SecurityException("Local consent changed during observation");
        }
        if (!FormalRelationshipClock.consistent(before, after) || after.elapsed - before.elapsed > 5000
                || !witness.equals(credentials()) || People.keyholder() != provider)
            throw new SecurityException("Local controller authority changed");
        return result;
    }
    /** No platform/provider/Keystore query under these locks; time is a fresh cheap transition sample. */
    void require(Observation observed, FormalRelationshipClock.Sample current) {
        if (!Thread.holdsLock(SubFeaturePolicy.LOCK) || !Thread.holdsLock(store)
                || !Thread.holdsLock(RuntimeSessionIdentity.LOCK)) throw new IllegalStateException("Authority lock order required");
        if (observed == null || People.keyholder() != observed.provider || !ControllerPinManager.isDomModeActive()
                || DeviceRolePreferences.get(context) != DeviceRolePreferences.Mode.BOTH
                || !observed.credentials.equals(credentials()) || !observed.inputs.same(inputs())
                || !observed.scope.equals(observed.inputs.scope(observed.adminActive))
                || !FormalRelationshipClock.consistent(observed.clock, current)
                || current.elapsed - observed.clock.elapsed > 5000)
            throw new SecurityException("Local controller authority changed");
        FormalRelationshipStore.rejectOtherLegacyDom(store.getWritableDatabase(), "", "");
    }
    String observedScope(Observation observed) { return observed.scope; }
    FormalRelationshipClock.Sample anchor(Observation observed) { return observed.clock; }
    boolean sameSource(Observation original, Observation current) {
        return original != null && current != null && original.provider == current.provider
                && original.credentials.equals(current.credentials) && original.scope.equals(current.scope);
    }
}
