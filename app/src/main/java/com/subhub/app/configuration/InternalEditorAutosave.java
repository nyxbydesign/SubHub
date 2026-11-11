package com.subhub.app.configuration;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.internal.InternalConfigurationStore;
import com.subhub.app.internal.InternalSubRecord;
import com.subhub.app.people.DummySubPreview;
import com.subhub.app.people.InternalSubData;
import com.subhub.app.people.PersonUi;
import java.util.function.BooleanSupplier;

/** A replaceable View binding. Persistence ordering belongs to the draft's shared save drain. */
final class InternalEditorAutosave implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BooleanSupplier current, stage;
    private final InternalEditorSaveDrain drain;
    private final InternalEditorSaveDrain.Observation observation;
    private final TextView status, reload;
    private boolean closed, dirty, unavailable, invalidFields, stageFailed;
    private final Runnable save = this::flush;

    static InternalEditorSaveDrain createDrain(Context physical, InternalSubData data,
            InternalConfigurationStore.EditorDraft draft, String generation, long controllerRevision,
            java.util.concurrent.Executor delivery) {
        return new InternalEditorSaveDrain(new InternalEditorSaveDrain.Driver() {
            @Override public boolean current() {
                return data.ownerDataReady() && DummySubPreview.current(physical, generation, controllerRevision);
            }
            @Override public boolean hasChanges() { return draft.hasUnflushedChanges(); }
            @Override public java.util.concurrent.CompletionStage<?> prepareAndSubmit() {
                InternalConfigurationStore.PreparedEdit prepared = data.configuration.prepare(draft);
                return data.ownerData().submitEditor(generation, controllerRevision, access -> {
                    if (!prepared.base().equals(access.record())) throw new SecurityException("Internal draft base changed");
                    InternalSubRecord acknowledged = data.configuration.flushPrepared(prepared);
                    if (!acknowledged.generation.equals(generation)) throw new SecurityException("Internal target changed");
                    return acknowledged;
                });
            }
        }, delivery);
    }

    InternalEditorAutosave(AppCompatActivity host, LinearLayout body, InternalSubData data,
            InternalConfigurationStore.EditorDraft draft, String generation, long controllerRevision,
            InternalEditorSaveDrain drain, BooleanSupplier stage, Runnable reloadPage) {
        Context physical = host.getApplicationContext();
        current = () -> DummySubPreview.current(physical, generation, controllerRevision);
        this.stage = stage; this.drain = drain;
        status = PersonUi.text(body, host.getString(R.string.internal_sub_saved), false);
        status.setTag("internal_configuration_save_status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        reload = PersonUi.action(body, R.string.remote_duration_reload, () -> {
            if (!drain.pending() && current.getAsBoolean()) { close(); reloadPage.run(); }
        }, false);
        reload.setVisibility(View.GONE);
        unavailable = !data.ownerDataReady();
        observation = drain.observe(this::render);
        if (draft.hasUnflushedChanges()) changed();
    }
    void changed() {
        if (closed || unavailable || drain.state() == InternalEditorSaveDrain.State.FAILED
                || drain.state() == InternalEditorSaveDrain.State.REVOKED) return;
        dirty = true; invalidFields = false; stageFailed = false; render(drain.state());
        main.removeCallbacks(save); main.postDelayed(save, 500);
    }
    void flush() {
        main.removeCallbacks(save);
        if (closed || unavailable || !dirty) return;
        try {
            if (!current.getAsBoolean()) { drain.request(); return; }
            if (!stage.getAsBoolean()) { invalidFields = true; render(drain.state()); return; }
            invalidFields = false; stageFailed = false; dirty = false; drain.request(); render(drain.state());
        } catch (RuntimeException problem) {
            // A widget failure does not invent a storage ACK or rebase the session's draft.
            stageFailed = true; render(drain.state());
        }
    }
    private void render(InternalEditorSaveDrain.State state) {
        if (closed) return;
        if (unavailable) { status.setText(R.string.internal_sub_owner_data_unavailable); return; }
        if (stageFailed || state == InternalEditorSaveDrain.State.FAILED || state == InternalEditorSaveDrain.State.REVOKED) {
            status.setText(R.string.internal_sub_save_failed); reload.setVisibility(View.VISIBLE); return;
        }
        reload.setVisibility(View.GONE);
        if (invalidFields) { status.setText(R.string.remote_check_fields); return; }
        status.setText(dirty || state == InternalEditorSaveDrain.State.SAVING
                ? R.string.remote_autosave_saving : R.string.internal_sub_saved);
    }
    @Override public void close() {
        if (closed) return;
        // Stage this binding once before detaching. The shared drain needs no disposed Views to
        // finish a held ACK, save later staged edits, or serve a replacement binding after rotation.
        flush(); closed = true; main.removeCallbacks(save); observation.close();
    }
}
