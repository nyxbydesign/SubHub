package com.subhub.app.people;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.subhub.app.R;
import com.subhub.app.help.HelpActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.stats.StatsActivity;
import com.subhub.app.stats.StatsSnapshot;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.SelectionGrid;
import java.util.ArrayList;
import java.util.List;

/** Person-focused overviews share the existing service controls and identity-bound routes. */
public final class RoleHomePresenter {
    private final AppCompatActivity activity;
    private final AsyncUiScope data;
    private final LinearLayout domBody, subBody, relationship;
    private final View service, oldDashboard;
    private final HomeSelection selection;
    private long lastRefresh;
    private boolean displayedDom, sessionPage;

    public static final class HomeSelection extends ViewModel {
        PersonId person;
        boolean allUsage;
        public HomeSelection() {}
    }

    public RoleHomePresenter(AppCompatActivity activity, AsyncUiScope data) {
        this.activity = activity;
        this.data = data;
        selection = new ViewModelProvider(activity).get(HomeSelection.class);
        domBody = activity.findViewById(R.id.home_dom_overview);
        subBody = activity.findViewById(R.id.home_sub_summary);
        service = activity.findViewById(R.id.home_service_widget);
        oldDashboard = activity.findViewById(R.id.sub_dashboard);
        relationship = PersonUi.column(activity);
        relationship.setId(R.id.home_assigned_dom);
        relationship.setVisibility(View.GONE);
        LinearLayout content = (LinearLayout) service.getParent();
        content.addView(relationship, content.indexOfChild(service), new LinearLayout.LayoutParams(-1, -2));
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

    public void setSessionPage(boolean value) { sessionPage = value; }

    public void refresh(boolean force) {
        boolean dom = ControllerPinManager.isDomModeActive();
        domBody.setVisibility(dom && !sessionPage ? View.VISIBLE : View.GONE);
        subBody.setVisibility(!dom && !sessionPage ? View.VISIBLE : View.GONE);
        service.setVisibility(!dom || sessionPage ? View.VISIBLE : View.GONE);
        if (dom || sessionPage) relationship.setVisibility(View.GONE);
        oldDashboard.setVisibility(View.GONE);
        if (sessionPage) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && dom == displayedDom && now - lastRefresh < 5_000L) return;
        displayedDom = dom;
        lastRefresh = now;
        PersonRepository repository = People.repository(activity.getApplicationContext());
        java.util.Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
        data.load("role-home", () -> {
            List<PersonSnapshot> values = new ArrayList<>();
            if (dom) {
                for (PersonId id : repository.people()) values.add(repository.load(id));
            } else values.add(repository.load(PersonId.LOCAL));
            return new HomeData(PersonDisplayOrder.sorted(values, locale), dom ? null : assignedDom());
        }, loaded -> {
            if (dom != ControllerPinManager.isDomModeActive() || sessionPage) return;
            if (dom) renderDom(loaded.people);
            else if (!loaded.people.isEmpty()) renderSub(loaded.people.get(0), loaded.dom);
        }, failure -> {
            if (dom != ControllerPinManager.isDomModeActive()) return;
            relationship.setVisibility(View.GONE);
            LinearLayout target = dom ? domBody : subBody;
            target.removeAllViews();
            PersonUi.text(target, activity.getString(R.string.role_person_unavailable), false);
            PersonUi.action(target, R.string.role_retry, () -> refresh(true), false);
        });
    }

    private void renderDom(List<PersonSnapshot> people) {
        domBody.removeAllViews();
        long active = people.stream().filter(person -> person.serviceActive
                && person.sync.runtime == PersonSnapshot.Freshness.CURRENT).count();
        boolean allRuntimeKnown = people.stream().allMatch(person -> person.sync.runtime == PersonSnapshot.Freshness.CURRENT);
        boolean hasRemote = people.stream().anyMatch(person -> !person.id.isLocal());
        long connected = people.stream().filter(person -> person.connection == PersonSnapshot.Connection.CONNECTED).count();
        SelectionGrid totals = new SelectionGrid(activity, null);
        totals.setTag("home_status_totals");
        domBody.addView(totals, new LinearLayout.LayoutParams(-1, -2));
        statistic(totals, Long.toString(active), allRuntimeKnown
                ? R.string.home_composition_in_session : R.string.home_composition_confirmed_active, R.drawable.ic_nav_limits);
        statistic(totals, Long.toString(hasRemote ? connected : people.size()), hasRemote
                ? R.string.home_composition_connected : R.string.home_composition_devices, R.drawable.ic_nav_people);
        heading(domBody, R.string.role_people, R.string.home_composition_view_all, () -> {
            if (ControllerPinManager.isDomModeActive()) activity.startActivity(new Intent(activity, PeopleActivity.class));
        });
        if (people.isEmpty()) {
            PersonUi.text(domBody, activity.getString(R.string.role_no_subs), false);
            return;
        }
        PersonSnapshot chosen = people.stream().filter(person -> person.id.equals(selection.person))
                .findFirst().orElse(people.get(0));
        selection.person = chosen.id;
        if (people.size() > 1) {
            HorizontalScrollView scroll = new HorizontalScrollView(activity);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout choices = new LinearLayout(activity);
            scroll.addView(choices);
            domBody.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
            for (PersonSnapshot person : people) {
                TextView chip = text(person.name, 13, true);
                chip.setGravity(Gravity.CENTER);
                chip.setMinHeight(dp(48));
                chip.setPadding(dp(16), dp(8), dp(16), dp(8));
                chip.setBackgroundResource(R.drawable.bg_home_composition_choice);
                chip.setSelected(chosen.id.equals(person.id));
                chip.setTag("home_person_choice:" + person.id.value());
                chip.setContentDescription(person.name + ". " + ProfileViews.targetDiscriminator(activity, person)
                        + ". " + PersonUi.connection(activity, person));
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
                size.setMarginEnd(dp(8));
                size.bottomMargin = dp(12);
                choices.addView(chip, size);
                clickable(chip, () -> {
                    if (!ControllerPinManager.isDomModeActive()) return;
                    selection.person = person.id;
                    renderDom(people);
                });
            }
        }
        personOverview(chosen);
    }

    private void statistic(SelectionGrid parent, String value, int label, int icon) {
        LinearLayout tile = PersonUi.column(activity);
        tile.setBackgroundResource(R.drawable.bg_home_composition_stat);
        tile.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(icon(icon), new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView number = text(value, 22, true);
        number.setTextColor(activity.getColor(R.color.accent_text));
        LinearLayout.LayoutParams numberSize = new LinearLayout.LayoutParams(0, -2, 1);
        numberSize.setMarginStart(dp(10));
        top.addView(number, numberSize);
        tile.addView(top);
        tile.addView(text(activity.getString(label), 12, false));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.setMargins(dp(3), 0, dp(3), dp(8));
        parent.addView(tile, size);
    }

    private void personOverview(PersonSnapshot person) {
        LinearLayout card = card(domBody, R.drawable.bg_home_composition_person);
        card.setTag("person:" + person.id.value());
        LinearLayout identity = new LinearLayout(activity);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setMinimumHeight(dp(64));
        identity.addView(ProfileViews.avatar(activity, person.name), new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout copy = PersonUi.column(activity);
        LinearLayout.LayoutParams copySize = new LinearLayout.LayoutParams(0, -2, 1);
        copySize.setMarginStart(dp(12));
        identity.addView(copy, copySize);
        copy.addView(text(person.name, 18, true));
        copy.addView(text(ProfileViews.targetDiscriminator(activity, person), 11, false));
        TextView connection = text(PersonUi.connection(activity, person), 12, false);
        if (person.connection == PersonSnapshot.Connection.CONNECTED)
            connection.setTextColor(activity.getColor(R.color.home_composition_current));
        copy.addView(connection);
        TextView open = text("›", 24, false);
        open.setGravity(Gravity.CENTER);
        open.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        identity.addView(open, new LinearLayout.LayoutParams(dp(24), -2));
        card.addView(identity, new LinearLayout.LayoutParams(-1, -2));
        clickable(identity, () -> openPerson(person));
        divider(card);
        String session = person.sync.runtime == PersonSnapshot.Freshness.UNKNOWN
                ? activity.getString(R.string.role_unknown_data) : person.serviceLabel;
        if (person.sync.runtime == PersonSnapshot.Freshness.STALE)
            session += " · " + activity.getString(R.string.role_stale_data);
        PersonUi.row(card, R.drawable.ic_nav_limits, activity.getString(R.string.role_session), session,
                () -> openPerson(person)).setTag("home_person_session:" + person.id.value());
        divider(card);
        PersonUi.row(card, R.drawable.ic_nav_contract, person.contractName,
                activity.getString(person.sync.contract == PersonSnapshot.Freshness.CURRENT
                        ? R.string.role_active_contract : person.sync.contract == PersonSnapshot.Freshness.STALE
                                ? R.string.role_stale_data : R.string.role_contract_unavailable), null);
        if (person.sync.hasPendingConfiguration()) PersonUi.text(card, activity.getString(R.string.role_changes_pending), false);
        if (!person.can(PersonSnapshot.Capability.EDIT_CONTRACT)) return;
        heading(domBody, R.string.home_composition_quick_controls, 0, null);
        SelectionGrid controls = new SelectionGrid(activity, null);
        controls.setTag("home_person_controls:" + person.id.value());
        domBody.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        for (PersonSnapshot.Section section : person.sections) {
            if (!List.of("censor", "limits", "wallet", "atmosphere").contains(section.key)) continue;
            LinearLayout tile = PersonUi.column(activity);
            tile.setBackgroundResource(R.drawable.bg_home_composition_stat);
            tile.setPadding(dp(12), dp(12), dp(12), dp(12));
            tile.setMinimumHeight(dp(88));
            tile.addView(icon(PersonUi.icon(section.key)), new LinearLayout.LayoutParams(dp(28), dp(28)));
            tile.addView(text(section.title, 14, true));
            if (!section.summary.isEmpty()) tile.addView(text(section.summary, 12, false));
            tile.setTag("home_person_edit:" + person.id.value() + ":" + section.key);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
            size.setMargins(dp(3), dp(3), dp(3), dp(3));
            controls.addView(tile, size);
            clickable(tile, () -> editSection(person, section.key));
        }
    }

    private void openPerson(PersonSnapshot person) {
        if (ControllerPinManager.isDomModeActive()) PersonRoutes.open(activity, person.id);
    }

    private void editSection(PersonSnapshot person, String key) {
        if (!ControllerPinManager.isDomModeActive() || !person.can(PersonSnapshot.Capability.EDIT_CONTRACT)) return;
        if (person.id.isLocal()) PersonRoutes.editSection(activity, person.id, key);
        else {
            ConnectionUi ui = People.connectionUi();
            if (ui != null) ui.editSection(activity, person.id, key);
        }
    }

    private void renderSub(PersonSnapshot person, AssignedDom dom) {
        subBody.removeAllViews();
        relationship.removeAllViews();
        relationship.setVisibility(dom == null ? View.GONE : View.VISIBLE);
        if (dom != null) {
            LinearLayout linked = card(relationship, R.drawable.bg_home_composition_stat);
            PersonUi.row(linked, R.drawable.ic_nav_people, dom.name, activity.getString(dom.status), () -> {
                if (ControllerPinManager.isDomModeActive()) return;
                ConnectionUi ui = People.connectionUi();
                if (ui != null) ui.openConnection(activity, PersonId.LOCAL);
            }).setTag("home_assigned_dom_connection");
        }
        data.cancel("contract-cover");
        LinearLayout contract = card(subBody, R.drawable.bg_home_composition_person);
        contract.setTag("home_active_contract");
        ContractHeader.add(contract, person, activity.getString(R.string.role_active_contract), data);
        clickable(contract, () -> {
            if (!ControllerPinManager.isDomModeActive()) activity.startActivity(new Intent(activity, ContractActivity.class));
        });
        usage(person, dom);
        LinearLayout stats = card(subBody, R.drawable.bg_home_composition_stat);
        PersonUi.row(stats, R.drawable.ic_nav_stats, activity.getString(R.string.role_stats),
                person.sync.stats == PersonSnapshot.Freshness.UNKNOWN ? activity.getString(R.string.role_unknown_data)
                        : StatsSnapshot.formatDuration(person.protectedSeconds) + " · " + person.activityEvents
                                + " " + activity.getString(R.string.role_stat_activity), () -> {
                    if (!ControllerPinManager.isDomModeActive()) activity.startActivity(new Intent(activity, StatsActivity.class));
                });
        TextView help = text(activity.getString(R.string.role_help_safety), 13, false);
        help.setTextColor(activity.getColor(R.color.accent_text));
        help.setGravity(Gravity.CENTER);
        help.setMinHeight(dp(48));
        subBody.addView(help, new LinearLayout.LayoutParams(-1, -2));
        clickable(help, () -> activity.startActivity(new Intent(activity, HelpActivity.class)));
    }

    private void usage(PersonSnapshot person, AssignedDom dom) {
        if (person.allowances.isEmpty()) return;
        LinearLayout card = card(subBody, R.drawable.bg_home_composition_stat);
        card.setTag("home_app_time");
        PersonUi.text(card, activity.getString(R.string.role_app_time), true);
        List<PersonSnapshot.Allowance> values = new ArrayList<>(person.allowances);
        values.sort((a, b) -> {
            if (a.packageName.isEmpty() != b.packageName.isEmpty()) return a.packageName.isEmpty() ? -1 : 1;
            long left = a.usedMillis < 0 ? Long.MAX_VALUE : Math.max(0, a.dailyMinutes * 60_000L - a.usedMillis);
            long right = b.usedMillis < 0 ? Long.MAX_VALUE : Math.max(0, b.dailyMinutes * 60_000L - b.usedMillis);
            int compared = Long.compare(left, right);
            return compared != 0 ? compared : a.label.compareToIgnoreCase(b.label);
        });
        int shown = selection.allUsage ? values.size() : Math.min(3, values.size());
        for (int index = 0; index < shown; index++) {
            PersonSnapshot.Allowance value = values.get(index);
            if (index > 0) divider(card);
            String copy = value.usedMillis < 0
                    ? activity.getString(R.string.role_allowance_unknown, value.dailyMinutes)
                    : activity.getString(R.string.role_allowance_usage, value.usedMillis / 60_000L, value.dailyMinutes);
            PersonUi.row(card, R.drawable.ic_nav_limits, value.label, copy, null);
            if (value.usedMillis < 0) continue;
            ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            progress.setProgress(value.dailyMinutes <= 0 ? 0 : (int) Math.min(1000,
                    value.usedMillis / (value.dailyMinutes * 60_000d) * 1000));
            progress.setProgressTintList(ColorStateList.valueOf(activity.getColor(R.color.accent_text)));
            progress.setContentDescription(value.label);
            card.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        }
        if (values.size() > 3) {
            TextView more = text(selection.allUsage ? activity.getString(R.string.home_composition_show_less)
                    : activity.getString(R.string.home_composition_show_all, values.size()), 13, true);
            more.setMinHeight(dp(48));
            more.setGravity(Gravity.CENTER);
            card.addView(more, new LinearLayout.LayoutParams(-1, -2));
            clickable(more, () -> { selection.allUsage = !selection.allUsage; renderSub(person, dom); });
        }
    }

    private AssignedDom assignedDom() {
        try {
            KeyholderProvider provider = People.keyholder();
            KeyholderProvider.State state = provider.state();
            String name = provider.assignedDomName();
            if (state == KeyholderProvider.State.NONE || state == KeyholderProvider.State.UNAVAILABLE
                    && (name == null || name.trim().isEmpty())) return null;
            return new AssignedDom(name == null || name.trim().isEmpty() ? activity.getString(R.string.role_your_dom) : name,
                    state == KeyholderProvider.State.READY ? R.string.role_connection_paired
                            : state == KeyholderProvider.State.PENDING ? R.string.role_connection_pending
                                    : R.string.role_connection_error);
        } catch (RuntimeException unavailable) { return null; }
    }

    private LinearLayout card(LinearLayout parent, int background) {
        LinearLayout value = PersonUi.card(parent);
        value.setBackgroundResource(background);
        return value;
    }

    private void heading(LinearLayout parent, int label, int actionLabel, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(activity.getString(label), 16, true);
        ViewCompat.setAccessibilityHeading(title, true);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView link = text(activity.getString(actionLabel), 12, false);
            link.setMinHeight(dp(48));
            link.setMinWidth(dp(48));
            link.setGravity(Gravity.CENTER);
            link.setTextColor(activity.getColor(R.color.accent_text));
            row.addView(link, new LinearLayout.LayoutParams(-2, -2));
            clickable(link, action);
        }
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.setMargins(0, dp(8), 0, dp(8));
        parent.addView(row, size);
    }

    private TextView text(String copy, int size, boolean bold) {
        TextView value = new TextView(activity);
        value.setText(copy);
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size);
        value.setTextColor(activity.getColor(bold ? R.color.text_primary : R.color.text_secondary));
        value.setIncludeFontPadding(false);
        value.setPadding(0, dp(3), 0, dp(3));
        if (bold) value.setTypeface(null, android.graphics.Typeface.BOLD);
        return value;
    }

    private ImageView icon(int resource) {
        ImageView image = new ImageView(activity);
        image.setImageResource(resource);
        image.setImageTintList(ColorStateList.valueOf(activity.getColor(R.color.accent_text)));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return image;
    }

    private void divider(LinearLayout parent) {
        View line = new View(activity);
        line.setBackgroundColor(activity.getColor(R.color.outline_subtle));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(1));
        size.setMargins(0, dp(8), 0, 0);
        parent.addView(line, size);
    }

    private void clickable(View view, Runnable action) {
        view.setFocusable(true);
        view.setClickable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        view.setForeground(activity.getDrawable(ripple.resourceId));
        view.setOnClickListener(ignored -> action.run());
        ViewCompat.setScreenReaderFocusable(view, true);
    }

    private int dp(int value) { return PersonUi.dp(activity, value); }

    private static final class AssignedDom {
        final String name;
        final int status;
        AssignedDom(String name, int status) { this.name = name; this.status = status; }
    }

    private static final class HomeData {
        final List<PersonSnapshot> people;
        final AssignedDom dom;
        HomeData(List<PersonSnapshot> people, AssignedDom dom) { this.people = people; this.dom = dom; }
    }
}
