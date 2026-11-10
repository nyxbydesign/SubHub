package com.subhub.app.remote;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import com.subhub.app.R;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.people.PersonUi;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.util.AsyncUiScope;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Explicit profile actions keep receipts separate from the recipient's application proof. */
public final class RemoteSessionControls implements DefaultLifecycleObserver {
    interface Gateway {
        CompletableFuture<RemoteSessionRuntime.Snapshot> snapshot();
        CompletableFuture<RemoteSessionRuntime.Review> review(RemoteSessionCommands.Action action);
        CompletableFuture<String> confirm(RemoteSessionRuntime.Review review);
    }

    public static final class Operation extends ViewModel {
        final SavedStateHandle saved;
        CompletableFuture<String> pending;
        public Operation(SavedStateHandle saved) { this.saved = saved; }
    }

    private final Activity activity;
    private final android.content.Context application;
    private final LinearLayout parent, controls;
    private final PersonId person;
    private final PersonSnapshot observed;
    private final RemoteFeature feature;
    private final RemoteStore.Peer peer;
    private final BooleanSupplier durationDraft;
    private final Lifecycle lifecycle;
    private final AsyncUiScope work;
    private final Operation operation;
    private final Gateway gateway;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final String key;
    private RemoteSessionRuntime.Snapshot snapshot;
    private TextView action, status, reason, duration;
    private boolean showingStop;
    private com.subhub.app.commitment.ServiceCountdownView time;
    private boolean reviewing, readUnavailable;
    private long observationEpoch;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!attached()) { lifecycle.removeObserver(RemoteSessionControls.this); return; }
            load();
            clock.postDelayed(this, 2000);
        }
    };

    RemoteSessionControls(Activity activity, LinearLayout parent, PersonSnapshot person,
            BooleanSupplier durationDraft) {
        this(activity, parent, person, durationDraft, null);
    }

    RemoteSessionControls(Activity activity, LinearLayout parent, PersonSnapshot person,
            BooleanSupplier durationDraft, Gateway provided) {
        this.activity = activity; this.application = activity.getApplicationContext();
        this.parent = parent; this.person = person.id; this.observed = person;
        this.durationDraft = durationDraft;
        controls = PersonUi.column(activity); controls.setTag("remote_session_controls");
        feature = RemoteFeature.get(application);
        peer = new RemotePersonRepository(application, feature).peer(person.id);
        RemoteFeature backend = feature; RemoteStore.Peer selected = peer;
        gateway = provided != null ? provided : new Gateway() {
            public CompletableFuture<RemoteSessionRuntime.Snapshot> snapshot() { return backend.sessionSnapshot(selected); }
            public CompletableFuture<RemoteSessionRuntime.Review> review(RemoteSessionCommands.Action action) {
                return backend.reviewSession(selected, action);
            }
            public CompletableFuture<String> confirm(RemoteSessionRuntime.Review review) { return backend.confirmSession(review); }
        };
        lifecycle = ((LifecycleOwner) activity).getLifecycle();
        work = AsyncUiScope.forContext(activity);
        key = "session-control:" + person.id.value();
        operation = new ViewModelProvider((ViewModelStoreOwner) activity).get(key, Operation.class);
    }

    void bind() {
        parent.addView(controls);
        android.view.LayoutInflater.from(activity).inflate(R.layout.view_remote_session_control, controls, true);
        time = controls.findViewById(R.id.remote_session_clock);
        time.setTag("remote_session_clock"); time.setVisibility(View.GONE);
        android.view.ViewGroup.LayoutParams clockSize = time.getLayoutParams();
        clockSize.height = PersonUi.dp(activity, Math.round(160 * Math.max(1f,
                activity.getResources().getConfiguration().fontScale))); time.setLayoutParams(clockSize);
        duration = controls.findViewById(R.id.remote_session_duration_caption);
        duration.setTag("remote_session_applied_duration");
        action = controls.findViewById(R.id.remote_session_action_button);
        showAction(observed.serviceActive);
        action.setOnClickListener(v -> {
            if (showingStop) stop();
            else request(RemoteSessionCommands.Action.START);
        });
        status = PersonUi.text(controls, observed.serviceLabel, false);
        status.setTag("remote_session_status");
        reason = PersonUi.text(controls, "", false); reason.setTag("remote_session_reason");
        action.setEnabled(false);
        lifecycle.addObserver(this);
    }

    private boolean attached() {
        return !activity.isFinishing() && !activity.isDestroyed() && controls.getParent() == parent
                && controls.isAttachedToWindow() && lifecycle.getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }

    private boolean authorized() {
        if (peer == null || !feature.recoveryReady() || !DeviceRolePreferences.supportsDom(application)
                || !ControllerPinManager.isDomWorkspaceUnlocked() || !(ControllerPinManager.isLocalDomWorkspaceUnlocked()||ControllerPinManager.hasCredentials(application))) return false;
        RemoteStore.Peer current = feature.store.peer(peer.identity, peer.generation);
        return current != null && current.state == RemoteStore.State.ACTIVE
                && current.localRole == RemoteProtocol.Role.DOM;
    }

    private void load() {
        if (!attached()) return;
        if (!authorized()) { controls.removeAllViews(); onStop((LifecycleOwner) activity); return; }
        if (work.isPending(key + ":snapshot")) return;
        Gateway backend = gateway;
        long revision=ControllerPinManager.domWorkspaceRevision();
        work.load(key + ":snapshot", () -> backend.snapshot().get(15, TimeUnit.SECONDS), value -> {
            if (!attached() || !authorized()||revision!=ControllerPinManager.domWorkspaceRevision()) return;
            if (!peer.identity.equals(value.peer) || !peer.generation.equals(value.generation)) { unavailable(); return; }
            snapshot = value; readUnavailable = false; render();
        }, failure -> {
            if (!attached()) return;
            if (!authorized()) { controls.removeAllViews(); onStop((LifecycleOwner) activity); return; }
            if (revision != ControllerPinManager.domWorkspaceRevision()) { unavailable(); return; }
            readFailed();
        });
    }

    private boolean busy() {
        return reviewing || operation.pending != null && !operation.pending.isDone()
                || snapshot != null && snapshot.latestResult != null && !snapshot.latestResult.state.terminal();
    }

    private void render() {
        boolean changedDuration = durationDraft.getAsBoolean();
        // A stale active report still presents Stop, but never authorizes it. Unknown
        // state keeps the observed mode instead of advertising an unrelated Start.
        showAction(snapshot.supported
                ? snapshot.canStop || !snapshot.sessionIdentity.isEmpty()
                        || snapshot.idleFence.isEmpty() && observed.serviceActive
                : observed.serviceActive);
        action.setEnabled(!readUnavailable && !busy() && (showingStop
                ? snapshot.supported ? snapshot.canStop : legacyStopAvailable()
                : snapshot.supported && snapshot.canStart && !changedDuration));
        duration.setText(snapshot.service == null ? "" : activity.getString(R.string.remote_session_applied_duration,
                durationLabel(snapshot.service)));
        duration.setVisibility(snapshot.service == null ? View.GONE : View.VISIBLE);
        // This is the applied configuration, never an invented live peer countdown.
        // Random duration remains a range until the peer reports a concrete session duration.
        if (snapshot.service == null || snapshot.service.duration == -2) time.setVisibility(View.GONE);
        else {
            if (snapshot.service.duration == -1) time.setPermanent(false, false);
            else time.setCountdown(snapshot.service.duration, snapshot.service.duration, false, false);
            time.setContentDescription(duration.getText()); time.setVisibility(View.VISIBLE);
        }
        RemoteSessionCommands.Result result = snapshot.latestResult;
        String queued = operation.saved.get("operation");
        status.setText(operation.pending != null && !operation.pending.isDone()
                ? activity.getString(R.string.role_action_pending)
                : queued != null && (result == null || !queued.equals(result.id))
                ? activity.getString(R.string.remote_unknown)
                : result != null ? activity.getString(stateLabel(result.state)) : "");
        status.setVisibility(status.length() == 0 ? View.GONE : View.VISIBLE);
        int explanation = !snapshot.supported ? R.string.remote_session_unsupported
                : changedDuration ? R.string.remote_session_apply_duration : reasonLabel(snapshot.reason);
        reason.setText(explanation == 0 ? "" : activity.getString(explanation));
        reason.setVisibility(explanation == 0 ? View.GONE : View.VISIBLE);
    }

    private void showAction(boolean stop) {
        showingStop = stop;
        action.setText(stop ? R.string.remote_session_stop : R.string.remote_session_start_action);
        action.setTag(stop ? "remote_session_stop" : "remote_session_start");
    }

    private String durationLabel(RemoteSessionCommands.Duration service) {
        if (service.duration == -1) return activity.getResources().getStringArray(R.array.remote_duration_choices)[0];
        return (service.duration == -2 ? service.minimum / 60_000 + "–" + service.maximum / 60_000
                : Long.toString(service.duration / 60_000)) + " " + activity.getString(R.string.remote_minutes);
    }

    private boolean legacyStopAvailable() {
        return observed.serviceActive && observed.sync.runtime == PersonSnapshot.Freshness.CURRENT
                && observed.can(PersonSnapshot.Capability.END_SESSION) && !observed.sessionIdentity.isEmpty();
    }

    private void stop() {
        if (!attached() || !authorized() || snapshot == null || readUnavailable || busy()) return;
        if (snapshot.supported) request(RemoteSessionCommands.Action.STOP);
        else if (legacyStopAvailable()) {
            com.subhub.app.people.ConnectionUi provider = com.subhub.app.people.People.connectionUi();
            if (provider != null) provider.endSession(activity, person, observed.sessionIdentity);
        }
    }

    private void request(RemoteSessionCommands.Action action) {
        if (!attached() || !authorized() || snapshot == null || readUnavailable || busy()
                || (action == RemoteSessionCommands.Action.START ? !snapshot.canStart || durationDraft.getAsBoolean()
                        : !snapshot.canStop)) return;
        RemoteSessionRuntime.Snapshot source = snapshot;
        ControllerPinGate.requireDomWorkspace(activity, () -> review(action, source), false);
    }

    private void review(RemoteSessionCommands.Action action, RemoteSessionRuntime.Snapshot source) {
        if (!attached() || !authorized() || busy() || snapshot == null
                || !source.sourceWitness.equals(snapshot.sourceWitness)
                || action == RemoteSessionCommands.Action.START && durationDraft.getAsBoolean()) return;
        reviewing = true; render();
        long epoch = observationEpoch;
        long revision=ControllerPinManager.domWorkspaceRevision();
        Gateway backend = gateway;
        work.load(key + ":review", () -> backend.review(action).get(15, TimeUnit.SECONDS), value -> {
            reviewing = false;
            if (!attached() || !authorized()) return;
            if (revision!=ControllerPinManager.domWorkspaceRevision()||epoch != observationEpoch || snapshot == null
                    || !source.sourceWitness.equals(snapshot.sourceWitness)
                    || !source.sourceWitness.equals(value.sourceWitness) || !peer.identity.equals(value.request.recipient)
                    || !peer.generation.equals(value.request.generation)
                    || value.request.action != action
                    || action == RemoteSessionCommands.Action.START && durationDraft.getAsBoolean()) {
                unavailable(); return;
            }
            // Only an explicit attached click reaches confirmation; restoration never queues an operation.
            CompletableFuture<String> pending = backend.confirm(value);
            operation.pending = pending; render();
            Operation retained = operation;
            pending.whenComplete((id, error) -> clock.post(() -> {
                if (error == null) retained.saved.set("operation", id);
                if (!attached() || !authorized()||revision!=ControllerPinManager.domWorkspaceRevision()) return;
                if (error != null) unavailable(); else load();
            }));
        }, failure -> { reviewing = false; if (attached() && authorized()) unavailable(); });
    }

    private void readFailed() {
        if (snapshot == null) { unavailable(); return; }
        observationEpoch++; reviewing = false; work.cancel(key + ":review");
        // Preserve already observed duration and session facts, never old command permission.
        readUnavailable = true; action.setEnabled(false);
        status.setText(R.string.role_refresh_failed); status.setVisibility(View.VISIBLE);
        reason.setText(R.string.remote_session_changed); reason.setVisibility(View.VISIBLE);
    }

    private void unavailable() {
        observationEpoch++; reviewing = false; work.cancel(key + ":review");
        snapshot = null; action.setEnabled(false); duration.setVisibility(View.GONE);
        time.stop(); time.setVisibility(View.GONE);
        status.setText(R.string.remote_unknown); status.setVisibility(View.VISIBLE);
        reason.setText(R.string.remote_session_changed); reason.setVisibility(View.VISIBLE);
    }

    static int stateLabel(RemoteSessionCommands.State state) {
        switch (state) {
            case QUEUED: return R.string.remote_session_queued;
            case ACKNOWLEDGED: return R.string.remote_session_received;
            case APPLYING: return R.string.remote_session_applying;
            case APPLIED: return R.string.remote_session_applied;
            case REJECTED: return R.string.remote_session_rejected;
            case EXPIRED: return R.string.remote_session_expired;
            case NEEDS_SUB_ACTION: return R.string.remote_session_needs_action;
            default: return R.string.remote_session_failed;
        }
    }

    private static int reasonLabel(RemoteSessionCommands.Reason reason) {
        switch (reason) {
            case NONE: return 0;
            case UNSUPPORTED: return R.string.remote_session_unsupported;
            case HARDCORE_REQUIRED: return R.string.remote_session_hardcore_required;
            case CONSENT_REQUIRED: return R.string.remote_session_consent_required;
            case READINESS_REQUIRED: return R.string.remote_session_needs_action;
            default: return R.string.remote_session_changed;
        }
    }

    @Override public void onStart(LifecycleOwner owner) { clock.removeCallbacks(poll); clock.post(poll); }
    @Override public void onStop(LifecycleOwner owner) {
        observationEpoch++; clock.removeCallbacks(poll); if (time != null) time.stop(); work.cancel(key + ":snapshot"); work.cancel(key + ":review"); reviewing = false;
    }
    @Override public void onDestroy(LifecycleOwner owner) { onStop(owner); lifecycle.removeObserver(this); }
}
