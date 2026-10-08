package com.subhub.app.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.subhub.app.settings.SettingsRepository;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Salted, app-local controller PIN with a process-local Dom-mode session. */
public final class ControllerPinManager {
    private static final String KEY_SALT = "controller_pin_salt";
    private static final String KEY_HASH = "controller_pin_hash";
    private static final int ITERATIONS = 150_000;
    private static final int KEY_BITS = 256;
    private static volatile boolean domModeActive;

    private ControllerPinManager() {}

    public static boolean isConfigured(Context context) {
        SharedPreferences preferences = preferences(context);
        return preferences.contains(KEY_SALT) && preferences.contains(KEY_HASH);
    }

    public static boolean hasCredentials(Context context) {
        return isConfigured(context) || new ControllerAuthenticator(context).isPaired();
    }
    public static boolean allowsUnkeyedAccess(Context context) {
        return !hasCredentials(context) && preferences(context).getBoolean("controller_keyholder_optional", false);
    }
    static boolean allowUnkeyedAccess(Context context) {
        return preferences(context).edit().putBoolean("controller_keyholder_optional", true).commit();
    }
    public static boolean removePin(Context context) {
        if (!isDomModeActive()) return false;
        return preferences(context).edit().remove(KEY_SALT).remove(KEY_HASH)
                .putBoolean("controller_keyholder_optional", true).commit();
    }

    public static boolean setPin(Context context, String pin) {
        String value = normalize(pin);
        if (!value.matches("[0-9]{4,12}")) return false;
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        try {
            byte[] hash = derive(value, salt);
            boolean saved = preferences(context).edit()
                    .putBoolean("controller_keyholder_optional", false)
                    .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                    .commit();
            if (!saved) return false;
            enterDomMode();
            return true;
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    public static boolean verify(Context context, String pin) {
        if (!isConfigured(context)) return false;
        try {
            SharedPreferences preferences = preferences(context);
            byte[] salt = Base64.decode(preferences.getString(KEY_SALT, ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(preferences.getString(KEY_HASH, ""), Base64.NO_WRAP);
            byte[] actual = derive(normalize(pin), salt);
            boolean matches = MessageDigest.isEqual(expected, actual);
            if (matches) enterDomMode();
            return matches;
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return false;
        }
    }

    public static boolean isSessionUnlocked() {
        return isDomModeActive();
    }

    public static void lockNow() {
        enterSubMode();
    }

    public static boolean isDomModeActive() {
        return domModeActive;
    }

    public static void enterDomMode() {
        domModeActive = true;
    }

    public static void enterSubMode() {
        domModeActive = false;
    }

    private static String normalize(String pin) {
        return pin == null ? "" : pin.trim();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static byte[] derive(String pin, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
