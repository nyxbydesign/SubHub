package com.subhub.app.relationships;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.subhub.app.R;
import com.subhub.app.people.People;
import com.subhub.app.people.PersonDisplayOrder;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRepository;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.people.PersonUi;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** A local relationship review shares the surrounding screen's lifecycle and role context. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic binding requires an activity and current-choice guards.
public final class RelationshipPanel extends LinearLayout implements DefaultLifecycleObserver {
    private static final Object JOURNAL_LOCK = new Object();
    private static final Map<String, JournalState> JOURNAL_STATES = new HashMap<>();
    private final AppCompatActivity activity;
    private final String selectionKey;
    private final BooleanSupplier present, localActions;
    private final Runnable retry;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RelationshipPresentation model;
    private String renderedContext;
    private final Runnable checkContext = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow() || !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
            String current = currentContext();
            if (!Objects.equals(renderedContext, current)) {
                model.detach(); removeAllViews(); setVisibility(GONE);
                if (current != null) refresh();
            }
            main.postDelayed(this, 250);
        }
    };

    public RelationshipPanel(AppCompatActivity activity, String selectionKey,
            BooleanSupplier present, BooleanSupplier localActions) {
        this(activity, selectionKey, present, localActions, null);
    }

    public RelationshipPanel(AppCompatActivity activity, String selectionKey,
            BooleanSupplier present, BooleanSupplier localActions, Runnable retry) {
        super(activity);
        this.activity = activity; this.selectionKey = selectionKey;
        this.present = present; this.localActions = localActions;
        this.retry = retry == null ? this::refresh : retry;
        setOrientation(VERTICAL); setTag("relationship_panel"); setVisibility(GONE);
        model = new RelationshipPresentation(RelationshipUi::provider, journal(activity),
                main::post, this::currentContext, this::mayLink, ignored -> render());
        activity.getLifecycle().addObserver(this);
    }

    public void refresh() {
        if (currentContext() == null) return;
        renderedContext = currentContext(); model.refresh();
    }

    /** A page snapshot failure owns one retry for both its controls and its roster. */
    public void snapshotUnavailable() {
        if (currentContext() == null) return;
        model.detach(); removeAllViews(); setVisibility(VISIBLE);
        text(R.string.relationships_unavailable, "relationship_unavailable");
        action(R.string.relationships_retry, "relationship_refresh", retry, true);
    }

    private String currentContext() {
        if (!isAttachedToWindow() || activity.isFinishing() || activity.isDestroyed()
                || !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                || !present.getAsBoolean()) return null;
        return selectionKey + ":" + DeviceRolePreferences.get(activity).name()
                + ":" + ControllerPinManager.isDomModeActive();
    }

    private boolean mayLink() {
        try {
            return currentContext() != null && localActions.getAsBoolean()
                    && DeviceRolePreferences.supportsDom(activity) && DeviceRolePreferences.supportsSub(activity)
                    && ControllerPinManager.hasCredentials(activity) && ControllerPinManager.isDomModeActive()
                    && People.keyholder().state() == KeyholderProvider.State.NONE
                    && People.isLocalConfigurationAllowed();
        } catch (RuntimeException unavailable) { return false; }
    }

    private void render() {
        removeAllViews();
        if (currentContext() == null || model.mode() == RelationshipPresentation.Mode.LEGACY) {
            setVisibility(GONE); return;
        }
        setVisibility(VISIBLE);
        if (model.mode() == RelationshipPresentation.Mode.LOADING) {
            text(R.string.role_loading, "relationship_loading"); return;
        }
        if (model.mode() == RelationshipPresentation.Mode.UNAVAILABLE) {
            text(R.string.relationships_unavailable, "relationship_unavailable");
            action(R.string.relationships_retry, "relationship_refresh", retry, true); return;
        }
        RelationshipUi.Snapshot snapshot = model.snapshot();
        RelationshipPresentation.Saved saved = model.saved();
        RelationshipUi.LocalReview review = model.review();
        RelationshipUi.Operation operation = model.operation();
        if (review != null) {
            text(R.string.relationships_review_title, "relationship_review_title");
            participantName(snapshot, review.domParticipantRef, RelationshipUi.Role.DOM);
            participantName(snapshot, review.subParticipantRef, RelationshipUi.Role.SUB);
            TextView scope = text(review.scopeSummary, "relationship_scope");
            scope.setTextIsSelectable(true);
            action(R.string.relationships_link, "relationship_confirm", () -> {
                if (!mayLink() || model.review() != review || model.busy()) return;
                ControllerPinGate.authenticate(activity, () -> model.confirm(review), false);
            }, model.canPrepare());
            action(android.R.string.cancel, "relationship_cancel", () -> model.cancel(saved), !model.busy());
            return;
        }
        if (saved != null || operation != null) {
            int status = operation == null ? saved != null && saved.unresolved()
                    ? R.string.relationships_preparing : R.string.relationships_unavailable
                    : status(operation.state);
            text(status, "relationship_operation_status");
            if (saved != null && saved.unresolved()) {
                action(R.string.relationships_retry, "relationship_retry_prepare", model::prepare,
                        model.canPrepare() && saved.domRef.equals(model.domChoice())
                                && saved.subRef.equals(model.subChoice()));
            } else if (saved != null) {
                action(R.string.relationships_resume, "relationship_resume", () -> model.resume(saved), !model.busy());
                action(android.R.string.cancel, "relationship_cancel", () -> model.cancel(saved), !model.busy());
            } else action(R.string.relationships_refresh, "relationship_refresh", retry, true);
            return;
        }
        List<RelationshipUi.Operation> pending = new ArrayList<>();
        for (RelationshipUi.Operation summary : snapshot.operations)
            if (summary.state == RelationshipUi.OperationState.PREPARED
                    || summary.state == RelationshipUi.OperationState.PENDING) pending.add(summary);
        if (!pending.isEmpty()) {
            if (pending.size() == 1) {
                RelationshipUi.Operation summary = pending.get(0);
                text(status(summary.state), "relationship_operation_status");
                action(R.string.relationships_resume, "relationship_resume_summary",
                        () -> model.resumeSummary(summary), !model.busy());
            } else {
                text(R.string.relationships_unavailable, "relationship_unavailable");
                action(R.string.relationships_retry, "relationship_refresh", retry, true);
            }
            return;
        }
        if (snapshot.governingDom != null) {
            text(R.string.relationships_governing_dom, "relationship_governing_dom"); return;
        }
        List<RelationshipUi.Participant> doms = participants(snapshot, RelationshipUi.Role.DOM);
        List<RelationshipUi.Participant> subs = participants(snapshot, RelationshipUi.Role.SUB);
        if (doms.isEmpty() || subs.isEmpty()) { setVisibility(GONE); return; }
        text(R.string.relationships_local_title, "relationship_local_title");
        select(doms, true); select(subs, false);
        if (!mayLink()) text(R.string.relationships_controller_required, "relationship_controller_required");
        action(R.string.relationships_review, "relationship_prepare", model::prepare, model.canPrepare());
    }

    private void select(List<RelationshipUi.Participant> values, boolean dom) {
        if (values.size() == 1) {
            participantName(model.snapshot(), values.get(0).ref, dom ? RelationshipUi.Role.DOM : RelationshipUi.Role.SUB);
            return;
        }
        text(dom ? R.string.role_dom : R.string.role_sub, dom ? "relationship_dom_label" : "relationship_sub_label");
        List<String> names = new ArrayList<>();
        int selected = 0;
        for (int index = 0; index < values.size(); index++) {
            RelationshipUi.Participant value = values.get(index); names.add(value.profile.name);
            if (value.ref.equals(dom ? model.domChoice() : model.subChoice())) selected = index;
        }
        Spinner spinner = new Spinner(activity);
        RelationshipUi.Snapshot choicesSnapshot = model.snapshot();
        spinner.setTag(dom ? "relationship_dom_choice" : "relationship_sub_choice");
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, names) {
            private View passive(View value) { value.setFocusable(false); value.setClickable(false); return value; }
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                return passive(super.getView(position, convertView, parent));
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                return passive(super.getDropDownView(position, convertView, parent));
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setSelection(selected); spinner.setEnabled(!model.busy());
        addView(spinner, new LayoutParams(-1, -2));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= values.size() || currentContext() == null
                        || !spinner.isAttachedToWindow() || spinner.getParent() != RelationshipPanel.this
                        || model.snapshot() != choicesSnapshot) return;
                model.choose(dom ? values.get(position).ref : model.domChoice(),
                        dom ? model.subChoice() : values.get(position).ref);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
    }

    private void participantName(RelationshipUi.Snapshot snapshot, String ref, RelationshipUi.Role role) {
        for (RelationshipUi.Participant participant : snapshot.localParticipants) {
            if (participant.role == role && participant.ref.equals(ref)) {
                text(activity.getString(R.string.relationships_participant,
                        activity.getString(role == RelationshipUi.Role.DOM ? R.string.role_dom : R.string.role_sub),
                        participant.profile.name), role == RelationshipUi.Role.DOM
                                ? "relationship_dom_profile" : "relationship_sub_profile");
                return;
            }
        }
    }

    private static List<RelationshipUi.Participant> participants(RelationshipUi.Snapshot snapshot, RelationshipUi.Role role) {
        List<RelationshipUi.Participant> result = new ArrayList<>();
        for (RelationshipUi.Participant value : snapshot.localParticipants) if (value.role == role) result.add(value);
        return result;
    }

    private TextView text(int label, String tag) { return text(activity.getString(label), tag); }
    private TextView text(String value, String tag) {
        TextView text = PersonUi.text(this, value, false); text.setTag(tag); return text;
    }
    private void action(int label, String tag, Runnable action, boolean enabled) {
        TextView button = PersonUi.action(this, label, () -> { }, false);
        button.setOnClickListener(view -> {
            if (view.isAttachedToWindow() && view.isShown() && view.isEnabled()
                    && currentContext() != null) action.run();
        });
        button.setTag(tag); button.setEnabled(enabled);
    }
    private static int status(RelationshipUi.OperationState state) {
        switch (state) {
            case PREPARED: return R.string.relationships_prepared;
            case PENDING: return R.string.relationships_pending;
            case ACTIVE: return R.string.relationships_linked;
            case CANCELLED: return R.string.relationships_cancelled;
            case EXPIRED: return R.string.relationships_expired;
            case REFUSED: return R.string.relationships_refused;
            default: return R.string.relationships_unavailable;
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        main.removeCallbacks(checkContext); main.post(checkContext); refresh();
    }
    @Override protected void onDetachedFromWindow() {
        model.detach(); main.removeCallbacks(checkContext); super.onDetachedFromWindow();
    }
    @Override public void onResume(LifecycleOwner owner) {
        refresh(); main.removeCallbacks(checkContext); main.post(checkContext);
    }
    @Override public void onPause(LifecycleOwner owner) {
        model.detach(); removeAllViews(); setVisibility(GONE); main.removeCallbacks(checkContext);
    }
    @Override public void onDestroy(LifecycleOwner owner) {
        model.detach(); main.removeCallbacks(checkContext); activity.getLifecycle().removeObserver(this);
    }

    public static RelationshipPresentation.Journal journal(Context context) {
        return new PreferencesJournal(context.getApplicationContext());
    }

    /** Runs on a page worker; timeout is an observation failure, never cancellation of the operation. */
    public static RelationshipPresentation.Access readAccess(Context context) throws Exception {
        RelationshipPresentation.Access access = RelationshipPresentation.access(RelationshipUi.provider(), journal(context))
                .get(5, TimeUnit.SECONDS);
        if (!access.current(RelationshipUi::provider)) throw new IllegalStateException("Relationship provider changed");
        return access;
    }

    public static List<PersonSnapshot> roster(Context context, PersonRepository repository,
            RelationshipPresentation.Access access, java.util.Locale locale) throws Exception {
        List<PersonSnapshot> values = new ArrayList<>();
        List<PersonId> ids = access.legacy ? repository.people() : RelationshipPresentation.rosterIds(access.snapshot);
        for (PersonId id : ids) {
            if (!ControllerPinManager.isDomModeActive() || !DeviceRolePreferences.supportsDom(context)
                    || !access.current(RelationshipUi::provider)) throw new IllegalStateException("Relationship context changed");
            PersonSnapshot value = repository.load(id);
            if (!id.equals(value.id)) throw new IllegalStateException("Relationship projection differs");
            if (!access.legacy && !com.subhub.app.people.FavoriteSubStore.eligible(value))
                throw new IllegalStateException("Relationship projection is not eligible");
            values.add(value);
        }
        return access.legacy ? PersonDisplayOrder.sorted(values, locale) : values;
    }

    /** This file has one owner; failed commits must not replace its original reference with memory-only data. */
    private static final class JournalState {
        RelationshipPresentation.Saved record;
        boolean seen, corrupt;
    }

    private static final class PreferencesJournal implements RelationshipPresentation.Journal {
        private final SharedPreferences preferences;
        private final JournalState state;
        PreferencesJournal(Context context) {
            preferences = context.getSharedPreferences("subhub_relationship_presentation", Context.MODE_PRIVATE);
            String key = Objects.requireNonNull(context.getApplicationInfo().dataDir) + "/subhub_relationship_presentation";
            synchronized (JOURNAL_LOCK) {
                JournalState retained = JOURNAL_STATES.get(key);
                if (retained == null) {
                    retained = new JournalState();
                    try {
                        retained.seen = preferences.getBoolean("formal_seen", false);
                        retained.record = decode(preferences.getString("operation", null));
                    } catch (Exception invalid) { retained.corrupt = true; }
                    JOURNAL_STATES.put(key, retained);
                }
                state = retained;
            }
        }
        @Override public boolean authoritativeSeen() {
            synchronized (JOURNAL_LOCK) { return state.seen || state.corrupt; }
        }
        @Override public boolean markAuthoritativeSeen() {
            synchronized (JOURNAL_LOCK) {
                state.seen = true;
                // Re-acknowledge the original record as well as the latch before every real-provider read.
                return persist(state.record);
            }
        }
        @Override public RelationshipPresentation.Saved read() {
            synchronized (JOURNAL_LOCK) { return state.record; }
        }
        @Override public boolean replace(RelationshipPresentation.Saved expected, RelationshipPresentation.Saved value) {
            synchronized (JOURNAL_LOCK) {
                if (state.corrupt || !Objects.equals(expected, state.record)) return false;
                boolean saved = persist(value);
                // Reserve an initial key/reference even when persistence is uncertain. Reuse it until acknowledged.
                if (saved || state.record == null && value != null) state.record = value;
                return saved;
            }
        }
        private boolean persist(RelationshipPresentation.Saved value) {
            if (state.corrupt) return false;
            try {
                SharedPreferences.Editor edit = preferences.edit().putBoolean("formal_seen", state.seen);
                if (value == null) edit.remove("operation");
                else {
                    JSONObject data = new JSONObject();
                    if (value.unresolved()) data.put("key", value.operationKey).put("dom", value.domRef)
                            .put("sub", value.subRef).put("revision", value.revision);
                    else data.put("id", value.operationId).put("digest", value.digest);
                    edit.putString("operation", data.toString());
                }
                // A fresh scalar forces a disk barrier after a memory-advanced, unacknowledged commit.
                return edit.putString("write_witness", UUID.randomUUID().toString()).commit();
            } catch (Exception invalid) { return false; }
        }
        private static RelationshipPresentation.Saved decode(String encoded) throws Exception {
            if (encoded == null) return null;
            JSONObject data = new JSONObject(encoded);
            if (data.length() == 2 && data.has("id") && data.has("digest"))
                return RelationshipPresentation.Saved.operation(string(data, "id"), string(data, "digest"));
            if (data.length() == 4 && data.has("key") && data.has("dom") && data.has("sub") && data.has("revision"))
                return RelationshipPresentation.Saved.preparing(string(data, "key"), string(data, "dom"),
                        string(data, "sub"), string(data, "revision"));
            throw new IllegalArgumentException("Invalid operation reference");
        }
        private static String string(JSONObject data, String name) throws Exception {
            Object value = data.get(name);
            if (!(value instanceof String)) throw new IllegalArgumentException("Invalid operation reference field");
            return (String) value;
        }
    }
}
