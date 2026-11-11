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
    private static final java.util.concurrent.CopyOnWriteArrayList<Runnable> sessionObservers=new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final class SessionDispatch {static final android.os.Handler MAIN=new android.os.Handler(android.os.Looper.getMainLooper());}
    private static final java.util.concurrent.atomic.AtomicBoolean sessionNotification=new java.util.concurrent.atomic.AtomicBoolean();

    /** Presentation observers run asynchronously, including when callers retain an outer authority lock. */
    public static AutoCloseable observeSession(Runnable observer){
        java.util.Objects.requireNonNull(observer);sessionObservers.addIfAbsent(observer);
        return ()->sessionObservers.remove(observer);
    }
    private static void sessionChanged(){
        if(!sessionObservers.isEmpty()&&sessionNotification.compareAndSet(false,true))SessionDispatch.MAIN.post(()->{
            sessionNotification.set(false);
            for(Runnable observer:sessionObservers){try{observer.run();}catch(RuntimeException ignored){/* Observer owns failure. */}}
        });
    }
    private static final String KEY_SALT = "controller_pin_salt";
    private static final String KEY_HASH = "controller_pin_hash";
    private static final int ITERATIONS = 150_000;
    private static final int KEY_BITS = 256;
    private static volatile boolean domModeActive;
    private static volatile Context application;
    private static WorkspaceProof localWorkspace;
    private static long workspaceRevision;
    private static com.subhub.app.runtime.RuntimeAdmissionAuthority.Revocation internalStartEpoch =
            new com.subhub.app.runtime.RuntimeAdmissionAuthority.Revocation();
    private static final MutationDomain pinMutations = new MutationDomain();
    private static final MutationDomain authenticatorMutations = new MutationDomain();
    private static final MutationDomain roleMutations = new MutationDomain();
    private static final java.util.Map<Long, MutationOperation> mutations = new java.util.HashMap<>();
    private static long credentialMutation;
    private static final class MutationDomain {
        int outstanding;
        boolean uncertain;
        Object failureEpoch = new Object();
        boolean blocked() { return uncertain || outstanding != 0; }
    }
    private static final class MutationOperation {
        final MutationDomain domain;
        final Object failuresAtStart;
        MutationOperation(MutationDomain domain) { this.domain = domain; failuresAtStart = domain.failureEpoch; }
    }

    /** Start-only evidence. Retiring it never ends an admitted INTERNAL protection session. */
    public static final class InternalStartWitness {
        public final long revision;
        public final com.subhub.app.runtime.RuntimeAdmissionAuthority.Revocation revocation;
        private InternalStartWitness(long revision,
                com.subhub.app.runtime.RuntimeAdmissionAuthority.Revocation revocation) {
            this.revision = revision; this.revocation = revocation;
        }
    }
    /** Owner worker only; actual launch later reads the token's one volatile bit without this lock. */
    public static InternalStartWitness captureInternalStartWitness(Context source) {
        Context physical = java.util.Objects.requireNonNull(source).getApplicationContext();
        if (!(physical instanceof android.app.Application)) throw new SecurityException("Physical application required");
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            if (pinMutations.blocked() || authenticatorMutations.blocked() || roleMutations.blocked()
                    || !isDomWorkspaceUnlocked()
                    || !DeviceRolePreferences.supportsDom(physical))
                throw new SecurityException("Internal start requires current acknowledged controller authority");
            return new InternalStartWitness(workspaceRevision, internalStartEpoch);
        }
    }
    /** Replaces only the cached start epoch; no callbacks, IO or runtime/session operation. */
    public static void invalidateInternalStartAuthority() {
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) { retireInternalStarts(); }
    }
    private static void retireInternalStarts() {
        internalStartEpoch.revoke();
        internalStartEpoch = new com.subhub.app.runtime.RuntimeAdmissionAuthority.Revocation();
    }
    private static long beginCredentialMutation() {
        return beginMutation(pinMutations);
    }
    private static long beginMutation(MutationDomain domain) {
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            long ticket = Math.incrementExact(credentialMutation);
            int outstanding = Math.incrementExact(domain.outstanding);
            retireInternalStarts(); credentialMutation = ticket; domain.outstanding = outstanding;
            mutations.put(ticket, new MutationOperation(domain));
            return ticket;
        }
    }
    private static void credentialMutationReturned(long mutation, boolean acknowledged) {
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            MutationOperation operation = mutations.remove(mutation);
            if (operation == null) throw new IllegalStateException("Unknown credential mutation return");
            MutationDomain domain = operation.domain;
            domain.outstanding--;
            if (!acknowledged) {
                domain.uncertain = true; domain.failureEpoch = new Object();
                retireInternalStarts();
            } else if (domain.outstanding == 0 && operation.failuresAtStart == domain.failureEpoch) {
                // Only a store/domain ACK begun after all known failures can reconcile them.
                // An overlapping late failure is never hidden by a newer successful operation.
                domain.uncertain = false;
            }
        }
    }
    /** Call inside the existing local-authority guard, before changing an authenticator credential. */
    static boolean mutateRuntimeCredential(java.util.function.BooleanSupplier mutation) {
        long ticket = beginMutation(authenticatorMutations);
        boolean acknowledged = false;
        try { acknowledged = mutation.getAsBoolean(); return acknowledged; }
        finally { credentialMutationReturned(ticket, acknowledged); }
    }
    static long beginRuntimeRoleMutation() { return beginMutation(roleMutations); }
    static void runtimeRoleMutationReturned(long mutation, boolean acknowledged) {
        credentialMutationReturned(mutation, acknowledged);
    }
    static boolean runtimeRoleNeedsAcknowledgment() {
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            return roleMutations.blocked();
        }
    }
    private static final class WorkspaceProof {
        final SharedPreferences preferences;
        final String salt, hash;
        final KeyholderProvider provider;
        WorkspaceProof(SharedPreferences preferences, String salt, String hash, KeyholderProvider provider) {
            this.preferences = preferences; this.salt = salt; this.hash = hash; this.provider = provider;
        }
        boolean current() {
            try {
                return DeviceRolePreferences.Mode.BOTH.name().equals(preferences.getString(
                        DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()))
                        && salt.equals(preferences.getString(KEY_SALT, null))
                        && hash.equals(preferences.getString(KEY_HASH, null))
                        && provider == com.subhub.app.people.People.keyholder();
            } catch (RuntimeException invalid) { return false; }
        }
    }
    // Keep the listener strongly referenced. Credential/capability changes retire a proof even if restored later.
    private static final SharedPreferences.OnSharedPreferenceChangeListener workspacePreferences = (preferences, key) -> {
        if (key == null || KEY_SALT.equals(key) || KEY_HASH.equals(key) || DeviceRolePreferences.KEY_MODE.equals(key)) {
            synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
                invalidateWorkspace();
            }
        }
    };

    private ControllerPinManager() {}

    public static void initialize(Context context) { Context app=context.getApplicationContext();if(app!=null)application=app; }

    public static boolean isConfigured(Context context) {
        initialize(context);
        SharedPreferences preferences = preferences(context);
        return preferences.contains(KEY_SALT) && preferences.contains(KEY_HASH);
    }

    public static boolean hasCredentials(Context context) {
        return isConfigured(context) || new ControllerAuthenticator(context).isPaired()
                || remoteKeyholderState() != KeyholderProvider.State.NONE;
    }
    public static boolean allowsUnkeyedAccess(Context context) {
        return !hasCredentials(context) && preferences(context).getBoolean("controller_keyholder_optional", false);
    }
    static boolean allowUnkeyedAccess(Context context) {
        return withLocalAuthority(() -> {
            long mutation = beginCredentialMutation(); boolean saved = false;
            try { saved = preferences(context).edit().putBoolean("controller_keyholder_optional", true).commit(); return saved; }
            finally { credentialMutationReturned(mutation, saved); }
        });
    }
    public static boolean useWithoutKeyholder(Context context) {
        return !hasCredentials(context) && allowUnkeyedAccess(context);
    }
    public static boolean removePin(Context context) {
        if (!isDomModeActive() || remoteKeyholderState() != KeyholderProvider.State.NONE) return false;
        return withLocalAuthority(() -> {
            if (!isDomModeActive()) return false;
            long mutation = beginCredentialMutation(); boolean saved = false;
            try { saved = preferences(context).edit().remove(KEY_SALT).remove(KEY_HASH)
                    .putBoolean("controller_keyholder_optional", true).commit(); return saved; }
            finally { credentialMutationReturned(mutation, saved); }
        });
    }

    public static boolean setPin(Context context, String pin) {
        if (remoteKeyholderState() != KeyholderProvider.State.NONE) return false;
        String value = normalize(pin);
        if (!value.matches("[0-9]{4,12}")) return false;
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        try {
            byte[] hash = derive(value, salt);
            return withLocalAuthority(() -> {
                long mutation = beginCredentialMutation(); boolean saved = false;
                try {
                    saved = preferences(context).edit()
                            .putBoolean("controller_keyholder_optional", false)
                            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP)).commit();
                } finally { credentialMutationReturned(mutation, saved); }
                if (!saved) return false;
                enterDomMode();
                return true;
            });
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    public static boolean verify(Context context, String pin) {
        if (remoteKeyholderState() != KeyholderProvider.State.NONE || !isConfigured(context)) return false;
        try {
            SharedPreferences preferences = preferences(context);
            byte[] salt = Base64.decode(preferences.getString(KEY_SALT, ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(preferences.getString(KEY_HASH, ""), Base64.NO_WRAP);
            byte[] actual = derive(normalize(pin), salt);
            boolean matches = MessageDigest.isEqual(expected, actual);
            return withLocalAuthority(() -> {
                if (matches) enterDomMode();
                return matches;
            });
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return false;
        }
    }

    /** Existing local PIN authenticates only the owner's BOTH-role Dom workspace, including offline. */
    public static boolean canUnlockLocalDomWorkspace(Context context) {
        try {
            SharedPreferences preferences = preferences(context);
            return DeviceRolePreferences.Mode.BOTH.name().equals(preferences.getString(
                    DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()))
                    && !preferences.getString(KEY_SALT, "").isEmpty()
                    && !preferences.getString(KEY_HASH, "").isEmpty();
        } catch (RuntimeException invalid) { return false; }
    }

    public static boolean verifyLocalDomWorkspace(Context context, String pin) {
        initialize(context);
        if (!canUnlockLocalDomWorkspace(context) || !normalize(pin).matches("[0-9]{4,12}")) return false;
        final WorkspaceProof proof;
        final long revision;
        try {
            SharedPreferences preferences = preferences(context);
            synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
                proof = new WorkspaceProof(preferences, preferences.getString(KEY_SALT, ""),
                        preferences.getString(KEY_HASH, ""), com.subhub.app.people.People.keyholder());
                revision = workspaceRevision;
                preferences.registerOnSharedPreferenceChangeListener(workspacePreferences);
            }
            byte[] expected = Base64.decode(proof.hash, Base64.NO_WRAP);
            byte[] actual = derive(normalize(pin), Base64.decode(proof.salt, Base64.NO_WRAP));
            if (expected.length != KEY_BITS / 8 || !MessageDigest.isEqual(expected, actual)) return false;
            synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
                if (revision != workspaceRevision || !proof.current()) return false;
                localWorkspace = proof;
                workspaceRevision++; retireInternalStarts();
                sessionChanged();
                return true;
            }
        } catch (RuntimeException | GeneralSecurityException invalid) { return false; }
    }

    /** Pure cached proof fence: never queries provider readiness, Keystore, or transport. */
    public static boolean isLocalDomWorkspaceUnlocked() {
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            if (localWorkspace != null && !localWorkspace.current()) invalidateWorkspace();
            return localWorkspace != null;
        }
    }

    public static boolean isDomWorkspaceUnlocked() {
        return isDomModeActive() || isLocalDomWorkspaceUnlocked();
    }

    /** Process-only witness for reviews that must not survive lock and reauthentication. */
    public static long domWorkspaceRevision() {
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            isLocalDomWorkspaceUnlocked();
            return workspaceRevision;
        }
    }

    private static void invalidateWorkspace() {
        localWorkspace = null;
        workspaceRevision++; retireInternalStarts();
        sessionChanged();
    }

    public static boolean isSessionUnlocked() {
        return isDomModeActive();
    }

    /** Local authentication is recovery-only when a paired peer owns configuration. */
    public static boolean canEditConfiguration() {
        Context context=application;
        return context!=null && DeviceRolePreferences.supportsSub(context)
                && com.subhub.app.configuration.LegacyPackMigration.isReady(context)
                && isDomModeActive() && com.subhub.app.people.People.isLocalConfigurationAllowed();
    }

    public static void lockNow() {
        enterSubMode();
    }

    public static boolean isDomModeActive() {
        return domModeActive;
    }

    public static void enterDomMode() {
        synchronized(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK){boolean changed=!domModeActive;domModeActive = true;workspaceRevision++;retireInternalStarts();if(changed)sessionChanged();}
    }

    public static void enterSubMode() {
        synchronized(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK){boolean changed=domModeActive;domModeActive = false;invalidateWorkspace();if(changed)sessionChanged();}
    }

    static KeyholderProvider.State remoteKeyholderState() {
        try { return com.subhub.app.people.People.keyholder().state(); }
        catch (RuntimeException unavailable) { return KeyholderProvider.State.UNAVAILABLE; }
    }

    static boolean withLocalAuthority(java.util.function.BooleanSupplier action) {
        try { return com.subhub.app.people.People.keyholder().withLocalAuthority(action); }
        catch (RuntimeException unavailable) { return false; }
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
