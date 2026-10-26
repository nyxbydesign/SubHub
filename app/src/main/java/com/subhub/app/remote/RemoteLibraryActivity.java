package com.subhub.app.remote;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import org.json.JSONObject;
import java.io.*;
import java.util.Map;

/** Saved portable contracts belong to the selected relationship. */
public final class RemoteLibraryActivity extends RemotePage {
    private JSONObject active, exportDoc;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.string.role_saved_contracts);
        if (!selectPeer()) return;
        if (state != null) try {
            String current = state.getString("active"), exported = state.getString("exportDoc");
            active = current == null ? null : new JSONObject(current);
            exportDoc = exported == null ? null : new JSONObject(exported);
        } catch (Exception invalid) { notice(R.string.remote_reload); }
        load();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("active", active == null ? null : active.toString());
        state.putString("exportDoc", exportDoc == null ? null : exportDoc.toString());
        super.onSaveInstanceState(state);
    }

    private void load() {
        body.removeAllViews();
        text(body, getString(R.string.role_loading), false);
        work(() -> new Library(feature.contracts.current(peer), feature.contracts.library(peer)), this::render);
    }

    private void render(Library library) {
        active = library.active;
        body.removeAllViews();
        text(body, peer.name, true);
        boolean editable = mayEdit();
        if (!editable) text(body, getString(R.string.remote_read_only), false);
        RemoteStore.Command latest = feature.store.latestCommand(peer.identity, peer.generation);
        if (latest != null) text(body, getString(latest.state == RemoteStore.CommandState.APPLIED
                ? R.string.remote_applied : latest.state == RemoteStore.CommandState.CONFLICT
                ? R.string.remote_conflict : latest.state == RemoteStore.CommandState.REJECTED
                ? R.string.remote_rejected : R.string.role_changes_pending), false);
        for (Map.Entry<String, JSONObject> entry : library.saved.entrySet()) {
            JSONObject saved = entry.getValue();
            LinearLayout card = PersonUi.card(body);
            text(card, saved.optJSONObject("contract").optString("name"), true);
            if (!editable) continue;
            button(card, R.string.remote_apply, () -> apply(saved));
            button(card, R.string.remote_export, () -> {
                if (!mayEdit()) return;
                exportDoc = saved;
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "contract.sub")
                        .addCategory(Intent.CATEGORY_OPENABLE), 2);
            });
            button(card, R.string.delete, () -> authorize(() -> work(() -> {
                requireEdit(); feature.contracts.delete(peer, entry.getKey()); feature.changed(); return null;
            }, ignored -> load())));
        }
        if (editable && active != null) button(body, R.string.remote_import, () -> {
            if (mayEdit()) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE), 1);
        });
        button(body, R.string.role_retry, this::load);
    }

    private void apply(JSONObject saved) {
        authorize(() -> work(() -> {
            requireEdit();
            JSONObject current = feature.contracts.current(peer);
            if (current == null) throw new IOException("Contract baseline unavailable");
            long desired = Long.parseLong(feature.store.metadata(peer.identity, peer.generation, "desired_revision", "0"));
            long applied = Long.parseLong(feature.store.metadata(peer.identity, peer.generation, "applied_revision", "0"));
            feature.store.queueConfiguration(peer.identity, peer.generation, desired, applied,
                    feature.contracts.merge(current, saved));
            feature.changed(); return null;
        }, ignored -> { notice(R.string.role_changes_pending); load(); }));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || (request != 1 && request != 2)) return;
        if (!mayEdit() || active == null || request == 2 && exportDoc == null) {
            notice(R.string.remote_reload); return;
        }
        JSONObject template = active, selected = exportDoc;
        android.net.Uri uri = data.getData();
        authorize(() -> work(() -> {
            requireEdit();
            if (request == 2) {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("Export unavailable");
                    feature.contracts.export(peer, selected, out);
                }
            } else {
                File temporary = File.createTempFile("peer-library-", ".part", getCacheDir());
                try {
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         OutputStream out = new FileOutputStream(temporary)) {
                        if (in == null) throw new IOException("Import unavailable");
                        byte[] buffer = new byte[8192]; long length = 0; int count;
                        while ((count = in.read(buffer)) != -1) {
                            length += count;
                            if (length > RemoteAssets.MAX_TOTAL_BYTES) throw new IOException("Archive too large");
                            out.write(buffer, 0, count);
                        }
                    }
                    requireEdit(); feature.contracts.importArchive(peer, temporary, template); feature.changed();
                } finally { temporary.delete(); }
            }
            return null;
        }, ignored -> load()));
    }

    private void requireEdit() { if (!mayEdit()) throw new SecurityException("Pairing is not authorized"); }
    private static final class Library {
        final JSONObject active;
        final Map<String, JSONObject> saved;
        Library(JSONObject active, Map<String, JSONObject> saved) { this.active = active; this.saved = saved; }
    }
}
