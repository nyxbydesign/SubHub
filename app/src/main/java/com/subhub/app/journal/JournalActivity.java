package com.subhub.app.journal;

import android.Manifest;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import com.subhub.app.R;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.PersonUi;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.InputFocusPolicy;
import com.subhub.app.util.PreferencePage;
import com.subhub.app.util.PrimaryHeader;
import java.io.File;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Private drafts, foreground recording and read-only shared entries use the same native layout. */
public final class JournalActivity extends PreferencePage {
    private JournalModel model;
    private AsyncUiScope data;
    private LinearLayout content;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<JournalAudioPlayer> players = new ArrayList<>();
    private ActivityResultLauncher<String[]> picker;
    private ActivityResultLauncher<String> microphone;
    private String rendered = "";
    private boolean resumed, pendingRecord;
    private MediaRecorder recorder;
    private File recording;
    private long recordingStarted;
    private TextView recordButton, recordTime, saveButton, addButton;
    private EditText editor;
    private View visibilityToggle;
    private int message;
    private final List<View> recordingLocked = new ArrayList<>();
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (!model.editing && !model.busy) refresh();
            handler.postDelayed(this, 2000);
        }
    };
    private final Runnable timer = new Runnable() {
        @Override public void run() {
            if (recorder == null) return;
            recordTime.setText(getString(R.string.journal_recording_time,
                    JournalAudioPlayer.clock(android.os.SystemClock.elapsedRealtime() - recordingStarted)));
            handler.postDelayed(this, 200);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        message = state == null ? 0 : state.getInt("journal_message", 0);
        model = new ViewModelProvider(this).get(JournalModel.class);
        try {
            boolean dom = getIntent().getBooleanExtra(JournalRoutes.EXTRA_DOM, false);
            PersonId target = new PersonId(getIntent().getStringExtra(PersonRoutes.EXTRA_PERSON_ID));
            if (!model.initialized) {
                model.runtime = JournalRuntime.get(this); model.dom = dom; model.target = target;
                model.restore(state); model.initialized = true;
            } else if (model.dom != dom || !model.target.equals(target)) throw new SecurityException("Journal target changed");
            model.runtime.checkTarget(dom, target);
            if (model.editing) model.runtime.retainDraft(dom, model.draft, model.allMedia());
        } catch (Exception unavailable) { finish(); return; }
        data = AsyncUiScope.forPage(this);
        picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null || !model.editing || model.busy || model.files().size() >= JournalEntry.MAX_ATTACHMENTS) return;
            JournalModel draft = model; JournalRuntime runtime = draft.runtime;
            boolean dom = draft.dom; String token = draft.draft;
            draft.work(() -> runtime.importFile(dom, token, uri), file -> {
                if (draft.attachments.stream().noneMatch(item -> item.hash.equals(file.hash))) draft.attachments.add(file);
            }, R.string.journal_import_failed);
        });
        microphone = registerForActivityResult(new ActivityResultContracts.RequestPermission(), allowed -> {
            if (!allowed) { pendingRecord = false; showMessage(R.string.journal_permission_needed); return; }
            pendingRecord = true;
            if (resumed) { pendingRecord = false; startRecording(); }
        });
        page(R.string.journal_title);
        PrimaryHeader.backButton(page).setOnClickListener(view -> back());
        content = PersonUi.column(this); page.addView(content, new LinearLayout.LayoutParams(-1, -2));
        JournalUi.text(content, getString(R.string.journal_loading), 14, false).setTag("journal_loading");
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        model.updates.observe(this, ignored -> {
            if (model.busy) message = 0;
            if (model.error != 0) { message = model.error; model.error = 0; }
            rendered = "";
            if (model.editing) renderEditor();
            else if (model.busy) {
                if (content.findViewWithTag("journal_busy") == null) {
                    LinearLayout progress = JournalUi.progress(content); progress.setTag("journal_busy");
                    content.removeView(progress); content.addView(progress, 0);
                }
                disableActions(content);
            } else refresh();
        });
        InputFocusPolicy.openActivityForReading(this);
    }

    @Override protected void onResume() {
        super.onResume(); if (content == null) return;
        try { model.runtime.requireScreen(model.dom); }
        catch (SecurityException unavailable) { finish(); return; }
        resumed = true; handler.post(poll);
        if (model.editing) renderEditor(); else refresh();
        if (pendingRecord) { pendingRecord = false; startRecording(); }
    }
    @Override protected void onPause() {
        resumed = false; handler.removeCallbacks(poll); pendingRecord = false;
        stopRecording(true); closePlayers(); super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); closePlayers(); super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        stopRecording(true);
        try { if (model != null) model.save(state); } catch (Exception invalid) { state.clear(); }
        state.putInt("journal_message", message);
        super.onSaveInstanceState(state);
    }

    private boolean authorized() {
        try {
            model.runtime.checkTarget(model.dom, model.target);
            return true;
        } catch (Exception unavailable) { return false; }
    }

    private static final class Snapshot {
        final List<JournalEntry> entries;
        final Map<String, Boolean> shared = new LinkedHashMap<>();
        final Map<String, Boolean> missingCurrentAudience = new LinkedHashMap<>();
        final Map<String, Boolean> ready = new LinkedHashMap<>();
        final String selected;
        String author = "", compatibility = "";
        String signature = "";
        Snapshot(List<JournalEntry> entries, String selected) { this.entries = entries; this.selected = selected; }
    }
    private void refresh() {
        if (content == null || model.editing || model.busy) return;
        if (!authorized()) { unavailable(); return; }
        JournalRuntime runtime = model.runtime; boolean dom = model.dom;
        PersonId target = model.target; String selected = model.selected; int offset = model.offset;
        android.content.Context application = getApplicationContext();
        data.load("journal-screen", () -> {
            List<JournalEntry> entries;
            if (selected.isEmpty()) entries = runtime.list(dom, target, offset);
            else { JournalEntry entry = runtime.entry(dom, target, selected);
                if (entry == null) throw new SecurityException("Journal entry unavailable"); entries = List.of(entry); }
            Snapshot value = new Snapshot(entries, selected);
            if (!target.isLocal()) {
                if (JournalRuntime.localShared(target)) value.author = application.getString(dom ? R.string.role_sub : R.string.role_dom);
                else {
                    com.subhub.app.remote.RemoteStore.Peer peer = runtime.peer(target, dom);
                    value.author = peer.name;
                    value.compatibility = runtime.authority.metadata(peer.identity, peer.generation, "journal_version", "");
                }
            }
            for (JournalEntry entry : entries) {
                value.signature += entry.json().toString();
                if (target.isLocal()) {
                    value.shared.put(entry.id, runtime.shared(dom, entry.id));
                    value.missingCurrentAudience.put(entry.id, runtime.needsCurrentSharing(dom, entry.id));
                }
                if (!selected.isEmpty()) for (JournalAttachment file : entry.media())
                    value.ready.put(file.hash, runtime.mediaReady(dom, target, entry.id, file));
            }
            value.signature += value.shared.toString() + value.missingCurrentAudience + value.ready + offset + value.author + value.compatibility;
            return value;
        }, value -> {
            if (model.editing || model.busy || !value.selected.equals(model.selected)) return;
            if (!authorized()) { unavailable(); return; }
            if (rendered.equals(value.signature)) return;
            rendered = value.signature;
            ScrollView scroll = page.getParent() instanceof ScrollView ? (ScrollView) page.getParent() : null;
            int previous = scroll == null ? 0 : scroll.getScrollY();
            closePlayers(); content.removeAllViews();
            showMessageIn(content);
            if (value.selected.isEmpty()) renderList(value); else renderEntry(value);
            if (scroll instanceof com.subhub.app.util.PageScrollView)
                ((com.subhub.app.util.PageScrollView) scroll).restorePosition(previous);
        }, failure -> {
            if (failure instanceof SecurityException || !authorized()) unavailable();
            else failedRefresh();
        });
    }
    private void unavailable() {
        closePlayers(); content.removeAllViews();
        JournalUi.empty(content, R.drawable.ic_ux_lock, getString(R.string.journal_unavailable));
        content.findViewWithTag("journal_empty").setTag("journal_unavailable");
    }
    private void failedRefresh() {
        rendered = "";
        if (content.findViewWithTag("journal_load_error") != null) return;
        View loading = content.findViewWithTag("journal_loading"); if (loading != null) content.removeView(loading);
        LinearLayout error = JournalUi.surface(content); error.setTag("journal_load_error");
        JournalUi.text(error, getString(R.string.journal_load_failed), 14, true);
        JournalUi.action(error, R.string.journal_retry, 0, () -> { rendered = ""; refresh(); }, false).setTag("journal_retry");
        content.removeView(error); content.addView(error, 0);
    }
    private void showMessage(int value) {
        message = value;
        if (model != null && model.editing && recorder == null) renderEditor();
        else { rendered = ""; refresh(); }
    }
    private void showMessageIn(LinearLayout parent) {
        if (message != 0) {
            LinearLayout notice = JournalUi.surface(parent); notice.setTag("journal_message");
            TextView text = JournalUi.text(notice, getString(message), 14, true);
            text.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
    }
    private void disableActions(View view) {
        if (view.isClickable() || view instanceof EditText) view.setEnabled(false);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) disableActions(group.getChildAt(i));
        }
    }
    private void sharedContext(Snapshot value) {
        if (model.target.isLocal()) return;
        TextView source = JournalUi.text(content, getString(R.string.journal_shared_by, value.author), 14, false);
        source.setPadding(0, 0, 0, dp(12)); source.setTag("journal_author");
        if ("0".equals(value.compatibility)) {
            TextView compatible = JournalUi.text(content, getString(R.string.journal_sync_version), 13, false);
            compatible.setPadding(0, 0, 0, dp(12)); compatible.setTag("journal_compatibility");
        }
    }

    private void renderList(Snapshot value) {
        sharedContext(value);
        if (model.target.isLocal()) {
            TextView create = JournalUi.action(content, R.string.journal_new, R.drawable.ic_journal_add, () -> beginEdit(null, false), true);
            create.setTag("journal_new");
            LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) create.getLayoutParams();
            size.topMargin = 0; size.bottomMargin = dp(12); create.setLayoutParams(size);
        }
        if (value.entries.isEmpty()) JournalUi.empty(content, R.drawable.ic_journal, getString(model.target.isLocal()
                ? R.string.journal_empty : R.string.journal_shared_empty));
        for (int index = 0; index < Math.min(value.entries.size(), JournalStore.PAGE_SIZE); index++) {
            JournalEntry entry = value.entries.get(index);
            LinearLayout card = JournalUi.surface(content); card.setTag("journal_entry:" + entry.id);
            JournalUi.text(card, date(entry.created), 12, false);
            JournalUi.state(card, !model.target.isLocal() || Boolean.TRUE.equals(value.shared.get(entry.id)))
                    .setTag("journal_visibility:" + entry.id);
            if (model.target.isLocal() && Boolean.TRUE.equals(value.shared.get(entry.id))
                    && Boolean.TRUE.equals(value.missingCurrentAudience.get(entry.id)))
                JournalUi.text(card, getString(model.dom ? R.string.journal_current_subs_missing
                        : R.string.journal_current_dom_missing), 12, false).setTag("journal_current_audience:" + entry.id);
            {
                TextView preview = JournalUi.text(card, entryTitle(entry), 16, true); preview.setMaxLines(3);
                preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
            List<String> media = new ArrayList<>();
            if (entry.audio != null) media.add(getString(R.string.journal_audio));
            if (!entry.attachments.isEmpty()) media.add(getResources().getQuantityString(R.plurals.journal_attachment_count, entry.attachments.size(), entry.attachments.size()));
            if (!media.isEmpty()) {
                TextView summary = JournalUi.text(card, String.join(" · ", media), 13, false);
                summary.setPadding(0, dp(entry.text.isEmpty() ? 0 : 10), 0, 0);
            }
            card.setClickable(true); card.setFocusable(true); card.setMinimumHeight(dp(48));
            android.util.TypedValue ripple = new android.util.TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            card.setForeground(getDrawable(ripple.resourceId));
            card.setContentDescription(date(entry.created) + ", " + entryTitle(entry));
            card.setOnClickListener(view -> { model.selected = entry.id; rendered = ""; top(); refresh(); });
        }
        if (model.offset > 0) JournalUi.action(content, R.string.journal_previous, 0, () -> {
            model.offset = Math.max(0, model.offset - JournalStore.PAGE_SIZE); rendered = ""; top(); refresh();
        }, false).setTag("journal_previous");
        if (value.entries.size() > JournalStore.PAGE_SIZE) JournalUi.action(content, R.string.journal_next, 0, () -> {
            model.offset += JournalStore.PAGE_SIZE; rendered = ""; top(); refresh();
        }, false).setTag("journal_next");
    }

    private void renderEntry(Snapshot value) {
        JournalEntry entry = value.entries.get(0);
        sharedContext(value);
        LinearLayout card = JournalUi.surface(content); card.setTag("journal_detail");
        JournalUi.text(card, date(entry.created), 12, false);
        boolean shared = !model.target.isLocal() || Boolean.TRUE.equals(value.shared.get(entry.id));
        JournalUi.state(card, shared).setTag("journal_detail_visibility");
        JournalUi.text(card, entryTitle(entry), 20, true).setTag("journal_entry_title");
        if (!entry.text.isEmpty()) {
            TextView writing = JournalUi.text(card, entry.text, 16, true); writing.setTag("journal_text_body"); writing.setTextIsSelectable(true);
        }
        if (entry.audio != null) {
            if (!entry.text.isEmpty()) JournalUi.divider(card);
            JournalUi.heading(card, getString(R.string.journal_audio), R.drawable.ic_journal_microphone);
            if (Boolean.TRUE.equals(value.ready.get(entry.audio.hash))) audio(card, entry.audio, false);
            else PersonUi.text(card, getString(R.string.journal_media_pending), false);
        }
        if (!entry.attachments.isEmpty()) {
            if (!entry.text.isEmpty() || entry.audio != null) JournalUi.divider(card);
            JournalUi.heading(card, getResources().getQuantityString(R.plurals.journal_attachment_count, entry.attachments.size(), entry.attachments.size()), R.drawable.ic_journal_attachment);
        }
        for (JournalAttachment file : entry.attachments) attachment(card, file, false, Boolean.TRUE.equals(value.ready.get(file.hash)));
        if (!model.target.isLocal()) {
            TextView readOnly = JournalUi.text(content, getString(R.string.journal_read_only), 12, false);
            readOnly.setTag("journal_read_only"); return;
        }
        if (shared && Boolean.TRUE.equals(value.missingCurrentAudience.get(entry.id))) {
            JournalUi.text(content, getString(model.dom ? R.string.journal_current_subs_missing
                    : R.string.journal_current_dom_missing), 12, false).setTag("journal_current_audience");
            JournalUi.action(content, model.dom ? R.string.journal_share_subs : R.string.journal_share_dom,
                    R.drawable.ic_journal_share, () -> {
                JournalModel draft = model;
                draft.work(() -> { draft.runtime.save(draft.dom, entry, true, true); return true; },
                        ignored -> { }, R.string.journal_no_audience);
            }, true).setTag("journal_share_current");
        }
        LinearLayout[] actions = JournalUi.pair(content);
        JournalUi.action(actions[0], shared ? R.string.journal_make_private : model.dom ? R.string.journal_share_subs : R.string.journal_share_dom,
                shared ? R.drawable.ic_ux_lock : R.drawable.ic_journal_share, () -> {
            JournalModel draft = model;
            draft.work(() -> { draft.runtime.save(draft.dom, entry, !shared, !shared); return true; }, ignored -> { }, R.string.journal_no_audience);
        }, false).setTag("journal_share");
        JournalUi.action(actions[1], R.string.journal_edit, R.drawable.ic_journal_edit, () -> beginEdit(entry, shared), false).setTag("journal_edit");
        TextView delete = JournalUi.action(content, R.string.journal_delete, R.drawable.ic_journal_delete, () -> {
            JournalModel draft = model;
            draft.work(() -> { draft.runtime.delete(draft.dom, entry.id); return true; }, ignored -> draft.selected = "", R.string.journal_failed);
        }, false); delete.setTag("journal_delete"); delete.setBackground(null); delete.setTextColor(getColor(R.color.text_secondary));
    }

    private void beginEdit(JournalEntry entry, boolean shared) {
        if (model.busy || !model.target.isLocal() || !authorized()) return;
        data.cancel("journal-screen"); closePlayers();
        model.editing = true; model.initialShared = shared; model.share = shared;
        message = 0;
        model.draft = UUID.randomUUID().toString(); model.id = entry == null ? UUID.randomUUID().toString().replace("-", "") : entry.id;
        model.created = entry == null ? System.currentTimeMillis() : entry.created;
        model.title = entry == null ? JournalEntry.defaultTitle(model.created) : entry.displayTitle();
        model.text = entry == null ? "" : entry.text; model.audio = entry == null ? null : entry.audio;
        synchronized (model) { model.attachments.clear(); if (entry != null) model.attachments.addAll(entry.attachments); }
        model.runtime.retainDraft(model.dom, model.draft, model.allMedia());
        rendered = ""; top(); renderEditor();
    }

    private void renderEditor() {
        if (content == null || !model.editing || !authorized()) return;
        model.runtime.retainDraft(model.dom, model.draft, model.allMedia());
        closePlayers(); content.removeAllViews(); recordingLocked.clear(); showMessageIn(content);
        if (model.busy) JournalUi.progress(content).setTag("journal_busy");
        LinearLayout form = JournalUi.surface(content); form.setTag("journal_editor");
        TextView titleLabel = JournalUi.text(form, getString(R.string.journal_entry_title), 15, true);
        EditText title = input(form, R.string.journal_entry_title, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        title.setTag("journal_title_input"); title.setId(View.generateViewId()); titleLabel.setLabelFor(title.getId());
        title.setSingleLine(true); title.setSaveEnabled(false);
        com.subhub.app.util.InputBounds.restore(title, model.title, JournalEntry.MAX_TITLE);
        title.setEnabled(!model.busy); recordingLocked.add(title);
        title.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int n, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int n) { model.title = s.toString(); }
            public void afterTextChanged(Editable value) { }
        });
        TextView label = JournalUi.heading(form, getString(R.string.journal_entry), R.drawable.ic_journal);
        editor = input(form, R.string.journal_text_hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setTag("journal_text"); editor.setId(View.generateViewId()); label.setLabelFor(editor.getId());
        editor.setSingleLine(false); editor.setMinLines(6); editor.setMaxLines(12); editor.setGravity(Gravity.TOP);
        editor.setTextSize(16); editor.setLineSpacing(dp(4), 1); editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(JournalEntry.MAX_TEXT)});
        editor.setSaveEnabled(false); editor.setText(model.text); editor.setEnabled(!model.busy);
        TextView count = JournalUi.text(form, getString(R.string.journal_text_count, model.text.length()), 12, false);
        count.setGravity(Gravity.END); count.setPadding(0, dp(8), 0, 0);
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int n, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int n) { model.text = s.toString(); count.setText(getString(R.string.journal_text_count, s.length())); }
            public void afterTextChanged(Editable text) { }
        });
        JournalUi.divider(form);
        LinearLayout sound = form; JournalUi.heading(sound, getString(R.string.journal_audio), R.drawable.ic_journal_microphone);
        if (model.audio != null) {
            audio(sound, model.audio, true);
        }
        LinearLayout[] recordControls = JournalUi.pair(sound);
        recordTime = JournalUi.text(recordControls[0], getString(R.string.journal_voice_limit), 13, false);
        recordTime.setTag("journal_record_time"); recordTime.setPadding(0, dp(14), 0, dp(8));
        recordButton = JournalUi.action(recordControls[1], model.audio == null ? R.string.journal_record : R.string.journal_record_again, R.drawable.ic_journal_microphone, () -> {
            if (recorder != null) stopRecording(true);
            else if (!model.busy) requestRecording();
        }, false); recordButton.setTag("journal_record"); recordButton.setEnabled(!model.busy);
        if (model.audio != null) {
            TextView removeAudio = JournalUi.action(sound, R.string.journal_remove_audio, R.drawable.ic_journal_delete, () -> {
                if (model.busy || recorder != null) return; model.audio = null; renderEditor();
            }, false); removeAudio.setTag("journal_remove_audio"); removeAudio.setBackground(null); recordingLocked.add(removeAudio);
        }
        JournalUi.divider(form);
        LinearLayout files = form;
        LinearLayout[] attachmentHeader = JournalUi.pair(files);
        JournalUi.heading(attachmentHeader[0], getString(R.string.journal_attachments, model.files().size()), R.drawable.ic_journal_attachment).setTag("journal_attachments_count");
        addButton = JournalUi.action(attachmentHeader[1], R.string.journal_add, R.drawable.ic_journal_add, () -> {
            if (!model.busy && recorder == null && model.files().size() < JournalEntry.MAX_ATTACHMENTS) picker.launch(new String[]{"*/*"});
        }, false); addButton.setTag("journal_add_attachment");
        addButton.setEnabled(!model.busy && model.files().size() < JournalEntry.MAX_ATTACHMENTS);
        addButton.setAlpha(addButton.isEnabled() ? 1f : .4f);
        addButton.setContentDescription(getString(R.string.journal_add_attachment));
        for (JournalAttachment file : model.files()) attachment(files, file, true, true);
        TextView attachmentLimit = JournalUi.text(files, getString(R.string.journal_attachment_limit), 12, false);
        attachmentLimit.setPadding(0, dp(8), 0, 0);
        JournalUi.divider(form);
        LinearLayout sharing = form;
        visibilityToggle = toggle(sharing, model.dom ? R.string.journal_share_subs : R.string.journal_share_dom,
                model.share, checked -> model.share = checked);
        visibilityToggle.setTag("journal_visibility"); visibilityToggle.setEnabled(!model.busy);
        LinearLayout[] actions = JournalUi.pair(content);
        saveButton = JournalUi.action(actions[0], R.string.profile_save, 0, this::save, true); saveButton.setTag("journal_save"); saveButton.setEnabled(!model.busy);
        JournalUi.action(actions[1], android.R.string.cancel, 0, this::back, false).setTag("journal_cancel");
        if (model.busy) disableActions(content);
    }

    private void save() {
        if (model.busy || recorder != null || !authorized()) return;
        final JournalEntry entry;
        try { entry = new JournalEntry(model.id, model.created, Math.max(model.created, System.currentTimeMillis()),
                JournalEntry.cleanTitle(model.title), JournalEntry.cleanText(model.text), model.audio, model.files()); }
        catch (IllegalArgumentException invalid) { showMessage(R.string.journal_empty_error); return; }
        JournalModel draft = model; boolean share = draft.share, newlyShared = !draft.initialShared;
        String token = draft.draft;
        draft.work(() -> { draft.runtime.save(draft.dom, entry, share, newlyShared); draft.runtime.releaseDraft(draft.dom, token); return entry.id; }, id -> {
            draft.editing = false; draft.selected = id; draft.audio = null; draft.attachments.clear(); draft.title = ""; draft.text = "";
        }, R.string.journal_failed);
    }

    private void back() {
        if (model == null) { finish(); return; }
        if (recorder != null) stopRecording(false);
        if (model.busy) { finish(); return; }
        if (model.editing) {
            JournalModel draft = model; String token = draft.draft;
            draft.work(() -> { draft.runtime.releaseDraft(draft.dom, token); return true; }, ignored -> {
                draft.editing = false; draft.audio = null; draft.attachments.clear(); draft.title = ""; draft.text = "";
            }, R.string.journal_failed);
        } else if (!model.selected.isEmpty()) { model.selected = ""; rendered = ""; top(); refresh(); }
        else finish();
    }

    private void requestRecording() {
        if (!authorized() || !model.editing) return;
        if (message != 0) { message = 0; renderEditor(); }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            microphone.launch(Manifest.permission.RECORD_AUDIO);
        else startRecording();
    }
    @SuppressWarnings("deprecation")
    private void startRecording() {
        if (!resumed || model.busy || !model.editing || recorder != null || !authorized()
                || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        for (JournalAudioPlayer player : players) player.setPlaybackEnabled(false);
        try {
            recording = File.createTempFile("journal-audio-", ".m4a", getCacheDir());
            recorder = new MediaRecorder(); recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1); recorder.setAudioSamplingRate(44100); recorder.setAudioEncodingBitRate(64000);
            recorder.setMaxDuration(5 * 60 * 1000); recorder.setMaxFileSize(JournalEntry.MAX_RECORDING_BYTES); recorder.setOutputFile(recording.getAbsolutePath());
            recorder.setOnInfoListener((value, what, extra) -> {
                if (value == recorder && (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)) stopRecording(true);
            });
            recorder.setOnErrorListener((value, what, extra) -> { if (value == recorder) { stopRecording(false); showMessage(R.string.journal_record_failed); } });
            recorder.prepare(); recorder.start(); recordingStarted = android.os.SystemClock.elapsedRealtime();
            recordButton.setText(R.string.journal_stop); editor.setEnabled(false); addButton.setEnabled(false);
            visibilityToggle.setEnabled(false); saveButton.setEnabled(false);
            for (View control : recordingLocked) control.setEnabled(false);
            timer.run();
        } catch (Exception failure) { stopRecording(false); showMessage(R.string.journal_record_failed); }
    }
    private void stopRecording(boolean keep) {
        if (recorder == null) return;
        handler.removeCallbacks(timer); MediaRecorder current = recorder; recorder = null;
        File file = recording; recording = null; boolean valid = keep;
        try { current.stop(); } catch (RuntimeException tooShort) { valid = false; }
        finally { current.release(); }
        if (file == null) return;
        if (valid && model != null && model.editing && authorized()) {
            JournalModel draft = model; String token = draft.draft; boolean dom = draft.dom;
            draft.work(() -> {
                try { return draft.runtime.importRecording(dom, token, file); }
                finally { Files.deleteIfExists(file.toPath()); }
            }, value -> draft.audio = value, R.string.journal_record_failed);
        } else {
            try { Files.deleteIfExists(file.toPath()); } catch (Exception ignored) { }
            if (resumed && model != null && model.editing) renderEditor();
        }
    }

    private void audio(LinearLayout parent, JournalAttachment file, boolean draft) {
        Uri uri = draft ? JournalProvider.draftUri(this, model.dom, model.draft, file)
                : JournalProvider.uri(this, model.dom, model.target, model.selected, file);
        JournalAudioPlayer player = new JournalAudioPlayer(this, uri, () -> showMessage(R.string.journal_media_pending));
        players.add(player); parent.addView(player, new LinearLayout.LayoutParams(-1, -2));
    }
    private void attachment(LinearLayout parent, JournalAttachment file, boolean draft, boolean ready) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(64));
        row.setTag("journal_attachment:" + file.hash);
        ImageView icon = new ImageView(this); icon.setImageResource(file.mime.startsWith("image/") ? R.drawable.ic_journal_photo
                : file.mime.startsWith("audio/") ? R.drawable.ic_journal_microphone
                : file.mime.startsWith("video/") ? R.drawable.ic_journal_play : R.drawable.ic_journal_file);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent_text)));
        icon.setBackgroundResource(R.drawable.bg_journal_media); icon.setClipToOutline(true);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout copy = PersonUi.column(this); LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        size.setMarginStart(dp(12)); row.addView(copy, size);
        TextView filename = JournalUi.text(copy, file.name, 14, true); filename.setTypeface(null, android.graphics.Typeface.BOLD);
        filename.setMaxLines(2); filename.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String kind = getString(file.mime.startsWith("image/") ? R.string.journal_photo : file.mime.startsWith("audio/")
                ? R.string.journal_audio : file.mime.startsWith("video/") ? R.string.journal_video : R.string.journal_file);
        TextView metadata = JournalUi.text(copy, getString(R.string.journal_file_summary, kind,
                android.text.format.Formatter.formatShortFileSize(this, file.length)), 12, false);
        metadata.setPadding(0, dp(4), 0, 0);
        if (!ready) JournalUi.text(copy, getString(R.string.journal_media_pending), 12, false);
        if (draft) {
            android.widget.ImageButton remove = new android.widget.ImageButton(this);
            remove.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            remove.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent_text)));
            android.util.TypedValue ripple = new android.util.TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
            remove.setBackground(getDrawable(ripple.resourceId)); remove.setPadding(dp(12), dp(12), dp(12), dp(12));
            remove.setContentDescription(getString(R.string.journal_remove_attachment, file.name));
            remove.setTag("journal_remove:" + file.hash);
            LinearLayout.LayoutParams removeSize = new LinearLayout.LayoutParams(dp(48), dp(48)); removeSize.setMarginStart(dp(8));
            row.addView(remove, removeSize);
            recordingLocked.add(remove);
            remove.setOnClickListener(view -> {
                if (model.busy || recorder != null) return;
                synchronized (model) { model.attachments.removeIf(item -> item.hash.equals(file.hash)); } renderEditor();
            });
        }
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (ready && file.mime.startsWith("image/")) {
            JournalRuntime runtime = model.runtime; boolean dom = model.dom; PersonId target = model.target;
            String selected = model.selected, token = model.draft;
            data.load("journal-thumb:" + file.hash, () -> {
                File source = draft ? runtime.draftFile(dom, token, file.hash) : runtime.mediaFile(dom, target, selected, file.hash);
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
                while ((long) bounds.outWidth / options.inSampleSize > 192 || (long) bounds.outHeight / options.inSampleSize > 192) options.inSampleSize *= 2;
                return BitmapFactory.decodeFile(source.getAbsolutePath(), options);
            }, bitmap -> {
                if (bitmap == null) return;
                try {
                    if (!authorized() || !icon.isAttachedToWindow()) throw new SecurityException("Journal preview no longer visible");
                    if (draft) runtime.draftFile(dom, token, file.hash); else runtime.mediaFile(dom, target, selected, file.hash);
                    icon.setImageTintList(null); icon.setPadding(0, 0, 0, 0); icon.setScaleType(ImageView.ScaleType.CENTER_CROP); icon.setImageBitmap(bitmap);
                } catch (Exception unavailable) { bitmap.recycle(); }
            }, ignored -> { });
        }
        row.setOnClickListener(view -> {
            if (model.busy || recorder != null || !authorized()) return;
            if (!ready) { notice(getString(R.string.journal_media_pending)); return; }
            Uri uri = draft ? JournalProvider.draftUri(this, model.dom, model.draft, file)
                    : JournalProvider.uri(this, model.dom, model.target, model.selected, file);
            try {
                Intent viewFile = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                viewFile.setClipData(ClipData.newUri(getContentResolver(), file.name, uri));
                startActivity(viewFile);
            }
            catch (android.content.ActivityNotFoundException missing) { notice(getString(R.string.journal_no_viewer)); }
        });
        row.setClickable(true); row.setFocusable(true); row.setContentDescription(getString(R.string.journal_open_attachment, file.name));
        row.setEnabled(ready); row.setAlpha(ready ? 1f : .75f);
    }
    private void closePlayers() { for (JournalAudioPlayer player : players) player.close(); players.clear(); }
    private void top() { if (page != null && page.getParent() instanceof ScrollView) ((ScrollView) page.getParent()).scrollTo(0, 0); }
    private String entryTitle(JournalEntry entry) {
        return entry.displayTitle();
    }
    private static String date(long time) { return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(time)); }
}
