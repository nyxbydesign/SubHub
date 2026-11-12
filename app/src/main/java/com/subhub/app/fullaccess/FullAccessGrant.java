package com.subhub.app.fullaccess;

import android.content.Context;
import android.content.SharedPreferences;

import com.subhub.app.remote.RemoteFeature;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.HardcoreModeManager;

import java.security.SecureRandom;

/** Receiver-owned consent. A pairing generation, rather than a profile name, identifies its recipient. */
public final class FullAccessGrant {
    private static final String SETTINGS = "subhub_full_access_grant";
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static boolean clearing;
    private static boolean revokedInProcess;

    private FullAccessGrant() { }

    public static boolean eligible(Context context) {
        return localChoiceAllowed(context) && selectedDom(context) != null;
    }

    public static String pairedDom(Context context) {
        RemoteStore.Peer dom = selectedDom(context);
        return dom == null ? "" : dom.name;
    }

    public static String pairedDomKey(Context context) {
        RemoteStore.Peer dom = selectedDom(context);
        return dom == null ? "" : key(dom);
    }

    public static boolean enable(Context context, String expectedDomKey) {
        if (!localChoiceAllowed(context) || expectedDomKey == null || expectedDomKey.isEmpty()) return false;
        RemoteStore.Peer dom = selectedDom(context);
        if (dom == null || !expectedDomKey.equals(key(dom))) return false;
        synchronized (LOCK) {
            if (clearing || !localChoiceAllowed(context)) return false;
            SharedPreferences preferences = preferences(context);
            if (!revokedInProcess && expectedDomKey.equals(preferences.getString("recipient", ""))
                    && preferences.getLong("epoch", -1) > 0) return true;
            long epoch;
            do { epoch = RANDOM.nextLong() & Long.MAX_VALUE; } while (epoch == 0);
            boolean saved = preferences.edit().putString("recipient", expectedDomKey)
                    .putLong("epoch", epoch).commit();
            revokedInProcess = !saved;
            return saved;
        }
    }

    public static boolean isLocallyEnabled(Context context) { return epoch(context) >= 0; }

    public static long epoch(Context context) {
        RemoteStore.Peer dom = selectedDom(context);
        return dom == null ? -1 : epoch(context, dom.identity, dom.generation);
    }

    /** Used only with a peer obtained from the authenticated relationship store. */
    public static long epoch(Context context, String identity, String generation) {
        if (!DeviceRolePreferences.supportsSub(context) || !new HardcoreModeManager(context).isEnabled())
            return -1;
        synchronized (LOCK) {
            if (revokedInProcess || clearing) return -1;
            SharedPreferences state = preferences(context);
            return (identity + ":" + generation).equals(state.getString("recipient", ""))
                    ? state.getLong("epoch", -1) : -1;
        }
    }

    public static boolean disable(Context context) {
        return clearIfSelected(context, null);
    }

    /** An obsolete relationship callback cannot clear a newly reviewed grant. */
    public static void relationshipEnded(Context context, String identity, String generation) {
        clearIfSelected(context, identity + ":" + generation);
    }

    private static boolean clearIfSelected(Context context, String expectedKey) {
        boolean saved;
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            String recipient = preferences.getString("recipient", "");
            if (clearing) return false;
            if (recipient.isEmpty() || expectedKey != null && !expectedKey.equals(recipient)) return true;
            revokedInProcess = true;
            clearing = true;
            saved = preferences.edit().clear().commit();
            if (!saved) saved = context.deleteSharedPreferences(SETTINGS);
        }
        try {
            RemoteFeature.get(context).closeFullAccessShares();
            FullAccessDataSources.clear(context);
        } finally {
            synchronized (LOCK) { clearing = false; }
        }
        return saved;
    }

    private static boolean localChoiceAllowed(Context context) {
        return DeviceRolePreferences.supportsSub(context) && !DeviceRolePreferences.isDomWorkspace(context)
                && new HardcoreModeManager(context).isEnabled();
    }

    private static RemoteStore.Peer selectedDom(Context context) {
        try { return RemoteFeature.get(context).fullAccessSubPeer(); }
        catch (RuntimeException unavailable) { return null; }
    }

    private static String key(RemoteStore.Peer peer) { return peer.identity + ":" + peer.generation; }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
    }
}
