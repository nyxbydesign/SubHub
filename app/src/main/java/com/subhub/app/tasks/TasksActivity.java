package com.subhub.app.tasks;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.SwitchCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.subhub.app.R;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.PersonUi;
import com.subhub.app.people.RolePage;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.util.InputFocusPolicy;
import com.subhub.app.util.SubHubNavigation;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Native, relationship-scoped task authoring and completion review. */
public final class TasksActivity extends RolePage {
    private static final DateTimeFormatter START = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final String[] MIME_TYPES = {"image/jpeg", "image/png", "image/webp", "image/gif",
            "audio/mpeg", "audio/ogg", "audio/wav", "audio/mp4", "video/mp4", "video/webm"};
    private UiState model;
    private TaskRepository repository;
    private PersonId person;
    private boolean primary;
    private boolean resumed;
    private TaskAttachmentView inlineMedia;
    private List<Object> inlineSignature;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (!model.busy && model.form.isEmpty()) refresh();
            clock.postDelayed(this, 30_000);
        }
    };
    private final ActivityResultLauncher<Intent> documents = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (model == null) return;
                Picker request = model.picker;
                model.picker = null;
                if (request == null || result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri == null || !request.matches(model) || model.busy) return;
                TaskRepository repo = repository;
                UiState state = model;
                model.run(repo, request.scope, request.dom, () -> repo.importMedia(uri), attachment -> {
                    if (!request.matches(state)) return;
                    ArrayList<String> media = media(state.draft);
                    for (String raw : media) {
                        if (parseAttachment(raw).hash.equals(attachment.hash)) {
                            state.message = R.string.tasks_duplicate_media;
                            return;
                        }
                    }
                    try { media.add(attachment.json().toString()); }
                    catch (Exception invalid) { state.message = R.string.tasks_import_error; return; }
                    state.draft.putStringArrayList("media", media);
                }, true);
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        primary = state == null ? !getIntent().hasExtra(PersonRoutes.EXTRA_PERSON_ID)
                : state.getBoolean("tasks.primary");
        if (!getIntent().hasExtra(PersonRoutes.EXTRA_PERSON_ID))
            getIntent().putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value());
        try { person = PersonRoutes.requireId(getIntent()); }
        catch (IllegalArgumentException invalid) { finish(); return; }
        repository = new TaskRepository(getApplicationContext(), person);
        model = new ViewModelProvider(this).get(UiState.class);
        model.app = getApplicationContext();
        if (model.person == null) model.restore(person.value(), state);
        if (!person.value().equals(model.person)) { finish(); return; }
        page(R.string.tasks_title, SubHubNavigation.Screen.TASKS, false);
        body.setSaveFromParentEnabled(false);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                navigateBack();
            }
        });
        InputFocusPolicy.openActivityForReading(this);
    }

    @Override protected void onResume() {
        resumed = true;
        if (model != null) model.changed = this::render;
        super.onResume();
        if (model != null) clock.postDelayed(tick, 30_000);
    }

    @Override protected void onPause() {
        resumed = false;
        clock.removeCallbacks(tick);
        if (model != null) model.changed = null;
        closeInlineMedia();
        if (body != null) body.removeAllViews();
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        primary = !intent.hasExtra(PersonRoutes.EXTRA_PERSON_ID);
        if (!intent.hasExtra(PersonRoutes.EXTRA_PERSON_ID))
            intent.putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value());
        PersonId requested;
        try { requested = PersonRoutes.requireId(intent); }
        catch (IllegalArgumentException invalid) { finish(); return; }
        if (!requested.equals(person)) {
            finish();
            startActivity(new Intent(this, TasksActivity.class)
                    .putExtra(PersonRoutes.EXTRA_PERSON_ID, requested.value()));
            return;
        }
        setIntent(intent);
        showSectionHome();
        refresh();
    }

    public void showSectionHome() {
        if (model == null || model.busy) return;
        primary = person.isLocal();
        model.closeForm();
        model.selected = "";
        render();
        scrollTop();
    }

    private void navigateBack() {
        if (model == null || model.busy) return;
        if (!model.form.isEmpty()) { model.closeForm(); render(); scrollTop(); }
        else if (!model.selected.isEmpty()) { model.selected = ""; render(); scrollTop(); }
        else finish();
    }

    private void scrollTop() {
        if (body != null && body.getParent() instanceof LinearLayout
                && ((View) body.getParent()).getParent() instanceof android.widget.ScrollView)
            ((android.widget.ScrollView) ((View) body.getParent()).getParent()).post(() ->
                    ((android.widget.ScrollView) ((View) body.getParent()).getParent()).scrollTo(0, 0));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("tasks.primary", primary);
        if (model != null) model.save(state);
        super.onSaveInstanceState(state);
    }

    @Override protected void refresh() {
        if (model == null || !resumed) return;
        if (!samePerson()) { finish(); return; }
        model.refresh(repository);
        render();
    }

    private boolean samePerson() {
        try { return person.equals(PersonRoutes.requireId(getIntent())); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private void render() {
        if (!resumed || isFinishing() || !samePerson()) return;
        Snapshot state = model.snapshot;
        List<Object> signature = inlineSignature(state);
        if (inlineMedia != null && !inlineMedia.isClosed() && !model.busy && model.failure == null
                && signature != null && signature.equals(inlineSignature) && inlineAuthorized()) return;
        closeInlineMedia();
        body.removeAllViews();
        title("create".equals(model.form) ? R.string.tasks_new
                : !model.form.isEmpty() ? R.string.tasks_request : R.string.tasks_title);
        boolean nested = !primary || !model.form.isEmpty() || !model.selected.isEmpty();
        TextView back = com.subhub.app.util.PrimaryHeader.backButton(findViewById(android.R.id.content));
        back.setVisibility(nested ? View.VISIBLE : View.GONE);
        back.setOnClickListener(v -> navigateBack());
        com.subhub.app.util.PrimaryHeader.editLockButton(findViewById(android.R.id.content))
                .setVisibility(nested ? View.GONE : View.VISIBLE);
        if (state == null) {
            LinearLayout loading = PersonUi.card(body);
            PersonUi.text(loading, getString(R.string.role_loading), false);
            return;
        }
        if (state.dom != ControllerPinManager.isDomModeActive()) {
            model.snapshot = null;
            refresh();
            return;
        }
        if (model.message != 0) {
            TextView error = PersonUi.text(PersonUi.card(body), getString(model.message), false);
            error.setTag("tasks_error");
            error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
        if (model.failure != null) {
            TextView error = PersonUi.text(PersonUi.card(body), safeError(model.failure), false);
            error.setTag("tasks_error");
            error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
        if (!state.valid) {
            PersonUi.text(PersonUi.card(body), getString(R.string.tasks_unavailable), true);
            action(body, R.string.tasks_refresh, "tasks_refresh", this::refresh, false);
            return;
        }
        PersonUi.text(body, state.name, true).setTag("tasks_target");
        if (!person.isLocal() && !state.supported)
            PersonUi.text(body, getString(R.string.tasks_update_devices), false);
        if (model.busy || model.loading) PersonUi.text(body,
                getString(model.busy ? R.string.tasks_working : R.string.role_loading), false);
        if (!model.form.isEmpty()) {
            if ("create".equals(model.form) && state.canCreate && state.allowed) author();
            else {
                TaskEngine.Occurrence occurrence = state.find(model.form);
                if (!state.dom && state.allowed && occurrence != null
                        && occurrence.status == TaskEngine.Status.ACTIVE && !state.refused.contains(occurrence.task.id))
                    proof(occurrence);
                else { model.closeForm(); render(); }
            }
            return;
        }
        if (state.canOptOut) {
            LinearLayout consent = PersonUi.card(body);
            SwitchCompat allowed = new SwitchCompat(this);
            allowed.setText(R.string.tasks_allowed);
            allowed.setTextColor(getColor(R.color.text_primary));
            allowed.setMinHeight(PersonUi.dp(this, 48));
            allowed.setChecked(state.chosen);
            allowed.setSaveEnabled(false);
            allowed.setTag("tasks_allowed");
            allowed.setEnabled(!model.busy && !model.loading);
            consent.addView(allowed, new LinearLayout.LayoutParams(-1, -2));
            TaskRepository repo = repository;
            allowed.setOnCheckedChangeListener((button, checked) -> mutate(() -> {
                repo.setAllowed(checked); return null;
            }, false));
            if (!state.chosen && state.allowed)
                PersonUi.text(consent, getString(R.string.tasks_hardcore_override), false);
        }
        if (!state.allowed) {
            PersonUi.text(PersonUi.card(body), getString(R.string.tasks_disabled), false);
            if (!state.dom) return;
        }
        if (!model.selected.isEmpty()) {
            TaskEngine.Occurrence selected = state.find(model.selected);
            if (selected == null) {
                model.selected = "";
                render();
            } else detail(state, selected);
            return;
        }
        LinearLayout summary = PersonUi.card(body);
        summary.setTag("tasks_summary");
        TasksUi.counts(summary, count(state, TaskEngine.Status.ACTIVE),
                count(state, TaskEngine.Status.PENDING), count(state, TaskEngine.Status.SCHEDULED));
        if (state.canCreate && state.allowed && state.zone == null) {
            PersonUi.text(body, getString(R.string.tasks_timezone_unavailable), false);
            action(body, R.string.tasks_refresh, "tasks_refresh", this::refresh, false);
        }
        if (state.canCreate && state.allowed && state.zone != null) action(body, R.string.tasks_create, "tasks_create", () -> {
            model.openForm("create");
            model.draft.putString("id", UUID.randomUUID().toString());
            model.draft.putString("start", START.format(LocalDateTime.now(state.zone).plusHours(1).withSecond(0).withNano(0)));
            model.draft.putString("zone", state.zone.getId());
            model.draft.putString("duration", "60");
            render(); scrollTop(); InputFocusPolicy.openActivityForReading(this);
        }, true);
        CheckBox history = check(body, R.string.tasks_show_history, "tasks_history", model.history);
        history.setOnCheckedChangeListener((button, checked) -> { model.history = checked; render(); });
        int shown = count(state, TaskEngine.Status.ACTIVE) + count(state, TaskEngine.Status.PENDING)
                + count(state, TaskEngine.Status.SCHEDULED);
        if (model.history) shown += count(state, TaskEngine.Status.ACCEPTED) + count(state, TaskEngine.Status.DENIED)
                + count(state, TaskEngine.Status.TIMED_OUT) + count(state, TaskEngine.Status.REFUSED)
                + count(state, TaskEngine.Status.SKIPPED) + count(state, TaskEngine.Status.CANCELLED);
        if (shown == 0) TasksUi.empty(body, R.string.tasks_no_open);
        else {
            section(state, R.string.tasks_active, TaskEngine.Status.ACTIVE);
            section(state, R.string.tasks_pending, TaskEngine.Status.PENDING);
            section(state, R.string.tasks_scheduled, TaskEngine.Status.SCHEDULED);
            if (model.history) section(state, R.string.tasks_history, TaskEngine.Status.ACCEPTED,
                    TaskEngine.Status.DENIED, TaskEngine.Status.TIMED_OUT, TaskEngine.Status.REFUSED,
                    TaskEngine.Status.SKIPPED, TaskEngine.Status.CANCELLED);
        }
        action(body, R.string.tasks_refresh, "tasks_refresh", this::refresh, false);
    }

    private static int count(Snapshot state, TaskEngine.Status status) {
        int count = 0;
        for (TaskEngine.Occurrence task : state.rows) if (task.status == status
                && !(state.refused.contains(task.task.id) && (status == TaskEngine.Status.ACTIVE
                || status == TaskEngine.Status.SCHEDULED))) count++;
        return count;
    }

    private void section(Snapshot state, int heading, TaskEngine.Status... statuses) {
        Set<TaskEngine.Status> selected = EnumSet.noneOf(TaskEngine.Status.class);
        java.util.Collections.addAll(selected, statuses);
        List<TaskEngine.Occurrence> visible = new ArrayList<>();
        for (TaskEngine.Occurrence occurrence : state.rows) {
            if (!selected.contains(occurrence.status)) continue;
            if (state.refused.contains(occurrence.task.id) && (occurrence.status == TaskEngine.Status.ACTIVE
                    || occurrence.status == TaskEngine.Status.SCHEDULED)) continue;
            visible.add(occurrence);
        }
        if (visible.isEmpty()) return;
        PersonUi.text(body, getString(R.string.tasks_section_count, getString(heading), visible.size()), true);
        for (TaskEngine.Occurrence occurrence : visible) {
            LinearLayout card = PersonUi.card(body);
            card.setTag("task:" + occurrence.id);
            DateTimeFormatter display = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                    .withZone(occurrence.task.zone);
            String timing = getString(occurrence.status == TaskEngine.Status.SCHEDULED
                    ? R.string.tasks_starts : R.string.tasks_due,
                    display.format(occurrence.status == TaskEngine.Status.SCHEDULED
                            ? occurrence.task.start(occurrence.date) : occurrence.task.end(occurrence.date)));
            PersonUi.row(card, R.drawable.ic_nav_tasks, occurrence.task.title, timing, () -> {
                if (model.busy || model.loading) return;
                model.selected = occurrence.id;
                render(); scrollTop();
            }).setTag("task_open:" + occurrence.id);
            TasksUi.badge(card, getString(statusLabel(occurrence.status)));
        }
    }

    private void detail(Snapshot state, TaskEngine.Occurrence occurrence) {
        LinearLayout card = PersonUi.card(body);
        card.setTag("task_detail:" + occurrence.id);
        details(card, occurrence);
        if (state.refused.contains(occurrence.task.id))
            PersonUi.text(card, getString(R.string.tasks_series_refused), false);
        if (occurrence.submission != null) {
            LinearLayout submission = PersonUi.card(body);
            PersonUi.text(submission, getString(R.string.tasks_submission_heading), true);
            if (occurrence.submission.kind == TaskAction.Kind.OUTSIDE)
                PersonUi.text(submission, getString(R.string.tasks_outside_pending), false);
            else {
                if (!occurrence.submission.text.isEmpty()) PersonUi.text(submission, occurrence.submission.text, false);
                for (TaskAttachment attachment : occurrence.submission.attachments) attachment(submission, attachment);
            }
        }
        LinearLayout actions = PersonUi.card(body);
        if (state.canCreate && occurrence.status == TaskEngine.Status.PENDING && occurrence.submission != null) {
            action(actions, R.string.tasks_accept, "accept:" + occurrence.id,
                    () -> act(occurrence, TaskAction.Kind.ACCEPT), true);
            action(actions, R.string.tasks_deny, "deny:" + occurrence.id,
                    () -> act(occurrence, TaskAction.Kind.DENY), false);
        } else if (!state.dom && !state.refused.contains(occurrence.task.id)) {
            if (occurrence.status == TaskEngine.Status.ACTIVE) {
                action(actions, R.string.tasks_request, "request:" + occurrence.id, () -> {
                    model.openForm(occurrence.id); render(); scrollTop(); InputFocusPolicy.openActivityForReading(this);
                }, true);
                action(actions, R.string.tasks_outside, "outside:" + occurrence.id,
                        () -> act(occurrence, TaskAction.Kind.OUTSIDE), false);
                PersonUi.text(actions, getString(R.string.tasks_outside_help), false);
            }
            if (occurrence.task.allowRefusal && (occurrence.status == TaskEngine.Status.ACTIVE
                    || occurrence.status == TaskEngine.Status.SCHEDULED)) {
                action(actions, R.string.tasks_refuse_once, "refuse:" + occurrence.id,
                        () -> act(occurrence, TaskAction.Kind.REFUSE_ONCE), false);
                if (occurrence.task.recurring()) action(actions, R.string.tasks_refuse_series,
                        "refuse_series:" + occurrence.id, () -> act(occurrence, TaskAction.Kind.REFUSE_SERIES), false);
            }
        }
        if (state.dom && occurrence.status != TaskEngine.Status.CANCELLED) {
            if (state.canCancel) action(actions, R.string.tasks_cancel_task, "cancel_task:" + occurrence.id,
                    () -> confirmCancellation(occurrence), false);
            else PersonUi.text(actions, getString(R.string.tasks_cancel_update), false);
        }
        if (actions.getChildCount() == 0) body.removeView(actions);
        if (state.allowed && occurrence.task.attachment != null && inlineAuthorized()) {
            LinearLayout media = PersonUi.card(body);
            PersonUi.text(media, getString(R.string.tasks_attachment_heading), true);
            inlineMedia = new TaskAttachmentView(this, data, repository, occurrence.task.attachment,
                    model.scope, state.dom, this::inlineAuthorized, this::refresh);
            media.addView(inlineMedia, new LinearLayout.LayoutParams(-1, -2));
            inlineSignature = inlineSignature(state);
        }
    }

    private void confirmCancellation(TaskEngine.Occurrence occurrence) {
        if (model.busy || !model.dom || !ControllerPinManager.isDomModeActive()) return;
        if (inlineMedia != null) inlineMedia.pause();
        boolean[] series = {false};
        androidx.appcompat.app.AlertDialog.Builder dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.tasks_cancel_task)
                .setNegativeButton(R.string.tasks_keep_task, null)
                .setPositiveButton(R.string.tasks_cancel_task, (window, which) -> {
                    TaskRepository repo = repository;
                    mutate(() -> { repo.cancel(occurrence.task.id, occurrence.date, series[0]); return null; }, true);
                });
        if (occurrence.task.recurring()) dialog.setSingleChoiceItems(
                new String[]{getString(R.string.tasks_cancel_occurrence), getString(R.string.tasks_cancel_series)},
                0, (window, which) -> series[0] = which == 1);
        else dialog.setMessage(getString(R.string.tasks_cancel_confirm, occurrence.task.title));
        dialog.show();
    }

    private boolean inlineAuthorized() {
        try {
            return resumed && samePerson() && model.form.isEmpty() && !model.selected.isEmpty()
                    && model.dom == ControllerPinManager.isDomModeActive()
                    && model.scope.equals(repository.target().scope) && repository.allowed();
        } catch (RuntimeException denied) { return false; }
    }

    private List<Object> inlineSignature(Snapshot state) {
        if (state == null || !state.valid || !state.allowed || !model.form.isEmpty() || model.selected.isEmpty()) return null;
        TaskEngine.Occurrence row = state.find(model.selected);
        if (row == null || row.task.attachment == null) return null;
        return java.util.Arrays.asList(model.scope, state.dom, state.canCancel, state.canCreate, model.selected, row.status,
                row.submission == null ? "" : row.submission.id, state.refused.contains(row.task.id), model.message);
    }

    private void closeInlineMedia() {
        if (inlineMedia != null) { inlineMedia.close(); inlineMedia = null; }
        inlineSignature = null;
    }

    private void details(LinearLayout card, TaskEngine.Occurrence occurrence) {
        PersonUi.text(card, occurrence.task.title, true).setTextSize(24);
        TasksUi.badge(card, getString(statusLabel(occurrence.status)));
        DateTimeFormatter display = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(occurrence.task.zone);
        PersonUi.text(card, getString(R.string.tasks_window, display.format(occurrence.task.start(occurrence.date)),
                display.format(occurrence.task.end(occurrence.date)), occurrence.task.zone.getId()), false);
        if (occurrence.task.recurring()) {
            String[] labels = getResources().getStringArray(R.array.tasks_weekdays);
            ArrayList<String> days = new ArrayList<>();
            for (DayOfWeek day : DayOfWeek.values()) if (occurrence.task.weekdays.contains(day)) days.add(labels[day.getValue() - 1]);
            PersonUi.text(card, String.join(" · ", days), false);
        }
        if (!occurrence.task.text.isEmpty()) PersonUi.text(card, occurrence.task.text, false);
    }

    private void author() {
        LinearLayout card = PersonUi.card(body);
        EditText title = field(card, R.string.tasks_task_title, "title", 160, false);
        EditText instructions = field(card, R.string.tasks_body, "text", 8000, true);
        card = PersonUi.card(body);
        PersonUi.text(card, getString(R.string.tasks_schedule), true);
        EditText start = field(card, R.string.tasks_start, "start", 16, false);
        ZoneId scheduleZone = null;
        try { scheduleZone = ZoneId.of(model.draft.getString("zone", "")); }
        catch (Exception invalid) { /* A restored incomplete draft cannot be submitted. */ }
        boolean zoneReady = scheduleZone != null && scheduleZone.equals(model.snapshot.zone);
        PersonUi.text(card, zoneReady ? getString(R.string.tasks_timezone, scheduleZone.getId())
                : getString(R.string.tasks_timezone_unavailable), false).setTag("tasks_timezone");
        if (!zoneReady) action(card, R.string.tasks_refresh, "tasks_reload_zone", () -> {
            if (model.snapshot.zone != null) model.draft.putString("zone", model.snapshot.zone.getId());
            refresh();
        }, false);
        EditText duration = field(card, R.string.tasks_duration, "duration", 5, false);
        duration.setInputType(InputType.TYPE_CLASS_NUMBER);
        PersonUi.text(card, getString(R.string.tasks_repeat), true);
        PersonUi.text(card, getString(R.string.tasks_repeat_help), false);
        String[] labels = getResources().getStringArray(R.array.tasks_weekdays);
        // Two columns retain full native touch targets at narrow widths and large font scales.
        for (int i = 0; i < 7; i += 2) {
            LinearLayout row = new LinearLayout(this);
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));
            for (int j = i; j < Math.min(7, i + 2); j++) {
                final int day = j + 1;
                CheckBox value = check(row, 0, "tasks_day_" + day, model.draft.getBoolean("day" + day));
                value.setText(labels[j]);
                value.setContentDescription(DayOfWeek.of(day).getDisplayName(java.time.format.TextStyle.FULL,
                        getResources().getConfiguration().getLocales().get(0)));
                value.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                value.setOnCheckedChangeListener((button, checked) -> model.draft.putBoolean("day" + day, checked));
            }
        }
        card = PersonUi.card(body);
        PersonUi.text(card, getString(R.string.tasks_completion), true);
        CheckBox refusal = check(card, R.string.tasks_allow_refusal, "tasks_refusal", model.draft.getBoolean("refusal"));
        refusal.setOnCheckedChangeListener((button, checked) -> model.draft.putBoolean("refusal", checked));
        CheckBox tribute = check(card, R.string.tasks_failure_tribute, "tasks_tribute", model.draft.getBoolean("tribute"));
        tribute.setOnCheckedChangeListener((button, checked) -> model.draft.putBoolean("tribute", checked));
        PersonUi.text(card, getString(R.string.tasks_failure_help), false);
        LinearLayout media = PersonUi.card(body);
        PersonUi.text(media, getString(R.string.tasks_attachment_heading), true);
        mediaEditor(media, 1);
        action(body, R.string.tasks_create, "tasks_save", () -> {
            title.setError(null); start.setError(null); duration.setError(null);
            if (title.getText().toString().trim().isEmpty()) { InputFocusPolicy.showError(title, getString(R.string.tasks_title_error)); return; }
            LocalDateTime at;
            ZoneId timezone;
            long minutes;
            try {
                timezone = repository.schedulingZone();
                if (!timezone.getId().equals(model.draft.getString("zone", ""))) throw new IllegalStateException();
            } catch (Exception invalid) {
                model.message = R.string.tasks_timezone_unavailable; refresh(); return;
            }
            try {
                at = LocalDateTime.parse(start.getText().toString().trim(), START);
                if (at.atZone(timezone).toInstant().toEpochMilli() <= System.currentTimeMillis()
                        || at.getYear() < 2000 || at.getYear() > 2100) throw new IllegalArgumentException();
            } catch (Exception invalid) { InputFocusPolicy.showError(start, getString(R.string.tasks_start_error)); return; }
            try {
                minutes = Long.parseLong(duration.getText().toString());
                if (minutes < 1 || minutes > 43200) throw new IllegalArgumentException();
            } catch (Exception invalid) { InputFocusPolicy.showError(duration, getString(R.string.tasks_duration_error)); return; }
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            for (int day = 1; day <= 7; day++) if (model.draft.getBoolean("day" + day)) days.add(DayOfWeek.of(day));
            List<TaskAttachment> attachments = attachments(model.draft);
            TaskDefinition definition = new TaskDefinition(model.draft.getString("id"), title.getText().toString(),
                    instructions.getText().toString(), at, timezone, minutes * 60_000L, days,
                    refusal.isChecked(), tribute.isChecked(), attachments.isEmpty() ? null : attachments.get(0));
            TaskRepository repo = repository;
            mutate(() -> { repo.create(definition); return null; }, true);
        }, true);
        action(body, R.string.tasks_cancel, "tasks_cancel", () -> { model.closeForm(); render(); scrollTop(); }, false);
    }

    private void proof(TaskEngine.Occurrence occurrence) {
        details(PersonUi.card(body), occurrence);
        LinearLayout card = PersonUi.card(body);
        PersonUi.text(card, getString(R.string.tasks_submission_heading), true);
        PersonUi.text(card, getString(R.string.tasks_proof_help), false);
        EditText text = field(card, R.string.tasks_proof, "text", 8000, true);
        mediaEditor(card, 5);
        action(card, R.string.tasks_send, "tasks_send", () -> {
            String copy = text.getText().toString();
            List<TaskAttachment> media = attachments(model.draft);
            if (copy.trim().isEmpty() && media.isEmpty()) {
                InputFocusPolicy.showError(text, getString(R.string.tasks_proof_error)); return;
            }
            TaskRepository repo = repository;
            mutate(() -> { repo.act(occurrence.task.id, occurrence.date, TaskAction.Kind.SUBMIT, copy, media, ""); return null; }, true);
        }, true);
        action(card, R.string.tasks_cancel, "tasks_cancel", () -> { model.closeForm(); render(); }, false);
    }

    private void mediaEditor(LinearLayout card, int maximum) {
        ArrayList<String> media = media(model.draft);
        PersonUi.text(card, getString(R.string.tasks_attachment_count, media.size(), maximum), false);
        for (int i = 0; i < media.size(); i++) {
            TaskAttachment value = parseAttachment(media.get(i));
            PersonUi.text(card, mediaLabel(this, value), false);
            final int index = i;
            TextView remove = PersonUi.action(card, R.string.tasks_cancel, () -> {
                ArrayList<String> next = media(model.draft); next.remove(index);
                model.draft.putStringArrayList("media", next); render();
            }, false);
            remove.setText(getString(R.string.tasks_remove_attachment, i + 1));
            remove.setEnabled(!model.busy);
        }
        if (media.size() < maximum) action(card, R.string.tasks_add_attachment, "tasks_add_media", () -> {
            if (model.picker != null || model.busy) return;
            model.picker = new Picker(model.scope, model.dom, model.form, model.formToken);
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_MIME_TYPES, MIME_TYPES)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { documents.launch(intent); }
            catch (RuntimeException unavailable) { model.picker = null; model.message = R.string.tasks_import_error; render(); }
        }, false);
        PersonUi.text(card, getString(R.string.tasks_media_help), false);
    }

    private void attachment(LinearLayout parent, TaskAttachment attachment) {
        LinearLayout row = PersonUi.row(parent, 0, getString(R.string.tasks_attachment, mediaLabel(this, attachment)), "", () -> {
            if (model.busy) return;
            try {
                startActivity(new Intent(this, TaskMediaActivity.class)
                        .putExtra(PersonRoutes.EXTRA_PERSON_ID, person.value())
                        .putExtra(TaskMediaActivity.EXTRA_ATTACHMENT, attachment.json().toString())
                        .putExtra(TaskMediaActivity.EXTRA_SCOPE, model.scope)
                        .putExtra(TaskMediaActivity.EXTRA_DOM, model.dom));
            } catch (Exception invalid) { model.message = R.string.tasks_media_unavailable; render(); }
        });
        row.setTag("task_attachment:" + attachment.hash);
        row.setEnabled(!model.busy && !model.loading);
    }

    private void act(TaskEngine.Occurrence occurrence, TaskAction.Kind kind) {
        TaskRepository repo = repository;
        String submission = (kind == TaskAction.Kind.ACCEPT || kind == TaskAction.Kind.DENY)
                && occurrence.submission != null ? occurrence.submission.id : "";
        mutate(() -> { repo.act(occurrence.task.id, occurrence.date, kind, "", List.of(), submission); return null; }, false);
    }

    private void mutate(Work<Void> work, boolean closeForm) {
        if (model.busy || model.snapshot == null) return;
        UiState state = model;
        model.run(repository, model.scope, model.dom, work, ignored -> {
            if (closeForm) state.closeForm();
        }, false);
    }

    private TextView action(LinearLayout parent, int label, String tag, Runnable click, boolean primary) {
        TextView value = PersonUi.action(parent, label, () -> {
            if (!model.busy && !model.loading && resumed) click.run();
        }, primary);
        value.setTag(tag); value.setEnabled(!model.busy && !model.loading); value.setSaveEnabled(false);
        return value;
    }

    private CheckBox check(LinearLayout parent, int label, String tag, boolean checked) {
        CheckBox value = new CheckBox(this);
        if (label != 0) value.setText(label);
        value.setTextColor(getColor(R.color.text_primary));
        value.setMinHeight(PersonUi.dp(this, 48));
        value.setSaveEnabled(false); value.setTag(tag); value.setChecked(checked); value.setEnabled(!model.busy && !model.loading);
        value.setButtonTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent_text)));
        parent.addView(value, new LinearLayout.LayoutParams(-1, -2));
        return value;
    }

    private EditText field(LinearLayout parent, int label, String key, int limit, boolean multiline) {
        TextView caption = PersonUi.text(parent, getString(label), false);
        EditText value = new EditText(this);
        value.setId(View.generateViewId()); caption.setLabelFor(value.getId());
        value.setTag("tasks_" + key); value.setSaveEnabled(false);
        value.setTextColor(getColor(R.color.text_primary));
        value.setTextSize(16);
        value.setBackgroundResource(R.drawable.bg_input);
        int inset = PersonUi.dp(this, 12);
        value.setPadding(inset, inset, inset, inset);
        value.setHintTextColor(getColor(R.color.text_secondary));
        value.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        value.setMinHeight(PersonUi.dp(this, 48));
        if (multiline) { value.setMinLines(3); value.setGravity(android.view.Gravity.TOP); }
        value.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        value.setText(model.draft.getString(key, ""));
        value.setEnabled(!model.busy && !model.loading);
        Bundle draft = model.draft;
        value.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { draft.putString(key, s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = PersonUi.dp(this, 12);
        parent.addView(value, size);
        return value;
    }

    static String mediaLabel(Context context, TaskAttachment attachment) {
        return context.getString(attachment.mime.startsWith("image/") ? R.string.tasks_image
                : attachment.mime.startsWith("audio/") ? R.string.tasks_audio : R.string.tasks_video);
    }

    private String safeError(Exception failure) {
        // Only fixed domain errors may be displayed; providers can put private URIs in exceptions.
        String message = failure.getMessage();
        if (message != null && Set.of("Task is not accepting completion requests", "Completion request is no longer pending",
                "Task can no longer be refused", "Task has expired", "Task refusal is not enabled", "Task history is full",
                "Task limit reached", "Choose a future start time", "Attachment is not ready", "Tasks are disabled").contains(message))
            return message;
        return getString(failure instanceof SecurityException ? R.string.tasks_changed : R.string.tasks_error);
    }

    private static int statusLabel(TaskEngine.Status status) {
        switch (status) {
            case ACTIVE: return R.string.tasks_active;
            case SCHEDULED: return R.string.tasks_scheduled;
            case PENDING: return R.string.tasks_pending;
            case ACCEPTED: return R.string.tasks_accepted;
            case DENIED: return R.string.tasks_denied;
            case TIMED_OUT: return R.string.tasks_timed_out;
            case SKIPPED: return R.string.tasks_skipped;
            case CANCELLED: return R.string.tasks_cancelled;
            default: return R.string.tasks_refused;
        }
    }

    private static ArrayList<String> media(Bundle draft) {
        ArrayList<String> values = draft.getStringArrayList("media");
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
    private static TaskAttachment parseAttachment(String raw) {
        try { return TaskAttachment.parse(new JSONObject(raw)); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid task attachment"); }
    }
    private static List<TaskAttachment> attachments(Bundle draft) {
        List<TaskAttachment> result = new ArrayList<>();
        for (String raw : media(draft)) result.add(parseAttachment(raw));
        return result;
    }

    private interface Work<T> { T call() throws Exception; }
    private interface Result<T> { void accept(T value); }

    private static void verify(TaskRepository repo, String scope, boolean dom) {
        if (!scope.equals(repo.target().scope) || dom != repo.isDom()) throw new SecurityException("Task target changed");
    }

    private static final class Snapshot {
        String scope, name;
        ZoneId zone;
        boolean valid = true, dom, allowed, chosen, canCreate, canOptOut, supported, canCancel;
        List<TaskEngine.Occurrence> rows = List.of();
        final Set<String> refused = new HashSet<>();
        TaskEngine.Occurrence find(String id) {
            for (TaskEngine.Occurrence row : rows) if (row.id.equals(id)) return row;
            return null;
        }
        static Snapshot read(TaskRepository repo, Context app) throws Exception {
            Snapshot value = new Snapshot();
            TaskRepository.Target target = repo.target();
            value.scope = target.scope; value.dom = repo.isDom(); value.allowed = repo.allowed();
            value.name = target.peer == null ? com.subhub.app.people.ProfileNames.get(app, false) : target.peer.name;
            value.chosen = SubFeaturePolicy.chosen(app, "tasks");
            value.canOptOut = repo.person.isLocal() && !value.dom && !DeviceRolePreferences.isDomWorkspace(app);
            value.supported = repo.supported();
            value.canCancel = value.dom && repo.cancellationSupported();
            value.canCreate = value.supported && value.allowed && value.dom && ControllerPinManager.hasCredentials(app)
                    && (!repo.person.isLocal() || PersonRoutes.canEditLocal(app, repo.person));
            try { value.zone = repo.schedulingZone(); }
            catch (IllegalStateException unavailable) { value.zone = null; }
            value.rows = repo.list(System.currentTimeMillis());
            TaskEngine engine = repo.engine();
            for (TaskDefinition definition : engine.definitions()) if (engine.seriesRefused(definition.id)) value.refused.add(definition.id);
            verify(repo, value.scope, value.dom);
            return value;
        }
    }

    private static final class Picker {
        final String scope, form, token;
        final boolean dom;
        Picker(String scope, boolean dom, String form, String token) {
            this.scope = scope; this.dom = dom; this.form = form; this.token = token;
        }
        boolean matches(UiState state) {
            return scope.equals(state.scope) && dom == state.dom && form.equals(state.form) && token.equals(state.formToken);
        }
    }

    /** Retains accepted writes and drafts across rotation; worker closures only hold app data. */
    public static final class UiState extends ViewModel {
        private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "subhub-task-ui"));
        private final Handler main = new Handler(Looper.getMainLooper());
        private volatile boolean closed;
        private Runnable changed;
        private Snapshot snapshot;
        private Exception failure;
        private String person, scope = "", form = "", formToken = "", selected = "";
        private boolean dom, busy, history, loading;
        private int message;
        private long readSequence;
        private Bundle draft = new Bundle();
        private Picker picker;
        private Context app;

        public UiState() { }
        private void notifyUi() { if (!closed && changed != null) changed.run(); }
        private void openForm(String value) {
            form = value; formToken = UUID.randomUUID().toString(); draft = new Bundle();
            failure = null; message = 0;
        }
        private void closeForm() { form = ""; formToken = ""; draft = new Bundle(); picker = null; }
        private void bind(Snapshot next) {
            if (!scope.equals(next.scope) || dom != next.dom) {
                if (!form.isEmpty()) message = R.string.tasks_changed;
                closeForm();
                selected = "";
            }
            scope = next.scope; dom = next.dom; snapshot = next;
        }
        private void refresh(TaskRepository repo) {
            // The repository stores only the application context.
            if (app == null) throw new IllegalStateException("Missing task context");
            long sequence = ++readSequence;
            loading = true;
            failure = null;
            message = 0;
            Context context = app;
            worker.execute(() -> {
                Snapshot next = null; Exception error = null;
                try { next = Snapshot.read(repo, context); } catch (Exception problem) { error = problem; }
                Snapshot result = next; Exception problem = error;
                main.post(() -> {
                    if (closed || sequence != readSequence) return;
                    loading = false;
                    if (problem == null) bind(result);
                    else { snapshot = new Snapshot(); snapshot.valid = false; snapshot.dom = ControllerPinManager.isDomModeActive(); failure = problem; closeForm(); }
                    notifyUi();
                });
            });
        }
        private <T> void run(TaskRepository repo, String expectedScope, boolean expectedDom,
                Work<T> work, Result<T> success, boolean importing) {
            if (busy) return;
            busy = true; failure = null; message = 0;
            ++readSequence;
            Context context = app;
            notifyUi();
            worker.execute(() -> {
                T value = null; Snapshot next = null; Exception error = null;
                try {
                    verify(repo, expectedScope, expectedDom);
                    value = work.call();
                    verify(repo, expectedScope, expectedDom);
                } catch (Exception problem) { error = problem; }
                try { next = Snapshot.read(repo, context); }
                catch (Exception problem) { if (error == null) error = problem; }
                T result = value; Snapshot refreshed = next; Exception problem = error;
                main.post(() -> {
                    if (closed) return;
                    busy = false; loading = false;
                    if (refreshed != null) bind(refreshed);
                    else { snapshot = new Snapshot(); snapshot.valid = false; snapshot.dom = ControllerPinManager.isDomModeActive(); closeForm(); }
                    if (problem == null) success.accept(result);
                    else if (importing && !(problem instanceof SecurityException)) message = R.string.tasks_import_error;
                    else failure = problem;
                    notifyUi();
                });
            });
        }
        private void restore(String target, Bundle state) {
            person = target;
            if (state == null || !target.equals(state.getString("tasks.person"))) return;
            scope = state.getString("tasks.scope", ""); dom = state.getBoolean("tasks.dom");
            form = state.getString("tasks.form", ""); formToken = state.getString("tasks.token", "");
            history = state.getBoolean("tasks.history");
            selected = state.getString("tasks.selected", "");
            Bundle saved = state.getBundle("tasks.draft"); if (saved != null) draft = saved;
            if (state.getBoolean("tasks.picker")) picker = new Picker(scope, dom, form, formToken);
        }
        private void save(Bundle state) {
            state.putString("tasks.person", person); state.putString("tasks.scope", scope); state.putBoolean("tasks.dom", dom);
            state.putString("tasks.form", form); state.putString("tasks.token", formToken); state.putBundle("tasks.draft", draft);
            state.putBoolean("tasks.history", history); state.putBoolean("tasks.picker", picker != null);
            state.putString("tasks.selected", selected);
        }
        @Override protected void onCleared() {
            closed = true; changed = null; worker.shutdown(); main.removeCallbacksAndMessages(null);
        }
    }
}
