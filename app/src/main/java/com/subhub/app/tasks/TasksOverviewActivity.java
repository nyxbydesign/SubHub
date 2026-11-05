package com.subhub.app.tasks;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.people.*;
import com.subhub.app.relationships.RelationshipPanel;
import com.subhub.app.relationships.RelationshipPresentation;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.util.SubHubNavigation;
import java.util.ArrayList;
import java.util.List;

/** The Dom's primary Tasks destination; each card retains its own relationship identity. */
public final class TasksOverviewActivity extends RolePage {
    private long rosterEpoch;
    private TaskStore.Observation taskObservation;
    private final android.os.Handler clock = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            clock.postDelayed(this, 30_000);
        }
    };

    @Override protected void onResume() {
        taskObservation = TaskRuntime.get(this).store.observe(this::refresh);
        super.onResume();
        clock.postDelayed(tick, 30_000);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.string.tasks_title, SubHubNavigation.Screen.TASKS, true);
    }

    private boolean allowed() {
        return ControllerPinManager.isDomWorkspaceUnlocked() && DeviceRolePreferences.supportsDom(this);
    }

    @Override protected void refresh() {
        long epoch = ++rosterEpoch;
        data.cancel("tasks-overview-open");
        if (!allowed()) { body.removeAllViews(); return; }
        loading();
        android.content.Context app = getApplicationContext();
        PersonRepository people = People.repository(app);
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        long domRevision = ControllerPinManager.domWorkspaceRevision();
        data.load("tasks-overview", () -> {
            RelationshipPresentation.Access access = RelationshipPanel.readAccess(app);
            List<PersonSnapshot> targets = RelationshipPanel.roster(app, people, access, locale);
            List<Summary> result = new ArrayList<>();
            for (PersonSnapshot person : targets) {
                if (!ControllerPinManager.isDomWorkspaceUnlocked() || !DeviceRolePreferences.supportsDom(app)
                        || domRevision != ControllerPinManager.domWorkspaceRevision()
                        || !access.current(RelationshipUi::provider))
                    throw new IllegalStateException("Tasks context changed");
                Summary summary = new Summary(person, edge(access, person.id));
                try {
                    TaskRepository tasks = new TaskRepository(app, person.id);
                    summary.supported = tasks.supported();
                    summary.allowed = tasks.allowed();
                    if (summary.supported && summary.allowed) for (TaskEngine.Occurrence task : tasks.list(System.currentTimeMillis())) {
                        if (task.status == TaskEngine.Status.ACTIVE) summary.active++;
                        else if (task.status == TaskEngine.Status.PENDING) summary.pending++;
                        else if (task.status == TaskEngine.Status.SCHEDULED) summary.scheduled++;
                    }
                } catch (Exception unavailable) { summary.failed = true; }
                result.add(summary);
            }
            // Counts may outlive an unlink/relink under the same provider. Reobserve after all Store work.
            RelationshipPresentation.Access fresh = RelationshipPanel.readAccess(app);
            if (fresh.provider != access.provider || fresh.legacy != access.legacy)
                throw new IllegalStateException("Tasks roster changed");
            List<PersonId> currentIds = new ArrayList<>();
            if (fresh.legacy) {
                for (PersonSnapshot person : RelationshipPanel.roster(app, people, fresh, locale)) currentIds.add(person.id);
            } else currentIds.addAll(RelationshipPresentation.rosterIds(fresh.snapshot));
            if (currentIds.size() != result.size()) throw new IllegalStateException("Tasks roster changed");
            for (int index = 0; index < result.size(); index++) {
                Summary summary = result.get(index);
                if (!summary.person.id.equals(currentIds.get(index))
                        || !sameEdge(summary.edge, edge(fresh, summary.person.id)))
                    throw new IllegalStateException("Tasks relationship changed");
            }
            if (!ControllerPinManager.isDomWorkspaceUnlocked() || !DeviceRolePreferences.supportsDom(app)
                    || domRevision != ControllerPinManager.domWorkspaceRevision()
                    || !fresh.current(RelationshipUi::provider))
                throw new IllegalStateException("Tasks context changed");
            return new Roster(fresh, result, epoch, domRevision);
        }, loaded -> {
            if (!current(loaded)) return;
            body.removeAllViews();
            if (loaded.summaries.isEmpty()) {
                TasksUi.empty(body, R.string.tasks_overview_empty);
                PersonUi.action(body, R.string.role_add_sub, () -> {
                    if (!current(loaded)) return;
                    ConnectionUi ui = People.connectionUi();
                    if (ui != null) ui.openPairing(this, true);
                    else startActivity(new Intent(this, PeopleActivity.class));
                }, true);
            }
            for (Summary summary : loaded.summaries) {
                LinearLayout card = PersonUi.card(body);
                PersonUi.row(card, R.drawable.ic_nav_tasks, summary.person.name,
                        ProfileViews.targetDiscriminator(this, summary.person), () -> open(summary, loaded))
                        .setTag("tasks_profile_row:" + summary.person.id.value());
                if (summary.failed) PersonUi.text(card, getString(R.string.tasks_profile_unavailable), false);
                else if (!summary.supported) PersonUi.text(card, getString(R.string.tasks_update_devices), false);
                else if (!summary.allowed) PersonUi.text(card, getString(R.string.tasks_disabled), false);
                else TasksUi.counts(card, summary.active, summary.pending, summary.scheduled);
                PersonUi.action(card, R.string.tasks_view_all, () -> open(summary, loaded), false)
                        .setTag("tasks_profile:" + summary.person.id.value());
            }
        }, failure -> { if (allowed()) unavailable(); else body.removeAllViews(); });
    }

    private boolean current(Roster loaded) {
        if (loaded.epoch != rosterEpoch) return false;
        if (!allowed()) { body.removeAllViews(); return false; }
        if (loaded.domRevision != ControllerPinManager.domWorkspaceRevision()
                || !loaded.access.current(RelationshipUi::provider)) { unavailable(); return false; }
        return true;
    }

    private void open(Summary selected, Roster loaded) {
        if (!current(loaded)) return;
        android.content.Context app = getApplicationContext();
        PersonRepository people = People.repository(app);
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        data.load("tasks-overview-open", () -> {
            if (!ControllerPinManager.isDomWorkspaceUnlocked() || !DeviceRolePreferences.supportsDom(app)
                    || loaded.domRevision != ControllerPinManager.domWorkspaceRevision()
                    || !loaded.access.current(RelationshipUi::provider))
                throw new IllegalStateException("Tasks context changed");
            RelationshipPresentation.Access fresh = RelationshipPanel.readAccess(app);
            if (fresh.provider != loaded.access.provider || fresh.legacy != loaded.access.legacy)
                throw new IllegalStateException("Tasks provider changed");
            boolean eligible = false;
            for (PersonSnapshot person : RelationshipPanel.roster(app, people, fresh, locale))
                if (person.id.equals(selected.person.id)) eligible = true;
            // LOCAL remains a valid direct Sub target, so this outgoing route must recheck its exact edge.
            if (!eligible || !sameEdge(selected.edge, edge(fresh, selected.person.id)))
                throw new IllegalStateException("Tasks relationship changed");
            return fresh;
        }, fresh -> {
            if (current(loaded) && fresh.current(RelationshipUi::provider))
                startActivity(new Intent(this, TasksActivity.class)
                        .putExtra(PersonRoutes.EXTRA_PERSON_ID, selected.person.id.value()));
        }, failure -> {
            if (loaded.epoch != rosterEpoch) return;
            if (allowed()) unavailable(); else body.removeAllViews();
        });
    }

    private static RelationshipUi.Edge edge(RelationshipPresentation.Access access, PersonId id) {
        if (access.legacy) return null;
        RelationshipUi.Edge found = null;
        for (RelationshipUi.Edge value : access.snapshot.eligibleSubs) if (value.personId.equals(id)) {
            if (found != null) throw new IllegalStateException("Ambiguous Tasks relationship");
            found = value;
        }
        return found;
    }

    private static boolean sameEdge(RelationshipUi.Edge first, RelationshipUi.Edge second) {
        if (first == null || second == null) return first == second;
        return first.relationshipId.equals(second.relationshipId) && first.generation.equals(second.generation)
                && first.personId.equals(second.personId) && first.domParticipantRef.equals(second.domParticipantRef)
                && first.subParticipantRef.equals(second.subParticipantRef) && first.delivery == second.delivery;
    }

    private static final class Roster {
        final RelationshipPresentation.Access access;
        final List<Summary> summaries;
        final long epoch, domRevision;
        Roster(RelationshipPresentation.Access access, List<Summary> summaries, long epoch, long domRevision) {
            this.access = access; this.summaries = summaries;
            this.epoch = epoch; this.domRevision = domRevision;
        }
    }

    @Override protected void onPause() {
        if (taskObservation != null) { taskObservation.close(); taskObservation = null; }
        clock.removeCallbacks(tick);
        ++rosterEpoch;
        if (body != null) body.removeAllViews();
        if (data != null) {
            data.cancel("tasks-overview");
            data.cancel("tasks-overview-open");
        }
        super.onPause();
    }

    private static final class Summary {
        final PersonSnapshot person;
        final RelationshipUi.Edge edge;
        int active, pending, scheduled;
        boolean supported, allowed, failed;
        Summary(PersonSnapshot person, RelationshipUi.Edge edge) { this.person = person; this.edge = edge; }
    }
}
