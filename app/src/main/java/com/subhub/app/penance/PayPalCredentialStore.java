package com.subhub.app.penance;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted per-install PayPal credentials and environment-bound vault authorization state. */
public final class PayPalCredentialStore {
    public static final String PREFS_NAME = "paypal_sandbox_credentials";
    private static final String KEY_ENVIRONMENT = "environment";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_SECRET = "client_secret";
    private static final String KEY_VERIFIED_BOUNDARY = "verified_boundary";
    private static final String LEGACY_KEY_VAULT_REQUESTED = "vault_requested";
    private static final String KEY_VAULT_STATUS = "vault_status";
    private static final String KEY_VAULT_ID = "vault_id";
    private static final String KEY_CUSTOMER_ID = "customer_id";
    private static final String KEY_PAYER_EMAIL = "payer_email";
    private static final String KEY_PAYER_ACCOUNT_ID = "payer_account_id";
    private static final String KEY_VAULT_BOUNDARY = "vault_boundary";
    private static final String KEY_SETUP_TOKEN_ID = "vault_setup_token_id";
    private static final String KEY_SETUP_CUSTOMER_ID = "vault_setup_customer_id";
    private static final String KEY_SETUP_METADATA_ID = "vault_setup_metadata_id";
    private static final String KEY_SETUP_APPROVAL_URL = "vault_setup_approval_url";
    private static final String KEY_SETUP_BOUNDARY = "vault_setup_boundary";
    private static final String KEY_ALIAS = "subhub_paypal_sandbox_v1";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private final Context context;

    public PayPalCredentialStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public PayPalEnvironment selectedEnvironment() {
        return PayPalEnvironment.stored(preferences().getString(
                KEY_ENVIRONMENT, PayPalEnvironment.SANDBOX.name()));
    }

    /** Selecting another environment immediately ends the old credential/vault boundary. */
    public void selectEnvironment(PayPalEnvironment environment) {
        PayPalEnvironment selected = environment == null
                ? PayPalEnvironment.SANDBOX : environment;
        if (selected == selectedEnvironment()) return;
        preferences().edit().clear()
                .putString(KEY_ENVIRONMENT, selected.name()).commit();
    }

    public Credentials load() {
        PayPalEnvironment environment = selectedEnvironment();
        SharedPreferences preferences = preferences();
        String clientId = decrypt(preferences.getString(KEY_CLIENT_ID, ""));
        String secret = decrypt(preferences.getString(KEY_SECRET, ""));
        return new Credentials(environment, clientId, secret);
    }

    public boolean hasCredentials() {
        return load().isComplete();
    }

    /** True only after PayPal has accepted this exact environment/client boundary. */
    public boolean hasVerifiedCredentials() {
        Credentials credentials = load();
        if (!credentials.isComplete()) return false;
        String verified = decrypt(preferences().getString(KEY_VERIFIED_BOUNDARY, ""));
        return credentials.boundaryId().equals(verified);
    }

    public boolean markCredentialsVerified() {
        Credentials credentials = load();
        if (!credentials.isComplete()) return false;
        String verified = encrypt(credentials.boundaryId());
        return !verified.isEmpty() && preferences().edit()
                .putString(KEY_VERIFIED_BOUNDARY, verified).commit();
    }

    public boolean save(PayPalEnvironment environment, String clientId, String secret) {
        PayPalEnvironment selected = environment == null
                ? PayPalEnvironment.SANDBOX : environment;
        String cleanId = clientId == null ? "" : clientId.trim();
        String cleanSecret = secret == null ? "" : secret.trim();
        if (cleanId.isEmpty() || cleanSecret.isEmpty()) return false;
        String encryptedId = encrypt(cleanId);
        String encryptedSecret = encrypt(cleanSecret);
        if (encryptedId.isEmpty() || encryptedSecret.isEmpty()) return false;
        Credentials old = load();
        boolean boundaryChanged = !old.boundaryId().equals(
                Credentials.boundaryId(selected, cleanId));
        SharedPreferences.Editor editor = preferences().edit()
                .putString(KEY_ENVIRONMENT, selected.name())
                .putString(KEY_CLIENT_ID, encryptedId)
                .putString(KEY_SECRET, encryptedSecret)
                .remove(KEY_VERIFIED_BOUNDARY);
        if (boundaryChanged) clearVault(editor);
        return editor.commit();
    }

    /** Imports merchant credentials without retaining any local verification or payer state. */
    public boolean saveImported(PayPalEnvironment environment, String clientId, String secret) {
        if (environment == null || clientId == null || clientId.isEmpty()
                || secret == null || secret.isEmpty()) return false;
        String encryptedId = encrypt(clientId);
        String encryptedSecret = encrypt(secret);
        if (encryptedId.isEmpty() || encryptedSecret.isEmpty()) return false;
        // Even the same merchant must not inherit this device's old payer authorization.
        return preferences().edit().clear().putString(KEY_ENVIRONMENT, environment.name())
                .putString(KEY_CLIENT_ID, encryptedId).putString(KEY_SECRET, encryptedSecret).commit();
    }

    /** Local rollback only: values remain Android-Keystore ciphertext; never put this in a pack. */
    public org.json.JSONObject encryptedLocalSnapshot() throws org.json.JSONException {
        org.json.JSONObject snapshot = new org.json.JSONObject();
        for (String key : localKeys()) {
            Object value = preferences().getAll().get(key);
            if (value instanceof String) snapshot.put(key, value);
        }
        return snapshot;
    }

    public boolean restoreEncryptedLocalSnapshot(org.json.JSONObject snapshot) {
        if (snapshot == null) return false;
        SharedPreferences.Editor editor = preferences().edit().clear();
        java.util.Iterator<String> keys = snapshot.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!localKeys().contains(key) || !(snapshot.opt(key) instanceof String)) return false;
            editor.putString(key, snapshot.optString(key));
        }
        return editor.commit();
    }

    private static java.util.Set<String> localKeys() {
        return java.util.Set.of(KEY_ENVIRONMENT, KEY_CLIENT_ID, KEY_SECRET, KEY_VERIFIED_BOUNDARY,
                KEY_VAULT_STATUS, KEY_VAULT_ID, KEY_CUSTOMER_ID, KEY_PAYER_EMAIL,
                KEY_PAYER_ACCOUNT_ID, KEY_VAULT_BOUNDARY, KEY_SETUP_TOKEN_ID,
                KEY_SETUP_CUSTOMER_ID, KEY_SETUP_METADATA_ID, KEY_SETUP_APPROVAL_URL,
                KEY_SETUP_BOUNDARY);
    }

    /** Disconnects credentials and vault state while retaining the selected environment. */
    public void clear() {
        PayPalEnvironment selected = selectedEnvironment();
        preferences().edit().clear()
                .putString(KEY_ENVIRONMENT, selected.name()).commit();
    }

    public VaultState vaultState() {
        Credentials credentials = load();
        if (!credentials.isComplete()) return VaultState.disconnected();
        String boundary = decrypt(preferences().getString(KEY_VAULT_BOUNDARY, ""));
        if (!boundary.isEmpty() && !credentials.boundaryId().equals(boundary)) {
            clearVault(preferences().edit()).commit();
            return VaultState.requested();
        }
        VaultStatus status = VaultStatus.stored(
                preferences().getString(KEY_VAULT_STATUS, VaultStatus.REQUESTED.name()));
        String vaultId = decrypt(preferences().getString(KEY_VAULT_ID, ""));
        String customerId = decrypt(preferences().getString(KEY_CUSTOMER_ID, ""));
        String payerEmail = decrypt(preferences().getString(KEY_PAYER_EMAIL, ""));
        String payerAccountId = decrypt(preferences().getString(KEY_PAYER_ACCOUNT_ID, ""));
        if (status == VaultStatus.READY && (vaultId.isEmpty() || customerId.isEmpty())) {
            status = VaultStatus.PENDING;
        }
        return new VaultState(status, vaultId, customerId,
                PayPalVaultPolicy.maskedPayer(payerEmail, payerAccountId));
    }

    public void recordVaultResult(Credentials credentials, String rawStatus,
            String vaultId, String customerId, String payerEmail, String payerAccountId) {
        if (credentials == null || !credentials.isComplete()) return;
        String cleanVaultId = vaultId == null ? "" : vaultId.trim();
        String cleanCustomerId = customerId == null ? "" : customerId.trim();
        VaultStatus status = PayPalVaultPolicy.resultStatus(
                rawStatus, cleanVaultId, cleanCustomerId);
        SharedPreferences.Editor editor = preferences().edit()
                .putString(KEY_VAULT_STATUS, status.name())
                .putString(KEY_VAULT_BOUNDARY, encrypt(credentials.boundaryId()))
                .remove(LEGACY_KEY_VAULT_REQUESTED);
        if (!cleanVaultId.isEmpty()) editor.putString(KEY_VAULT_ID, encrypt(cleanVaultId));
        if (!cleanCustomerId.isEmpty()) {
            editor.putString(KEY_CUSTOMER_ID, encrypt(cleanCustomerId));
        }
        String cleanEmail = payerEmail == null ? "" : payerEmail.trim();
        String cleanAccount = payerAccountId == null ? "" : payerAccountId.trim();
        if (!cleanEmail.isEmpty()) editor.putString(KEY_PAYER_EMAIL, encrypt(cleanEmail));
        if (!cleanAccount.isEmpty()) {
            editor.putString(KEY_PAYER_ACCOUNT_ID, encrypt(cleanAccount));
        }
        clearPendingVault(editor);
        editor.commit();
    }

    public boolean recordPendingVaultSetup(Credentials credentials, String setupTokenId,
            String customerId, String clientMetadataId, String approvalUrl) {
        if (credentials == null || !credentials.isComplete()) return false;
        String cleanToken = setupTokenId == null ? "" : setupTokenId.trim();
        if (cleanToken.isEmpty()) return false;
        SharedPreferences.Editor editor = preferences().edit()
                .putString(KEY_VAULT_STATUS, VaultStatus.PENDING.name())
                .putString(KEY_VAULT_BOUNDARY, encrypt(credentials.boundaryId()))
                .putString(KEY_SETUP_TOKEN_ID, encrypt(cleanToken))
                .putString(KEY_SETUP_CUSTOMER_ID,
                        encrypt(customerId == null ? "" : customerId.trim()))
                .putString(KEY_SETUP_METADATA_ID,
                        encrypt(clientMetadataId == null ? "" : clientMetadataId.trim()))
                .putString(KEY_SETUP_APPROVAL_URL,
                        encrypt(approvalUrl == null ? "" : approvalUrl.trim()))
                .putString(KEY_SETUP_BOUNDARY, encrypt(credentials.boundaryId()));
        return editor.commit();
    }

    public PendingVaultSetup pendingVaultSetup() {
        Credentials credentials = load();
        String boundary = decrypt(preferences().getString(KEY_SETUP_BOUNDARY, ""));
        if (!credentials.isComplete() || !credentials.boundaryId().equals(boundary)) {
            clearPendingVault(preferences().edit()).commit();
            return PendingVaultSetup.empty();
        }
        return new PendingVaultSetup(
                decrypt(preferences().getString(KEY_SETUP_TOKEN_ID, "")),
                decrypt(preferences().getString(KEY_SETUP_CUSTOMER_ID, "")),
                decrypt(preferences().getString(KEY_SETUP_METADATA_ID, "")),
                decrypt(preferences().getString(KEY_SETUP_APPROVAL_URL, "")), boundary);
    }

    public void clearPendingVaultSetup() {
        clearPendingVault(preferences().edit()).commit();
        if (!vaultState().isReady()) {
            preferences().edit().putString(
                    KEY_VAULT_STATUS, VaultStatus.REQUESTED.name()).commit();
        }
    }

    public void markVaultUnavailable(Credentials credentials) {
        if (credentials == null || !credentials.isComplete()) return;
        SharedPreferences.Editor editor = preferences().edit()
                .putString(KEY_VAULT_STATUS, VaultStatus.UNAVAILABLE.name())
                .putString(KEY_VAULT_BOUNDARY, encrypt(credentials.boundaryId()))
                .remove(KEY_VAULT_ID).remove(KEY_CUSTOMER_ID)
                .remove(KEY_PAYER_EMAIL).remove(KEY_PAYER_ACCOUNT_ID);
        clearPendingVault(editor).commit();
    }

    private static SharedPreferences.Editor clearVault(SharedPreferences.Editor editor) {
        return editor.remove(LEGACY_KEY_VAULT_REQUESTED).remove(KEY_VAULT_STATUS)
                .remove(KEY_VAULT_ID).remove(KEY_CUSTOMER_ID).remove(KEY_PAYER_EMAIL)
                .remove(KEY_PAYER_ACCOUNT_ID).remove(KEY_VAULT_BOUNDARY)
                .remove(KEY_SETUP_TOKEN_ID).remove(KEY_SETUP_CUSTOMER_ID)
                .remove(KEY_SETUP_METADATA_ID).remove(KEY_SETUP_APPROVAL_URL)
                .remove(KEY_SETUP_BOUNDARY);
    }

    private static SharedPreferences.Editor clearPendingVault(
            SharedPreferences.Editor editor) {
        return editor.remove(KEY_SETUP_TOKEN_ID).remove(KEY_SETUP_CUSTOMER_ID)
                .remove(KEY_SETUP_METADATA_ID).remove(KEY_SETUP_APPROVAL_URL)
                .remove(KEY_SETUP_BOUNDARY);
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            String[] pieces = encoded.split(":", 2);
            if (pieces.length != 2) return "";
            byte[] iv = Base64.decode(pieces[0], Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(pieces[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    public enum VaultStatus {
        DISCONNECTED, REQUESTED, PENDING, READY, UNAVAILABLE;

        static VaultStatus stored(String value) {
            try { return valueOf(value == null ? "" : value); }
            catch (IllegalArgumentException ignored) { return REQUESTED; }
        }
    }

    public static final class VaultState {
        private final VaultStatus status;
        private final String vaultId;
        private final String customerId;
        private final String maskedPayer;

        private VaultState(VaultStatus status, String vaultId, String customerId,
                String maskedPayer) {
            this.status = status;
            this.vaultId = vaultId;
            this.customerId = customerId;
            this.maskedPayer = maskedPayer;
        }

        static VaultState disconnected() {
            return new VaultState(VaultStatus.DISCONNECTED, "", "", "");
        }
        static VaultState requested() {
            return new VaultState(VaultStatus.REQUESTED, "", "", "");
        }
        public VaultStatus status() { return status; }
        public String vaultId() { return vaultId; }
        public String customerId() { return customerId; }
        public String maskedPayer() { return maskedPayer; }
        public boolean isReady() { return status == VaultStatus.READY; }
    }

    public static final class PendingVaultSetup {
        private final String setupTokenId;
        private final String customerId;
        private final String clientMetadataId;
        private final String approvalUrl;
        private final String boundaryId;

        private PendingVaultSetup(String setupTokenId, String customerId,
                String clientMetadataId, String approvalUrl, String boundaryId) {
            this.setupTokenId = setupTokenId == null ? "" : setupTokenId;
            this.customerId = customerId == null ? "" : customerId;
            this.clientMetadataId = clientMetadataId == null ? "" : clientMetadataId;
            this.approvalUrl = approvalUrl == null ? "" : approvalUrl;
            this.boundaryId = boundaryId == null ? "" : boundaryId;
        }

        static PendingVaultSetup empty() {
            return new PendingVaultSetup("", "", "", "", "");
        }

        public String setupTokenId() { return setupTokenId; }
        public String customerId() { return customerId; }
        public String clientMetadataId() { return clientMetadataId; }
        public String approvalUrl() { return approvalUrl; }
        public String boundaryId() { return boundaryId; }
        public boolean isPresent() { return !setupTokenId.isEmpty(); }
    }

    public static final class Credentials {
        private final PayPalEnvironment environment;
        private final String clientId;
        private final String secret;

        private Credentials(PayPalEnvironment environment, String clientId, String secret) {
            this.environment = environment;
            this.clientId = clientId == null ? "" : clientId;
            this.secret = secret == null ? "" : secret;
        }

        public static Credentials create(
                PayPalEnvironment environment, String clientId, String secret) {
            return new Credentials(environment == null ? PayPalEnvironment.SANDBOX : environment,
                    clientId == null ? "" : clientId.trim(),
                    secret == null ? "" : secret.trim());
        }

        public PayPalEnvironment environment() { return environment; }
        public String clientId() { return clientId; }
        public String secret() { return secret; }
        public boolean isComplete() { return !clientId.isEmpty() && !secret.isEmpty(); }
        public String boundaryId() { return boundaryId(environment, clientId); }

        private static String boundaryId(PayPalEnvironment environment, String clientId) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest((environment.name() + ":" + clientId)
                        .getBytes(StandardCharsets.UTF_8));
                return Base64.encodeToString(hash, Base64.NO_WRAP | Base64.URL_SAFE);
            } catch (Exception ignored) {
                return environment.name() + ":" + clientId;
            }
        }
    }
}
