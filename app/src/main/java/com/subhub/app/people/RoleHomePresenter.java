package com.subhub.app.people;

import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.help.HelpActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.stats.StatsActivity;
import com.subhub.app.stats.StatsSnapshot;
import com.subhub.app.util.AsyncUiScope;
import java.util.ArrayList;
import java.util.List;

/** Separate home compositions over the existing service controls and person repository. */
public final class RoleHomePresenter {
    private final AppCompatActivity activity;
    private final AsyncUiScope data;
    private final LinearLayout domBody, subBody;
    private final View service, oldDashboard;
    private long lastRefresh;
    private boolean displayedDom;
    private boolean sessionPage;

    public RoleHomePresenter(AppCompatActivity activity, AsyncUiScope data) {
        this.activity = activity; this.data = data;
        domBody = activity.findViewById(R.id.home_dom_overview);
        subBody = activity.findViewById(R.id.home_sub_summary);
        service = activity.findViewById(R.id.home_service_widget);
        oldDashboard = activity.findViewById(R.id.sub_dashboard);
        View status = activity.findViewById(R.id.protection_status_row);
        ((android.view.ViewGroup) status.getParent()).removeView(status);
        ((LinearLayout) service).addView(status, 0, new LinearLayout.LayoutParams(-1, -2));
        status.setVisibility(View.VISIBLE);
        reparent(activity.findViewById(R.id.sub_wallet_pay));
        reparent(activity.findViewById(R.id.sub_wallet_pause));
    }

    private void reparent(View child) {
        ((android.view.ViewGroup) child.getParent()).removeView(child);
        ((LinearLayout) service).addView(child, new LinearLayout.LayoutParams(-1, -2));
    }

    public void setSessionPage(boolean sessionPage) { this.sessionPage = sessionPage; }

    public void refresh(boolean force) {
        boolean dom = ControllerPinManager.isDomModeActive();
        domBody.setVisibility(dom && !sessionPage ? View.VISIBLE : View.GONE);
        subBody.setVisibility(!dom && !sessionPage ? View.VISIBLE : View.GONE);
        service.setVisibility(!dom || sessionPage ? View.VISIBLE : View.GONE);
        oldDashboard.setVisibility(View.GONE);
        if (sessionPage) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && dom == displayedDom && now - lastRefresh < 5_000L) return;
        displayedDom = dom; lastRefresh = now;
        PersonRepository repository = People.repository(activity.getApplicationContext());
        java.util.Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
        data.load("role-home", () -> {
            List<PersonSnapshot> values = new ArrayList<>();
            if (dom) {
                for (PersonId id : repository.people()) values.add(repository.load(id));
            } else values.add(repository.load(PersonId.LOCAL));
            return PersonDisplayOrder.sorted(values, locale);
        }, values -> {
            if (dom != ControllerPinManager.isDomModeActive()) return;
            if (dom) renderDom(values);
            else if (!values.isEmpty()) renderSub(values.get(0));
        }, failure -> {
            if (dom != ControllerPinManager.isDomModeActive()) return;
            LinearLayout target = dom ? domBody : subBody;
            target.removeAllViews();
            PersonUi.text(target, activity.getString(R.string.role_person_unavailable), false);
            PersonUi.action(target, R.string.role_retry, () -> refresh(true), false);
        });
    }

    private void renderDom(List<PersonSnapshot> values) {
        domBody.removeAllViews();
        LinearLayout summary = PersonUi.card(domBody);
        PersonUi.text(summary, activity.getString(R.string.role_people), true);
        long active = values.stream().filter(value -> value.serviceActive
                && value.sync.runtime == PersonSnapshot.Freshness.CURRENT).count();
        boolean runtimeKnown = values.stream().allMatch(value -> value.sync.runtime == PersonSnapshot.Freshness.CURRENT);
        PersonUi.text(summary, activity.getString(R.string.role_person_count, values.size())
                + (runtimeKnown ? " · " + activity.getString(R.string.role_service_count, active) : ""), false);
        PersonUi.action(summary, R.string.role_view_people,
                () -> activity.startActivity(new Intent(activity, PeopleActivity.class)), true);
        for (PersonSnapshot person : values) {
            LinearLayout card = ProfileViews.person(domBody, person,
                    () -> PersonRoutes.open(activity, person.id));
            PersonUi.text(card, person.sync.runtime == PersonSnapshot.Freshness.CURRENT ? person.serviceLabel
                    : activity.getString(R.string.role_unknown_data), false);
            if (person.freshness != PersonSnapshot.Freshness.CURRENT) PersonUi.text(card,
                    activity.getString(person.freshness == PersonSnapshot.Freshness.STALE
                            ? R.string.role_stale_data : R.string.role_unknown_data), false);
            if (person.sync.stats != PersonSnapshot.Freshness.UNKNOWN) PersonUi.text(card,
                    StatsSnapshot.formatDuration(person.protectedSeconds)
                    + " · " + person.activityEvents + " " + activity.getString(R.string.role_stat_activity), false);
        }
    }

    private void renderSub(PersonSnapshot person) {
        subBody.removeAllViews();
        LinearLayout contract = PersonUi.card(subBody);
        PersonUi.row(contract, R.drawable.ic_nav_contract, person.contractName,
                activity.getString(R.string.role_active_contract),
                () -> activity.startActivity(new Intent(activity, ContractActivity.class)));
        PersonUi.allowances(subBody, person);
        LinearLayout stats = PersonUi.card(subBody);
        PersonUi.row(stats, R.drawable.ic_nav_stats, activity.getString(R.string.role_stats),
                StatsSnapshot.formatDuration(person.protectedSeconds) + " · " + person.activityEvents
                        + " " + activity.getString(R.string.role_stat_activity),
                () -> activity.startActivity(new Intent(activity, StatsActivity.class)));
        PersonUi.action(subBody, R.string.role_help_safety,
                () -> activity.startActivity(new Intent(activity, HelpActivity.class)), false);
    }
}
