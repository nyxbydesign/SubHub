package com.subhub.app.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Separate non-exportable store; consuming a code and recording replay state is synchronous. */
public final class ControllerAuthenticator {
    public enum Result { SUCCESS, INVALID, THROTTLED, UNAVAILABLE }
    private static final Object LOCK = new Object();
    private static final String ALIAS = "subhub.controller.totp.v1";
    private final Context context;
    private final SharedPreferences prefs;
    public ControllerAuthenticator(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("subhub_controller_auth", Context.MODE_PRIVATE);
    }
    public boolean isPaired() { return prefs.contains("encrypted_secret"); }
    public Result pair(String secret, String confirmation) {
        synchronized (LOCK) {
            if (!ControllerPinManager.isDomModeActive()) return Result.UNAVAILABLE;
            if (remainingCooldownMillis() > 0) return Result.THROTTLED;
            try {
                long step = Totp.verify(secret, confirmation, System.currentTimeMillis(), -1);
                if (step < 0) { failAttempt(); return Result.INVALID; }
                String encrypted = encrypt(secret);
                if (!prefs.edit().putString("encrypted_secret", encrypted).putLong("last_step", step)
                        .putInt("attempts", 0).remove("blocked_wall").remove("blocked_elapsed").commit()) return Result.UNAVAILABLE;
                return Result.SUCCESS;
            } catch (GeneralSecurityException | IllegalArgumentException error) { return Result.UNAVAILABLE; }
        }
    }
    public Result verify(String code) {
        synchronized (LOCK) {
            if (!isPaired()) return Result.UNAVAILABLE;
            if (remainingCooldownMillis() > 0) return Result.THROTTLED;
            try {
                long step = Totp.verify(decrypt(), code, System.currentTimeMillis(), prefs.getLong("last_step", -1));
                if (step < 0) { failAttempt(); return Result.INVALID; }
                if (!prefs.edit().putLong("last_step", step).putInt("attempts", 0)
                        .remove("blocked_wall").remove("blocked_elapsed").commit()) return Result.UNAVAILABLE;
                ControllerPinManager.enterDomMode(); return Result.SUCCESS;
            } catch (GeneralSecurityException | IllegalArgumentException error) { return Result.UNAVAILABLE; }
        }
    }
    public boolean remove() {
        synchronized (LOCK) {
            if (!ControllerPinManager.isDomModeActive()) return false;
            return prefs.edit().clear().commit();
        }
    }
    public long remainingCooldownMillis() {
        long deadline = prefs.getLong("blocked_wall", 0);
        if (deadline == 0) return 0;
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        long remaining = boot >= 0 && prefs.getInt("blocked_boot", -2) == boot
                ? prefs.getLong("blocked_elapsed", 0) - SystemClock.elapsedRealtime()
                : deadline - System.currentTimeMillis();
        return Math.max(0, Math.min(15 * 60_000L, remaining));
    }
    private void failAttempt() {
        int attempts = Math.min(20, prefs.getInt("attempts", 0) + 1);
        SharedPreferences.Editor edit = prefs.edit().putInt("attempts", attempts);
        if (attempts >= 5) {
            long delay = Math.min(15 * 60_000L, 30_000L << Math.min(5, attempts - 5));
            edit.putLong("blocked_wall", System.currentTimeMillis() + delay)
                    .putLong("blocked_elapsed", SystemClock.elapsedRealtime() + delay)
                    .putInt("blocked_boot", Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1));
        }
        if (!edit.commit()) throw new IllegalStateException("Could not persist authentication attempt");
    }
    private static SecretKey key() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try { store.load(null); } catch (java.io.IOException error) { throw new GeneralSecurityException(error); }
        java.security.Key stored = store.getKey(ALIAS, null);
        if (stored != null) return (SecretKey) stored;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    private static String encrypt(String secret) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.US_ASCII));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }
    private String decrypt() throws GeneralSecurityException {
        String[] parts = prefs.getString("encrypted_secret", "").split("\\.");
        if (parts.length != 2) throw new GeneralSecurityException("Authenticator unavailable");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        byte[] plain = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
        try { return new String(plain, StandardCharsets.US_ASCII); }
        finally { java.util.Arrays.fill(plain, (byte) 0); }
    }
}
