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
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.AsyncUiScope;
import org.json.JSONObject;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/** Debounce input, not durability: accepted writes finish in order even after their page closes. */
public final class RemoteConfigurationAutosave implements DefaultLifecycleObserver, AutoCloseable {
    static final long DEBOUNCE_MILLIS = 500;
    private static final AtomicLong IDS = new AtomicLong();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Activity activity;
    private final Lifecycle lifecycle;
    private final AsyncUiScope work;
    private final RemoteFeature feature;
    private final RemoteStore.Peer peer;
    private final Callable<JSONObject> snapshot;
    private final Runnable reload;
    private final TextView status, retry, reloadButton;
    private final String key = "configuration-autosave:" + IDS.incrementAndGet();
    private final WriteChain writes;
    private JSONObject lastSubmitted;
    private AutoCloseable observation;
    private boolean dirty, failed, closed;
    private int pending;
    private long sequence;
    private final Runnable save = this::flush;

    public RemoteConfigurationAutosave(Activity activity, LinearLayout parent, RemoteFeature feature,
            RemoteStore.Peer peer, JSONObject base, Callable<JSONObject> snapshot, Runnable reload) throws Exception {
        this.activity = activity; this.feature = feature; this.peer = peer;
        this.snapshot = snapshot; this.reload = reload;
        lifecycle = ((LifecycleOwner) activity).getLifecycle(); work = AsyncUiScope.forContext(activity);
        lastSubmitted = copy(base); writes = new WriteChain(feature, peer, base);
        status = PersonUi.text(parent, activity.getString(R.string.remote_autosave_ready), false);
        status.setTag("remote_configuration_save_status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        retry = PersonUi.action(parent, R.string.role_retry, () -> { dirty = true; flush(); }, false);
        retry.setVisibility(View.GONE);
        reloadButton = PersonUi.action(parent, R.string.remote_duration_reload, this::confirmReload, false);
        reloadButton.setVisibility(View.GONE);
        lifecycle.addObserver(this);
    }

    public JSONObject base() throws Exception { return writes.base(); }
    public boolean busy() { return dirty || pending > 0 || failed; }

    public void changed() {
        if (closed) return;
        dirty = true; retry.setVisibility(View.GONE);
        status.setText(R.string.remote_autosave_saving);
        main.removeCallbacks(save); main.postDelayed(save, DEBOUNCE_MILLIS);
    }

    public void flush() {
        main.removeCallbacks(save);
        if (closed || !dirty) return;
        final JSONObject next;
        try { next = RemoteConfiguration.active(snapshot.call()); }
        catch (Exception invalid) { status.setText(R.string.remote_check_fields); return; }
        try {
            if (!failed && RemoteProtocol.canonical(lastSubmitted).equals(RemoteProtocol.canonical(next))) {
                dirty = false; refreshStatus(); return;
            }
            long revision = ControllerPinManager.domWorkspaceRevision();
            if (!ControllerPinManager.isDomWorkspaceUnlocked()) throw new SecurityException("Dom workspace locked");
            long request = ++sequence;
            pending++; dirty = false; failed = false; lastSubmitted = copy(next);
            WriteChain chain = writes;
            status.setText(R.string.remote_autosave_saving);
            // Unique job keys prevent later input or view detachment from cancelling an accepted mutation.
            work.loadSerial(key + ":write:" + request, () -> chain.save(next, revision), saved -> {
                pending--; if (closed) return;
                if (request == sequence) { failed = false; retry.setVisibility(View.GONE); }
                refreshStatus();
            }, failure -> {
                pending--; if (closed) return;
                failed = true; status.setText(R.string.remote_autosave_failed);
                retry.setVisibility(View.VISIBLE); reloadButton.setVisibility(View.VISIBLE);
            });
        } catch (Exception unavailable) {
            failed = true; status.setText(R.string.remote_autosave_failed); retry.setVisibility(View.VISIBLE);
        }
    }

    void refreshStatus() {
        if (closed || busy() || !lifecycle.getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
        RemoteFeature backend = feature; RemoteStore.Peer selected = peer;
        long revision = ControllerPinManager.domWorkspaceRevision();
        work.load(key + ":status", () -> backend.store.authorityObservation(() -> {
            if (revision != ControllerPinManager.domWorkspaceRevision() || !ControllerPinManager.isDomWorkspaceUnlocked())
                throw new SecurityException("Dom workspace changed");
            synchronized (backend.store) {
                String error = backend.contracts.editFailure(selected);
                RemoteStore.Command command = backend.store.latestCommand(selected.identity, selected.generation);
                return !error.isEmpty() ? R.string.remote_autosave_stopped
                        : command != null && command.pending() ? R.string.remote_saved
                        : command != null && command.state == RemoteStore.CommandState.APPLIED ? R.string.remote_applied
                        : R.string.remote_autosave_ready;
            }
        }), label -> {
            if (closed || busy() || revision != ControllerPinManager.domWorkspaceRevision()) return;
            status.setText(label); reloadButton.setVisibility(label == R.string.remote_autosave_stopped ? View.VISIBLE : View.GONE);
        }, failure -> { if (!closed && !busy()) status.setText(R.string.remote_failure); });
    }

    private void confirmReload() {
        if (closed || pending > 0) return;
        com.subhub.app.util.ThemedDialogs.builder(activity)
                .setMessage(R.string.remote_autosave_reload_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.remote_duration_reload, (dialog, which) -> {
                    main.removeCallbacks(save); dirty = false;
                    RemoteFeature backend = feature; RemoteStore.Peer selected = peer;
                    long revision = ControllerPinManager.domWorkspaceRevision();
                    work.loadSerial(key + ":reload:" + ++sequence, () -> {
                        if (revision != ControllerPinManager.domWorkspaceRevision()) throw new SecurityException("Dom workspace changed");
                        return backend.contracts.reloadEdits(selected, revision);
                    }, value -> { if (!closed) { close(); reload.run(); } }, failure -> {
                        if (!closed) status.setText(R.string.remote_autosave_failed);
                    });
                }).show();
    }

    @Override public void onStart(LifecycleOwner owner) {
        if (closed) return;
        if (observation == null) observation = RemoteUi.observeConfiguration(this::refreshStatus);
        refreshStatus();
    }
    @Override public void onPause(LifecycleOwner owner) { flush(); }
    @Override public void onStop(LifecycleOwner owner) {
        if (observation != null) try { observation.close(); } catch (Exception ignored) { }
        observation = null;
    }
    @Override public void onDestroy(LifecycleOwner owner) { close(); }
    @Override public void close() {
        if (closed) return;
        flush(); closed = true; main.removeCallbacks(save);
        onStop((LifecycleOwner) activity); lifecycle.removeObserver(this);
        work.cancel(key + ":status");
    }

    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }

    /** This serial worker state contains only application data, never an Activity or its Views. */
    private static final class WriteChain {
        private final RemoteFeature feature;
        private final RemoteStore.Peer peer;
        private volatile JSONObject base;
        WriteChain(RemoteFeature feature, RemoteStore.Peer peer, JSONObject base) throws Exception {
            this.feature = feature; this.peer = peer; this.base = copy(base);
        }
        public JSONObject base() throws Exception { return copy(base); }
        JSONObject save(JSONObject next, long revision) throws Exception {
            if (revision != ControllerPinManager.domWorkspaceRevision()) throw new SecurityException("Dom workspace changed");
            JSONObject accepted = feature.contracts.saveEdit(peer, base, next, revision);
            base = copy(next); feature.changed(); return accepted;
        }
    }
}
