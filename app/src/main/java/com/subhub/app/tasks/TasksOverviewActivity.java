package com.subhub.app.tasks;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.people.*;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.util.SubHubNavigation;
import java.util.ArrayList;
import java.util.List;

/** The Dom's primary Tasks destination; each card retains its own relationship identity. */
public final class TasksOverviewActivity extends RolePage {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.string.tasks_title, SubHubNavigation.Screen.TASKS, true);
    }

    private boolean allowed() {
        return ControllerPinManager.isDomModeActive() && DeviceRolePreferences.supportsDom(this);
    }

    @Override protected void refresh() {
        if (!allowed()) { body.removeAllViews(); return; }
        loading();
        android.content.Context app = getApplicationContext();
        PersonRepository people = People.repository(app);
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        data.load("tasks-overview", () -> {
            List<PersonSnapshot> targets = new ArrayList<>();
            for (PersonId id : people.people()) {
                if (!ControllerPinManager.isDomModeActive()) throw new SecurityException("Dom access required");
                targets.add(people.load(id));
            }
            List<Summary> result = new ArrayList<>();
            for (PersonSnapshot person : PersonDisplayOrder.sorted(targets, locale)) {
                Summary summary = new Summary(person);
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
            return result;
        }, summaries -> {
            if (!allowed()) { body.removeAllViews(); return; }
            body.removeAllViews();
            if (summaries.isEmpty()) {
                TasksUi.empty(body, R.string.tasks_overview_empty);
                PersonUi.action(body, R.string.role_add_sub, () -> {
                    if (!allowed()) return;
                    ConnectionUi ui = People.connectionUi();
                    if (ui != null) ui.openPairing(this, true);
                    else startActivity(new Intent(this, PeopleActivity.class));
                }, true);
            }
            for (Summary summary : summaries) {
                LinearLayout card = PersonUi.card(body);
                PersonUi.row(card, R.drawable.ic_nav_tasks, summary.person.name,
                        ProfileViews.targetDiscriminator(this, summary.person), () -> open(summary.person.id));
                if (summary.failed) PersonUi.text(card, getString(R.string.tasks_profile_unavailable), false);
                else if (!summary.supported) PersonUi.text(card, getString(R.string.tasks_update_devices), false);
                else if (!summary.allowed) PersonUi.text(card, getString(R.string.tasks_disabled), false);
                else TasksUi.counts(card, summary.active, summary.pending, summary.scheduled);
                PersonUi.action(card, R.string.tasks_view_all, () -> open(summary.person.id), false)
                        .setTag("tasks_profile:" + summary.person.id.value());
            }
            PersonUi.action(body, R.string.tasks_refresh, this::refresh, false).setTag("tasks_overview_refresh");
        }, failure -> { if (allowed()) unavailable(); });
    }

    private void open(PersonId id) {
        if (allowed()) startActivity(new Intent(this, TasksActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, id.value()));
    }

    @Override protected void onPause() {
        if (body != null) body.removeAllViews();
        if (data != null) data.cancel("tasks-overview");
        super.onPause();
    }

    private static final class Summary {
        final PersonSnapshot person;
        int active, pending, scheduled;
        boolean supported, allowed, failed;
        Summary(PersonSnapshot person) { this.person = person; }
    }
}
