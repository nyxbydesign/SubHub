package com.subhub.app.security;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.people.LocalSessionControls;
import com.subhub.app.people.People;
import com.subhub.app.settings.SettingsRepository;
import java.util.Objects;

/** Device capabilities are independent of whether the local controller session is unlocked. */
public final class DeviceRolePreferences {
    public enum Mode { DOM_ONLY, SUB_ONLY, BOTH }
    public static final String KEY_MODE = "device_role_mode";
    private DeviceRolePreferences() { }

    // Distinct from the controller's Dom-workspace witness. A Sub-capable physical device may
    // start its own ordinary session while Dom is locked. This only fences pending local starts.
    private static final java.util.Map<Context, DeviceStartState> STARTS = new java.util.WeakHashMap<>();
    private static final class DeviceStartState {
        long revision;
        boolean pending, uncertain;
        com.subhub.app.runtime.RuntimeAdmissionAuthority.Revocation epoch =
                com.subhub.app.runtime.RuntimeAdmissionAuthority.Revocation.forDeviceLocalStart();
        void begin() {
            if (pending) throw new IllegalStateException("Role mutation already pending");
            long next = Math.incrementExact(revision);
            epoch.revoke(); epoch = com.subhub.app.runtime.RuntimeAdmissionAuthority.Revocation.forDeviceLocalStart();
            revision = next; pending = true;
        }
        void returned(boolean acknowledged) {
            if (!pending) throw new IllegalStateException("No pending role mutation");
            pending = false; uncertain = !acknowledged;
            if (!acknowledged) epoch.revoke();
        }
    }
    /** Process-local role observation, never configuration durability or an ACTIVE-session lease. */
    public static final class DeviceStartWitness {
        public final long revision;
        public final Mode mode;
        public final com.subhub.app.runtime.RuntimeAdmissionAuthority.Revocation revocation;
        private DeviceStartWitness(DeviceStartState state, Mode mode) {
            revision = state.revision; this.mode = mode; revocation = state.epoch;
        }
    }
    private static DeviceStartState startState(Context context) {
        if (!Thread.holdsLock(RuntimeSessionIdentity.LOCK)) throw new IllegalStateException("Role witness lock required");
        Context app = Objects.requireNonNull(context).getApplicationContext();
        return STARTS.computeIfAbsent(app, ignored -> new DeviceStartState());
    }
    /** Owner worker observes this once; actual permission launch reads only its monotonic bit. */
    public static DeviceStartWitness captureDeviceStartWitness(Context context) {
        Context app = Objects.requireNonNull(context).getApplicationContext();
        if (!(app instanceof android.app.Application)) throw new SecurityException("Physical application required");
        synchronized (RuntimeSessionIdentity.LOCK) {
            DeviceStartState state = startState(app);
            if (state.pending || state.uncertain || ControllerPinManager.runtimeRoleNeedsAcknowledgment())
                throw new SecurityException("Role persistence needs acknowledgment");
            // Keep the existing presentation fallback unchanged, but a corrupt stored role is
            // not affirmative authority to launch a new physical session.
            Mode observed;
            try { observed = Mode.valueOf(preferences(app).getString(KEY_MODE, Mode.BOTH.name())); }
            catch (IllegalArgumentException | ClassCastException invalid) { throw new SecurityException("Invalid role start authority", invalid); }
            if (observed == Mode.DOM_ONLY) throw new SecurityException("DEVICE session requires Sub capability");
            return new DeviceStartWitness(state, observed);
        }
    }
    private static boolean deviceRoleNeedsAcknowledgment(Context context) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            DeviceStartState state = startState(context);
            return state.pending || state.uncertain;
        }
    }

    private static boolean storedRoleValid(Context context) {
        try { Mode.valueOf(preferences(context).getString(KEY_MODE, Mode.BOTH.name())); return true; }
        catch (IllegalArgumentException | ClassCastException invalid) { return false; }
    }
    public static Mode get(Context context) {
        try { return Mode.valueOf(preferences(context).getString(KEY_MODE, Mode.BOTH.name())); }
        catch (IllegalArgumentException | ClassCastException invalid) { return Mode.BOTH; }
    }
    public static boolean supportsDom(Context context) { return get(context) != Mode.SUB_ONLY; }
    public static boolean supportsSub(Context context) { return get(context) != Mode.DOM_ONLY; }
    public static boolean isDomWorkspace(Context context) {
        return get(context) == Mode.DOM_ONLY || ControllerPinManager.isDomWorkspaceUnlocked();
    }

    /** Established devices require controller authentication; pairing and active protection stay authoritative. */
    public static boolean set(Context context, Mode mode) {
        Objects.requireNonNull(mode);
        if (get(context) == mode && storedRoleValid(context) && !ControllerPinManager.runtimeRoleNeedsAcknowledgment()
                && !deviceRoleNeedsAcknowledgment(context)) return true;
        return ControllerPinManager.withLocalAuthority(() -> {
            synchronized (RuntimeSessionIdentity.LOCK) {
                if (!People.isLocalConfigurationAllowed()) return false;
                if (!OnboardingState.isPristine(context)
                        && (!ControllerPinManager.hasCredentials(context) || !ControllerPinManager.isDomModeActive())) return false;
                if (mode == Mode.DOM_ONLY && hasSubProtection(context)) return false;
                DeviceStartState start = startState(context);
                start.begin(); // Retire pending DEVICE prompts before SharedPreferences memory changes.
                long mutation = -1;
                boolean saved = false;
                try {
                    mutation = ControllerPinManager.beginRuntimeRoleMutation();
                    saved = preferences(context).edit().putString(KEY_MODE, mode.name()).commit(); return saved;
                } finally {
                    try { if (mutation >= 0) ControllerPinManager.runtimeRoleMutationReturned(mutation, saved); }
                    finally { start.returned(saved); }
                }
            }
        });
    }
    public static boolean hasSubProtection(Context context) {
        HardcoreModeManager hardcore = new HardcoreModeManager(context);
        return LocalSessionControls.isActive(context) || hardcore.isRequested() || hardcore.isAdminActive();
    }
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
