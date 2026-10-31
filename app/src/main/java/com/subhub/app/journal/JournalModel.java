package com.subhub.app.journal;

import android.os.Bundle;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.subhub.app.people.PersonId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONArray;

/** Accepted imports and saves belong to the retained draft, not a recreated Activity. */
public final class JournalModel extends ViewModel {
    final MutableLiveData<Integer> updates = new MutableLiveData<>(0);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "Journal-writes"));
    JournalRuntime runtime;
    boolean initialized, dom, editing, initialShared, share;
    volatile boolean busy;
    volatile int error;
    String draft = UUID.randomUUID().toString(), selected = "", id = "", text = "";
    long created;
    int offset;
    PersonId target;
    JournalAttachment audio;
    final List<JournalAttachment> attachments = new ArrayList<>();
    private int update;

    synchronized List<JournalAttachment> files() { return new ArrayList<>(attachments); }
    synchronized List<JournalAttachment> allMedia() {
        List<JournalAttachment> result = files(); if (audio != null) result.add(audio); return result;
    }
    <T> void work(Callable<T> action, Consumer<T> accepted, int failed) {
        if (busy) return;
        busy = true; error = 0; updates.setValue(++update);
        worker.execute(() -> {
            try { T value = action.call(); synchronized (this) { accepted.accept(value); } }
            catch (Exception failure) { error = failed; }
            finally { busy = false; updates.postValue(++update); }
        });
    }
    synchronized void restore(Bundle state) throws Exception {
        if (state == null) return;
        editing = state.getBoolean("journal_editing"); selected = state.getString("journal_selected", "");
        id = state.getString("journal_id", ""); text = state.getString("journal_text", "");
        draft = state.getString("journal_draft", draft); created = state.getLong("journal_created");
        offset = state.getInt("journal_offset"); share = state.getBoolean("journal_share");
        initialShared = state.getBoolean("journal_initial_shared");
        JSONArray items = new JSONArray(state.getString("journal_files", "[]"));
        if (items.length() > JournalEntry.MAX_ATTACHMENTS) throw new IllegalStateException("Invalid journal draft");
        for (int i = 0; i < items.length(); i++) attachments.add(JournalAttachment.parse(items.getJSONObject(i)));
        String sound = state.getString("journal_audio", "");
        if (!sound.isEmpty()) audio = JournalAttachment.parse(new org.json.JSONObject(sound));
    }
    synchronized void save(Bundle state) throws Exception {
        state.putBoolean("journal_editing", editing); state.putString("journal_selected", selected);
        state.putString("journal_id", id); state.putString("journal_text", text); state.putString("journal_draft", draft);
        state.putLong("journal_created", created); state.putInt("journal_offset", offset);
        state.putBoolean("journal_share", share); state.putBoolean("journal_initial_shared", initialShared);
        JSONArray items = new JSONArray(); for (JournalAttachment file : attachments) items.put(file.json());
        state.putString("journal_files", items.toString()); state.putString("journal_audio", audio == null ? "" : audio.json().toString());
    }
    @Override protected void onCleared() {
        if (runtime != null) worker.execute(() -> { try { runtime.releaseDraft(dom, draft); } catch (Exception ignored) { } });
        worker.shutdown();
    }
}
