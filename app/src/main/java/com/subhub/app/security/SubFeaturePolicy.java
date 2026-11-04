package com.subhub.app.security;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.settings.SettingsRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Receiver-owned choices are separate from all remotely writable profile stores. */
public final class SubFeaturePolicy {
    public static final Object LOCK = new Object();
    public static final String STORE = "subhub_local_feature_consent";
    public static final Set<String> FEATURES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("censor", "limits", "wallet", "subliminal", "popup")));
    private static final String HARDCORE = "hardcore_locally_consented";
    private static final String START_PROOF = "hardcore_start_proof_v1";
    private static final String START_REVIEW = "hardcore_start_accepted_review";
    private static final java.util.concurrent.ConcurrentHashMap<Context, com.subhub.app.remote.RemoteStore> authorities =
            new java.util.concurrent.ConcurrentHashMap<>();
    private SubFeaturePolicy() { }

    public static boolean chosen(Context context, String feature) {
        requireFeature(feature);
        return preferences(context).getBoolean(feature, true);
    }
    /** Compatibility observation: Hardcore never overrides locally selected features. */
    public static boolean hardcoreOverride(Context context) {
        return false;
    }
    public static boolean allowed(Context context, String feature) {
        return chosen(context, feature);
    }
    public static boolean setAllowed(Context context, String feature, boolean allowed) {
        requireFeature(feature);
        synchronized (LOCK) {
            if (!DeviceRolePreferences.supportsSub(context)) return false;
            boolean wasAllowed = allowed(context, feature);
            SharedPreferences.Editor edit = preferences(context).edit().putBoolean(feature, allowed);
            if ("tasks".equals(feature) && allowed && !wasAllowed)
                edit.putLong("tasks_resume_after", System.currentTimeMillis());
            if (!edit.commit()) return false;
            changed(context);
            return true;
        }
    }
    public static boolean consentHardcore(Context context, boolean consent) {
        synchronized (LOCK) {
            if (consent && (!DeviceRolePreferences.supportsSub(context)
                    || DeviceRolePreferences.isDomWorkspace(context))) return false;
            boolean cleared = true;
            SharedPreferences.Editor edit = preferences(context).edit().putBoolean(HARDCORE, consent);
            if (!consent) {
                com.subhub.app.remote.RemoteStore authority = authorities.get(context.getApplicationContext());
                if (authority != null) {
                    try { authority.clearHardcoreStartAcknowledgements(); }
                    catch (RuntimeException unavailable) { cleared = false; }
                }
                edit.remove(START_PROOF).remove(START_REVIEW);
            }
            boolean saved = edit.commit();
            if (saved) changed(context);
            return saved && cleared;
        }
    }

    /** Registration performs no store access and adds no consent lock to application initialization. */
    public static void registerHardcoreAuthority(Context context, com.subhub.app.remote.RemoteStore store) {
        authorities.putIfAbsent(context.getApplicationContext(), store);
    }
    public static void unregisterHardcoreAuthority(Context context, com.subhub.app.remote.RemoteStore store) {
        authorities.remove(context.getApplicationContext(), store);
    }
    public static long hardcoreReviewRevision(Context context) {
        return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getLong("sub_feature_policy_revision", 0);
    }
    public static String hardcoreReviewDigest(Context context) throws org.json.JSONException {
        synchronized (LOCK) {
            org.json.JSONObject value = new org.json.JSONObject().put("hardcore", preferences(context).getBoolean(HARDCORE, false))
                    .put("revision", hardcoreReviewRevision(context));
            for (String feature : FEATURES) value.put(feature, chosen(context, feature));
            value.put("tasks", chosen(context, "tasks"));
            return com.subhub.app.remote.RemoteProtocol.sha256(com.subhub.app.remote.RemoteProtocol.canonical(value)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** OS observations precede these locks; the app-owned scope is checked again at acceptance. */
    public static boolean acceptHardcoreStartReview(Context context, com.subhub.app.remote.RemoteStore store,
            com.subhub.app.remote.RemoteSessionRuntime.HardcoreReview review,
            com.subhub.app.remote.RemoteSessionRuntime.SystemObservation observed) throws org.json.JSONException {
        java.util.Objects.requireNonNull(review); java.util.Objects.requireNonNull(observed);
        long elapsed = android.os.SystemClock.elapsedRealtime();
        if (!observed.adminActive || observed.clock.wall <= 0 || observed.clock.boot < 0
                || observed.clock.elapsed < review.preparedElapsed || review.preparedElapsed < 0
                || observed.clock.boot != review.boot || elapsed < observed.clock.elapsed
                || elapsed - observed.clock.elapsed > 5000 || observed.clock.wall < review.createdAt
                || !review.currentProcess() || !observed.self.equals(review.recipient)
                || review.consentVersion != com.subhub.app.remote.RemoteSessionRuntime.HARDCORE_START_CONSENT_VERSION)
            return false;
        synchronized (LOCK) {
            synchronized (store) {
                long acquired = android.os.SystemClock.elapsedRealtime();
                if (acquired < observed.clock.elapsed || acquired - observed.clock.elapsed > 5000) return false;
                if (authorities.get(context.getApplicationContext()) != store || !DeviceRolePreferences.supportsSub(context)
                        || DeviceRolePreferences.isDomWorkspace(context)
                        || !context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0)
                                .getBoolean(HardcoreModeManager.KEY_REQUESTED, false)) return false;
                com.subhub.app.remote.RemoteStore.Peer peer = store.peer(review.dom, review.generation);
                if (peer == null || peer.state != com.subhub.app.remote.RemoteStore.State.ACTIVE
                        || peer.localRole != com.subhub.app.remote.RemoteProtocol.Role.SUB) return false;
                SharedPreferences saved = preferences(context);
                com.subhub.app.remote.RemoteSessionRuntime.HardcoreConsent current = hardcoreStartConsent(context, store, observed.self);
                if (current != null && review.reviewId.equals(saved.getString(START_REVIEW, ""))) return true;
                if (review.policyRevision != hardcoreReviewRevision(context)
                        || !review.policyDigest.equals(hardcoreReviewDigest(context))) return false;
                com.subhub.app.remote.RemoteSessionRuntime.HardcoreConsent proof =
                        new com.subhub.app.remote.RemoteSessionRuntime.HardcoreConsent(review.dom, review.recipient,
                                review.generation, observed.clock.wall);
                android.database.sqlite.SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
                try {
                    boolean committed = saved.edit().putBoolean(HARDCORE, true)
                            .putString(START_PROOF, com.subhub.app.remote.RemoteProtocol.canonical(proof.json()))
                            .putString(START_REVIEW, review.reviewId).commit();
                    if (!committed) {
                        // SharedPreferences can change memory before reporting an unacknowledged disk write.
                        saved.edit().remove(START_PROOF).remove(START_REVIEW).commit();
                        return false;
                    }
                    store.acknowledgeHardcoreStartReview(review.dom, review.generation, review.reviewId);
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                changed(context); return true;
            }
        }
    }

    /** A preference write alone grants nothing: its exact review also needs the authority-store receipt. */
    public static com.subhub.app.remote.RemoteSessionRuntime.HardcoreConsent hardcoreStartConsent(Context context,
            com.subhub.app.remote.RemoteStore store, String self) {
        synchronized (LOCK) {
            synchronized (store) {
                try {
                    SharedPreferences saved = preferences(context);
                    if (!saved.getBoolean(HARDCORE, false)
                            || !context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0)
                                    .getBoolean(HardcoreModeManager.KEY_REQUESTED, false)) return null;
                    String review = saved.getString(START_REVIEW, "");
                    if (review.isEmpty()) return null;
                    com.subhub.app.remote.RemoteSessionRuntime.HardcoreConsent proof =
                            com.subhub.app.remote.RemoteSessionRuntime.HardcoreConsent.parseStored(
                                    new org.json.JSONObject(saved.getString(START_PROOF, "")));
                    if (!self.equals(proof.recipient)) return null;
                    com.subhub.app.remote.RemoteStore.Peer peer = store.peer(proof.dom, proof.generation);
                    if (peer == null || peer.state != com.subhub.app.remote.RemoteStore.State.ACTIVE
                            || peer.localRole != com.subhub.app.remote.RemoteProtocol.Role.SUB
                            || !review.equals(store.metadata(proof.dom, proof.generation, "hardcore_start_review_ack", ""))) return null;
                    return proof;
                } catch (org.json.JSONException | IllegalArgumentException | ClassCastException invalid) { return null; }
            }
        }
    }
    public static void changed(Context context) {
        SharedPreferences settings = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        settings.edit().putLong("sub_feature_policy_revision", settings.getLong("sub_feature_policy_revision", 0) + 1).apply();
    }
    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences(STORE, 0); }
    private static void requireFeature(String feature) {
        if (!FEATURES.contains(feature) && !"tasks".equals(feature)) throw new IllegalArgumentException("Unknown feature");
    }
}
