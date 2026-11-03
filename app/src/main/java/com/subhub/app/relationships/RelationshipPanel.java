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
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
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
    private final boolean pageRetry;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RelationshipPresentation model;
    private String renderedContext;
    private KeyholderProvider authorityProvider;
    private RelationshipUi.Provider presentationProvider;
    private long contextEpoch;
    private long rosterChangesSeen;
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
        pageRetry = retry != null;
        this.retry = retry == null ? this::refresh : retry;
        setOrientation(VERTICAL); setTag("relationship_panel"); setVisibility(GONE);
        model = new RelationshipPresentation(RelationshipUi::provider, journal(activity),
                main::post, this::currentContext, this::mayLink, ignored -> presentationChanged());
        activity.getLifecycle().addObserver(this);
    }

    private void presentationChanged() {
        render();
        if (pageRetry && model.mode() == RelationshipPresentation.Mode.READY && !model.busy() && rosterChangesSeen != model.rosterChanges()) {
            rosterChangesSeen = model.rosterChanges();
            String expectedContext = currentContext(); long expectedEpoch = model.reviewEpoch();
            main.post(() -> {
                if (expectedContext != null && expectedContext.equals(currentContext()) && model.reviewEpoch() == expectedEpoch)
                    retry.run();
            });
        }
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
        KeyholderProvider authority = People.keyholder();
        RelationshipUi.Provider provider = RelationshipUi.provider();
        if (authorityProvider != authority || presentationProvider != provider) {
            authorityProvider = authority; presentationProvider = provider; ++contextEpoch;
        }
        return selectionKey + ":" + DeviceRolePreferences.get(activity).name()
                + ":" + ControllerPinManager.isDomWorkspaceUnlocked() + ":" + ControllerPinManager.domWorkspaceRevision() + ":" + contextEpoch;
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
        localUnlink(snapshot);
        RelationshipPresentation.Saved saved = model.saved();
        RelationshipUi.LocalReview review = model.review();
        RelationshipUi.Operation operation = model.operation();
        RelationshipRecoveryUi.OperationSummary selected = model.summary();
        if (model.unlinkUnavailable() && (saved != null || operation == null))
            action(R.string.relationships_refresh, "relationship_unlink_refresh", retry, !model.busy());
        if (review != null) {
            text(R.string.relationships_review_title, "relationship_review_title");
            if (selected != null) summaryNames(selected, "");
            else {
                participantName(snapshot, review.domParticipantRef, RelationshipUi.Role.DOM);
                participantName(snapshot, review.subParticipantRef, RelationshipUi.Role.SUB);
            }
            TextView scope = text(review.scopeSummary, "relationship_scope");
            scope.setTextIsSelectable(true);
            long reviewEpoch = model.reviewEpoch();
            action(R.string.relationships_link, "relationship_confirm", () -> {
                if (!mayLink() || model.review() != review || model.busy()) return;
                ControllerPinGate.authenticate(activity, () -> model.confirm(review, reviewEpoch), false);
            }, model.canConfirm());
            action(android.R.string.cancel, "relationship_cancel", () -> model.cancel(saved), !model.busy());
            return;
        }
        if (saved != null || operation != null) {
            if (selected != null) summaryNames(selected, "");
            if (model.busy()) text(R.string.role_loading, "relationship_operation_status");
            else if (model.recoveryState() == RelationshipPresentation.RecoveryState.UNKNOWN)
                text("Couldn’t verify this link request.", "relationship_operation_status");
            else if (model.recoveryState() == RelationshipPresentation.RecoveryState.ABSENT_NEVER_CREATED)
                text("No link was prepared.", "relationship_operation_status");
            else if (model.recoveryState() == RelationshipPresentation.RecoveryState.REFUSED)
                text(R.string.relationships_refused, "relationship_operation_status");
            else {
                int status = operation == null ? saved != null && saved.unresolved() && !model.recoveryAware()
                        ? R.string.relationships_preparing : R.string.relationships_unavailable : status(operation.state);
                text(status, "relationship_operation_status");
            }
            if (saved != null && saved.unresolved() && model.recoveryAware()) {
                action(R.string.relationships_resume, "relationship_resume", () -> model.resume(saved), !model.busy());
                if (model.canRetryPreparation())
                    action(R.string.relationships_retry, "relationship_retry_prepare", model::prepare, true);
            } else if (saved != null && saved.unresolved()) {
                action(R.string.relationships_retry, "relationship_retry_prepare", model::prepare,
                        model.canPrepare() && saved.domRef.equals(model.domChoice())
                                && saved.subRef.equals(model.subChoice()));
            } else if (saved != null) {
                action(R.string.relationships_resume, "relationship_resume", () -> model.resume(saved), !model.busy());
                action(android.R.string.cancel, "relationship_cancel", () -> model.cancel(saved), !model.busy());
            } else action(R.string.relationships_refresh, "relationship_refresh", retry, true);
            return;
        }
        if (model.recoveryAware()) {
            List<RelationshipRecoveryUi.OperationSummary> pending = new ArrayList<>();
            for (RelationshipRecoveryUi.OperationSummary value : model.summaries())
                if (value.operation.state == RelationshipUi.OperationState.PREPARED
                        || value.operation.state == RelationshipUi.OperationState.PENDING) pending.add(value);
            if (!pending.isEmpty()) {
                for (RelationshipRecoveryUi.OperationSummary value : pending) {
                    String suffix = pending.size() == 1 ? "" : ":" + value.operation.operationId;
                    summaryNames(value, suffix);
                    text(status(value.operation.state), "relationship_operation_status" + suffix);
                    action(R.string.relationships_resume, "relationship_resume_summary" + suffix,
                            () -> model.resumeSummary(value), !model.busy());
                }
                return;
            }
        }
        List<RelationshipUi.Operation> pending = new ArrayList<>();
        for (RelationshipUi.Operation summary : snapshot.operations)
            if (summary.state == RelationshipUi.OperationState.PREPARED
                    || summary.state == RelationshipUi.OperationState.PENDING) pending.add(summary);
        if (!pending.isEmpty()) {
            for (RelationshipUi.Operation summary : pending) {
                String suffix = pending.size() == 1 ? "" : ":" + summary.operationId;
                if (pending.size() > 1) operationExpiry(summary, suffix);
                text(status(summary.state), "relationship_operation_status" + suffix);
                action(R.string.relationships_resume, "relationship_resume_summary" + suffix,
                        () -> model.resumeSummary(summary), !model.busy());
            }
            return;
        }
        if (snapshot.governingDom != null) return;
        List<RelationshipUi.Participant> doms = participants(snapshot, RelationshipUi.Role.DOM);
        List<RelationshipUi.Participant> subs = participants(snapshot, RelationshipUi.Role.SUB);
        if (doms.isEmpty() || subs.isEmpty()) { setVisibility(GONE); return; }
        text(R.string.relationships_local_title, "relationship_local_title");
        select(doms, true); select(subs, false);
        if (!mayLink()) text(R.string.relationships_controller_required, "relationship_controller_required");
        action(R.string.relationships_review, "relationship_prepare", model::prepare, model.canPrepare());
    }

    private void localUnlink(RelationshipUi.Snapshot snapshot) {
        if (!model.recoveryAware()) return;
        List<RelationshipUi.Edge> local = new ArrayList<>();
        for (RelationshipUi.Edge edge : snapshot.eligibleSubs)
            if (edge.delivery == RelationshipUi.Delivery.LOCAL) local.add(edge);
        RelationshipUi.Edge governing = snapshot.governingDom;
        if (governing != null && governing.delivery == RelationshipUi.Delivery.LOCAL) {
            boolean listed = false;
            for (RelationshipUi.Edge edge : local)
                if (edge.relationshipId.equals(governing.relationshipId) && edge.generation.equals(governing.generation)) listed = true;
            if (!listed) local.add(governing);
        }
        for (RelationshipUi.Edge edge : local) {
            participantName(snapshot, edge.domParticipantRef, RelationshipUi.Role.DOM, "relationship_unlink_dom:" + edge.relationshipId);
            participantName(snapshot, edge.subParticipantRef, RelationshipUi.Role.SUB, "relationship_unlink_sub:" + edge.relationshipId);
            long expectedEpoch = model.reviewEpoch();
            action("Unlink", local.size() == 1 ? "relationship_unlink" : "relationship_unlink:" + edge.relationshipId + ":" + edge.generation, () -> {
                if (!model.canUnlink(edge)) return;
                ControllerPinGate.authenticate(activity, () -> model.unlink(edge, expectedEpoch), false);
            }, model.canUnlink(edge));
        }
        if (model.unlinkUnavailable()) text(R.string.relationships_unavailable, "relationship_unlink_status");
    }

    private void summaryNames(RelationshipRecoveryUi.OperationSummary summary, String suffix) {
        String kind = summary.kind == RelationshipRecoveryUi.OperationKind.LOCAL_LINK
                ? activity.getString(R.string.relationships_local_title)
                : summary.kind == RelationshipRecoveryUi.OperationKind.PEER_INVITE_CREATE
                ? activity.getString(R.string.remote_create_invite) : "Accept invitation";
        text(kind, "relationship_operation_kind" + suffix);
        if (!suffix.isEmpty()) operationExpiry(summary.operation, suffix);
        if (summary.domParticipantRef != null)
            text(activity.getString(R.string.relationships_participant, activity.getString(R.string.role_dom),
                    summary.domLabel == null ? "Unknown" : summary.domLabel), "relationship_dom_profile" + suffix);
        if (summary.subParticipantRef != null)
            text(activity.getString(R.string.relationships_participant, activity.getString(R.string.role_sub),
                    summary.subLabel == null ? "Unknown" : summary.subLabel), "relationship_sub_profile" + suffix);
    }

    private void operationExpiry(RelationshipUi.Operation operation, String suffix) {
        String deadline = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(operation.originalDeadline));
        text("Expires " + deadline, "relationship_operation_id" + suffix);
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
        participantName(snapshot, ref, role, role == RelationshipUi.Role.DOM ? "relationship_dom_profile" : "relationship_sub_profile");
    }
    private void participantName(RelationshipUi.Snapshot snapshot, String ref, RelationshipUi.Role role, String tag) {
        for (RelationshipUi.Participant participant : snapshot.localParticipants) {
            if (participant.role == role && participant.ref.equals(ref)) {
                text(activity.getString(R.string.relationships_participant,
                        activity.getString(role == RelationshipUi.Role.DOM ? R.string.role_dom : R.string.role_sub),
                        participant.profile.name), tag);
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
        action(activity.getString(label), tag, action, enabled);
    }
    private void action(String label, String tag, Runnable action, boolean enabled) {
        TextView button = PersonUi.action(this, android.R.string.ok, () -> { }, false);
        button.setText(label);
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
        DomSubjectScope scope = DomSubjectScope.from(context, access);
        List<PersonSnapshot> values = new ArrayList<>();
        List<PersonId> ids = access.legacy ? repository.people() : RelationshipPresentation.rosterIds(access.snapshot);
        for (PersonId id : ids) {
            if (!scope.includes(id)) continue;
            if (!ControllerPinManager.isDomWorkspaceUnlocked() || !DeviceRolePreferences.supportsDom(context)
                    || !access.current(RelationshipUi::provider)) throw new IllegalStateException("Relationship context changed");
            PersonSnapshot value = repository.load(id);
            if (!id.equals(value.id)) throw new IllegalStateException("Relationship projection differs");
            if (!access.legacy && !com.subhub.app.people.FavoriteSubStore.eligible(value))
                throw new IllegalStateException("Relationship projection is not eligible");
            values.add(value);
        }
        scope.revalidate();
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
