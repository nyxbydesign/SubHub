package com.subhub.app.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Installation secrets are encrypted by a non-exportable Android Keystore wrapping key. */
public final class RemoteSecrets {
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;
    private final String alias;

    public RemoteSecrets(Context context) {
        this(context,"subhub_remote_secrets_v1","subhub.remote.wrap.v1");
    }

    RemoteSecrets(Context context,String preferencesName,String alias) {
        prefs = context.getApplicationContext().getSharedPreferences(preferencesName,0);
        this.alias = alias;
    }

    public synchronized byte[] identitySeed() throws GeneralSecurityException {
        synchronized (LOCK) {
            byte[] stored = read("identity");
            if (stored != null) {
                if (stored.length != 32) throw new GeneralSecurityException("Invalid identity storage");
                return stored;
            }
            byte[] seed = new byte[32];
            new SecureRandom().nextBytes(seed);
            try { write("identity", seed); return seed.clone(); }
            finally { Arrays.fill(seed, (byte) 0); }
        }
    }

    public synchronized void savePending(PairingInvite invite) throws GeneralSecurityException {
        synchronized (LOCK) {
            write("pending_" + invite.id, invite.link().getBytes(StandardCharsets.UTF_8));
        }
    }
    public synchronized String pendingLink(String generation) throws GeneralSecurityException {
        synchronized (LOCK) {
            byte[] value = read("pending_" + RemoteProtocol.identifier(generation));
            if (value == null) return null;
            try { return new String(value, StandardCharsets.UTF_8); }
            finally { Arrays.fill(value, (byte) 0); }
        }
    }
    public synchronized void removePending(String generation) {
        if (!prefs.edit().remove("pending_" + RemoteProtocol.identifier(generation)).commit()) {
            throw new IllegalStateException("Could not remove pairing secret");
        }
    }

    private SecretKey key() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try { store.load(null); }
        catch (IOException error) { throw new GeneralSecurityException("Keystore unavailable", error); }
        java.security.Key key = store.getKey(alias, null);
        if (key instanceof SecretKey) return (SecretKey) key;
        if (!prefs.getAll().isEmpty()) throw new GeneralSecurityException("Remote identity key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private void write(String slot, byte[] value) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(slot.getBytes(StandardCharsets.UTF_8));
        String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                + Base64.encodeToString(cipher.doFinal(value), Base64.NO_WRAP);
        if (!prefs.edit().putString(slot, encoded).commit()) throw new GeneralSecurityException("Secret storage unavailable");
    }
    private byte[] read(String slot) throws GeneralSecurityException {
        String stored = prefs.getString(slot, null);
        if (stored == null) return null;
        try {
            String[] fields = stored.split("\\.", -1);
            if (fields.length != 2) throw new GeneralSecurityException("Invalid secret storage");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(fields[0], Base64.NO_WRAP)));
            cipher.updateAAD(slot.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(Base64.decode(fields[1], Base64.NO_WRAP));
        } catch (IllegalArgumentException invalid) { throw new GeneralSecurityException("Invalid secret storage"); }
    }
}
