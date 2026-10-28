package com.subhub.app.people;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
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
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.stats.StatsActivity;
import com.subhub.app.stats.StatsSnapshot;
import com.subhub.app.util.AsyncUiScope;
import java.util.ArrayList;
import java.util.List;

/** Person-focused overviews share the existing service controls and identity-bound routes. */
public final class RoleHomePresenter {
    private final AppCompatActivity activity;
    private final AsyncUiScope data;
    private final LinearLayout domBody, subBody, relationship;
    private final View service, oldDashboard;
    private final HomeSelection selection;
    private final FavoriteSubStore favorites;
    private LinearLayout domPeople;
    private List<Object> renderedDom;
    private long lastRefresh;
    private boolean displayedDom, sessionPage;

    public static final class HomeSelection extends ViewModel {
        boolean allUsage;
        public HomeSelection() {}
    }

    public RoleHomePresenter(AppCompatActivity activity, AsyncUiScope data) {
        this.activity = activity;
        this.data = data;
        selection = new ViewModelProvider(activity).get(HomeSelection.class);
        favorites = new FavoriteSubStore(activity);
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
        boolean dom = domWorkspace();
        domBody.setVisibility(dom && !sessionPage ? View.VISIBLE : View.GONE);
        subBody.setVisibility(!dom && !sessionPage ? View.VISIBLE : View.GONE);
        service.setVisibility(!dom || sessionPage ? View.VISIBLE : View.GONE);
        if (dom || sessionPage) relationship.setVisibility(View.GONE);
        oldDashboard.setVisibility(View.GONE);
        if (dom && !ControllerPinManager.isDomModeActive()) {
            data.cancel("role-home");
            domPeople = null;
            renderedDom = null;
            if (domBody.findViewWithTag("home_dom_unlock") == null) {
                domBody.removeAllViews();
                PersonUi.action(domBody, R.string.home_dom_locked, () ->
                        com.subhub.app.security.ControllerPinGate.unlock(activity, () -> refresh(true), false), true)
                        .setTag("home_dom_unlock");
            }
            return;
        }
        if (sessionPage) return;
        if (dom) ensureDomSections();
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && dom == displayedDom && now - lastRefresh < 5_000L) return;
        displayedDom = dom;
        lastRefresh = now;
        android.content.Context application = activity.getApplicationContext();
        PersonRepository repository = People.repository(application);
        java.util.Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
        data.load("role-home", () -> {
            List<PersonSnapshot> values = new ArrayList<>();
            if (dom) {
                if (!ControllerPinManager.isDomModeActive() || !DeviceRolePreferences.supportsDom(application))
                    return new HomeData(values, null);
                for (PersonId id : repository.people()) {
                    if (!ControllerPinManager.isDomModeActive() || !DeviceRolePreferences.supportsDom(application)) break;
                    values.add(repository.load(id));
                }
            } else values.add(repository.load(PersonId.LOCAL));
            return new HomeData(PersonDisplayOrder.sorted(values, locale), dom ? null : assignedDom(application));
        }, loaded -> {
            if (dom != domWorkspace() || sessionPage
                    || dom && !ControllerPinManager.isDomModeActive()) return;
            if (dom) renderDom(loaded.people);
            else if (!loaded.people.isEmpty()) renderSub(loaded.people.get(0), loaded.dom);
        }, failure -> {
            if (dom != domWorkspace()
                    || dom && !ControllerPinManager.isDomModeActive()) return;
            relationship.setVisibility(View.GONE);
            if (dom) renderedDom = null;
            LinearLayout target = dom ? domPeople : subBody;
            target.removeAllViews();
            PersonUi.text(target, activity.getString(R.string.role_person_unavailable), false);
            PersonUi.action(target, R.string.role_retry, () -> refresh(true), false);
        });
    }

    private boolean domWorkspace() {
        return DeviceRolePreferences.supportsDom(activity) && DeviceRolePreferences.isDomWorkspace(activity);
    }

    private void renderDom(List<PersonSnapshot> people) {
        List<Object> signature = new ArrayList<>();
        signature.add(favorites.identity());
        for (PersonSnapshot person : people) java.util.Collections.addAll(signature,
                person.id, person.name, person.device, person.connection, person.lastSyncedAt,
                person.serviceActive, person.serviceLabel, person.sync.runtime, person.sync.stats, person.protectedSeconds,
                person.activityEvents, person.additionOrder);
        if (domPeople != null && domPeople.getParent() == domBody && signature.equals(renderedDom)) return;
        renderedDom = signature;
        ensureDomSections();
        domPeople.removeAllViews();
        heading(domPeople, R.string.home_favorite_title, 0, null);
        PersonSnapshot favorite = favorites.resolve(people);
        if (favorite == null) PersonUi.action(domPeople, R.string.home_favorite_choose, () -> {
            if (domWorkspace() && ControllerPinManager.isDomModeActive())
                activity.startActivity(new Intent(activity, PeopleActivity.class));
        }, true).setTag("home_choose_favorite");
        else favoriteOverview(favorite);
        homeSummary(people);
        List<PersonSnapshot> recent = recentAdditions(people, favorite);
        if (!recent.isEmpty()) {
            heading(domPeople, R.string.home_recent_additions, 0, null);
            for (PersonSnapshot person : recent) {
                LinearLayout recentCard = card(domPeople, R.drawable.bg_home_recent);
                recentCard.setPadding(dp(12), dp(8), dp(12), dp(8));
                identityHeader(recentCard, person, false).setTag("home_recent_person:" + person.id.value());
            }
        }
    }

    private void homeSummary(List<PersonSnapshot> people) {
        int sessions = 0, subs = 0, unknownSessions = 0, online = 0, offline = 0, unknownDevices = 0;
        java.util.Set<String> counted = new java.util.HashSet<>();
        for (PersonSnapshot person : people) {
            if (person.connection == PersonSnapshot.Connection.REVOKED
                    || person.connection == PersonSnapshot.Connection.PENDING
                    || person.connection == PersonSnapshot.Connection.UNPAIRED) continue;
            if (!counted.add(FavoriteSubStore.identity(person.id))) continue;
            subs++;
            if (person.sync.runtime == PersonSnapshot.Freshness.CURRENT) {
                if (person.serviceActive) sessions++;
            } else unknownSessions++;
            if (person.connection == PersonSnapshot.Connection.LOCAL
                    || person.connection == PersonSnapshot.Connection.CONNECTED) online++;
            else if (person.connection == PersonSnapshot.Connection.OFFLINE
                    || person.connection == PersonSnapshot.Connection.ERROR) offline++;
            else unknownDevices++;
        }
        LinearLayout summaries = new LinearLayout(activity);
        boolean stacked = activity.getResources().getConfiguration().screenWidthDp < 360
                || activity.getResources().getConfiguration().fontScale >= 1.3f;
        summaries.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        summaries.setBaselineAligned(false);
        domPeople.addView(summaries, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout active = PersonUi.column(activity);
        LinearLayout devices = PersonUi.column(activity);
        for (LinearLayout tile : new LinearLayout[]{active, devices}) {
            tile.setBackgroundResource(R.drawable.bg_home_composition_stat);
            tile.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams size = stacked ? new LinearLayout.LayoutParams(-1, -2)
                    : new LinearLayout.LayoutParams(0, -1, 1);
            size.bottomMargin = dp(10);
            if (!stacked && tile == devices) size.setMarginStart(dp(10));
            summaries.addView(tile, size);
        }
        active.setTag("home_in_session");
        devices.setTag("home_devices");
        PersonUi.text(active, activity.getString(R.string.home_in_session), true);
        PersonUi.text(active, activity.getString(R.string.home_session_counts, sessions, subs), false);
        if (unknownSessions > 0) PersonUi.text(active,
                activity.getString(R.string.home_status_unknown, unknownSessions), false);
        PersonUi.text(devices, activity.getString(R.string.home_devices), true);
        PersonUi.text(devices, activity.getString(R.string.home_devices_counts, online, offline), false);
        if (unknownDevices > 0) PersonUi.text(devices,
                activity.getString(R.string.home_status_unknown, unknownDevices), false);
        for (LinearLayout tile : new LinearLayout[]{active, devices}) clickable(tile, () -> {
            if (domWorkspace() && ControllerPinManager.isDomModeActive())
                activity.startActivity(new Intent(activity, PeopleActivity.class));
        });
    }

    private void ensureDomSections() {
        if (domPeople != null && domPeople.getParent() == domBody) return;
        domBody.removeAllViews();
        domPeople = PersonUi.column(activity);
        domPeople.setTag("home_people_composition");
        domBody.addView(domPeople, new LinearLayout.LayoutParams(-1, -2));
        PersonUi.text(domPeople, activity.getString(R.string.role_loading), false);
        com.subhub.app.activity.ActivityPreview.add(domBody, null, 5, data, "home-activity-preview");
        LinearLayout progress = PersonUi.card(domBody);
        progress.setTag("home_dom_progress");
        PersonUi.row(progress, R.drawable.ic_nav_stats, activity.getString(R.string.role_stats), null, () -> {
            if (domWorkspace() && ControllerPinManager.isDomModeActive())
                activity.startActivity(new Intent(activity, StatsActivity.class));
        }).setTag("home_dom_statistics");
        PersonUi.row(progress, R.drawable.ic_nav_stats, activity.getString(R.string.achievements_title), null, () -> {
            if (domWorkspace() && ControllerPinManager.isDomModeActive())
                activity.startActivity(new Intent(activity, com.subhub.app.stats.AchievementsActivity.class));
        }).setTag("home_dom_achievements");
    }

    static List<PersonSnapshot> recentAdditions(List<PersonSnapshot> people, PersonSnapshot favorite) {
        List<PersonSnapshot> ordered = new ArrayList<>();
        for (PersonSnapshot person : people) {
            if (person.id.isLocal() || !FavoriteSubStore.eligible(person) || person.additionOrder <= 0) continue;
            if (favorite != null && FavoriteSubStore.identity(favorite.id).equals(FavoriteSubStore.identity(person.id))) continue;
            ordered.add(person);
        }
        ordered.sort(java.util.Comparator.comparingLong((PersonSnapshot person) -> person.additionOrder).reversed()
                .thenComparing(person -> FavoriteSubStore.identity(person.id)).thenComparing(person -> person.id.value()));
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<PersonSnapshot> result = new ArrayList<>();
        for (PersonSnapshot person : ordered) {
            if (seen.add(FavoriteSubStore.identity(person.id))) result.add(person);
            if (result.size() == 3) break;
        }
        return result;
    }

    private void favoriteOverview(PersonSnapshot person) {
        LinearLayout card = card(domPeople, R.drawable.bg_home_favorite);
        card.setTag("home_favorite:" + person.id.value());
        identityHeader(card, person, true).setTag("home_favorite_identity:" + person.id.value());
        divider(card);
        String session = person.sync.runtime == PersonSnapshot.Freshness.UNKNOWN
                ? activity.getString(R.string.role_unknown_data) : person.serviceLabel;
        if (person.sync.runtime == PersonSnapshot.Freshness.STALE)
            session += " · " + activity.getString(R.string.role_stale_data);
        PersonUi.row(card, R.drawable.ic_nav_limits, activity.getString(R.string.role_session), session, null)
                .setTag("home_favorite_session:" + person.id.value());
        divider(card);
        String stats = person.sync.stats == PersonSnapshot.Freshness.UNKNOWN
                ? activity.getString(R.string.role_unknown_data)
                : StatsSnapshot.formatDuration(person.protectedSeconds) + " · " + person.activityEvents
                        + " " + activity.getString(R.string.role_stat_activity);
        if (person.sync.stats == PersonSnapshot.Freshness.STALE)
            stats += " · " + activity.getString(R.string.role_stale_data);
        PersonUi.row(card, R.drawable.ic_nav_stats, activity.getString(R.string.role_stats), stats, null)
                .setTag("home_favorite_stats:" + person.id.value());
    }

    private LinearLayout identityHeader(LinearLayout parent, PersonSnapshot person, boolean favorite) {
        LinearLayout identity = new LinearLayout(activity);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setMinimumHeight(dp(favorite ? 72 : 64));
        identity.addView(ProfileViews.avatar(activity, person.name),
                new LinearLayout.LayoutParams(dp(favorite ? 56 : 36), dp(favorite ? 56 : 36)));
        LinearLayout copy = PersonUi.column(activity);
        LinearLayout.LayoutParams copySize = new LinearLayout.LayoutParams(0, -2, 1);
        copySize.setMarginStart(dp(12));
        identity.addView(copy, copySize);
        TextView name = text(person.name, favorite ? 22 : 14, true);
        name.setTag((favorite ? "home_favorite_name:" : "home_recent_name:") + person.id.value());
        copy.addView(name);
        TextView device = text(ProfileViews.targetDiscriminator(activity, person), 12, false);
        copy.addView(device);
        TextView connection = text(PersonUi.connection(activity, person), favorite ? 12 : 11, false);
        connection.setTag("home_recent_connection:" + person.id.value());
        if (person.connection == PersonSnapshot.Connection.CONNECTED)
            connection.setTextColor(activity.getColor(R.color.home_composition_current));
        copy.addView(connection);
        if (!favorite) for (TextView label : new TextView[] {name, device}) {
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        TextView open = text("›", 24, false);
        open.setGravity(Gravity.CENTER);
        open.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        identity.addView(open, new LinearLayout.LayoutParams(dp(24), -2));
        identity.setContentDescription(person.name + ". " + ProfileViews.targetDiscriminator(activity, person)
                + ". " + PersonUi.connection(activity, person));
        androidx.appcompat.widget.TooltipCompat.setTooltipText(identity, identity.getContentDescription());
        parent.addView(identity, new LinearLayout.LayoutParams(-1, -2));
        clickable(identity, () -> openPerson(person));
        return identity;
    }

    private void openPerson(PersonSnapshot person) {
        if (domWorkspace() && ControllerPinManager.isDomModeActive()) PersonRoutes.open(activity, person.id);
    }

    private void renderSub(PersonSnapshot person, AssignedDom dom) {
        subBody.removeAllViews();
        relationship.removeAllViews();
        relationship.setVisibility(dom == null ? View.GONE : View.VISIBLE);
        if (dom != null) {
            LinearLayout linked = card(relationship, R.drawable.bg_home_composition_stat);
            PersonUi.row(linked, R.drawable.ic_nav_people, dom.name, activity.getString(dom.status), () -> {
                if (domWorkspace()) return;
                ConnectionUi ui = People.connectionUi();
                if (ui != null) ui.openConnection(activity, PersonId.LOCAL);
            }).setTag("home_assigned_dom_connection");
        }
        PersonUi.row(PersonUi.card(subBody), R.drawable.ic_nav_tasks, activity.getString(R.string.tasks_title), "", () -> {
            if (!domWorkspace()) activity.startActivity(new Intent(activity, com.subhub.app.tasks.TasksActivity.class)
                    .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()));
        }).setTag("home_tasks");
        usage(person, dom);
        com.subhub.app.stats.AchievementPreviewView achievements =
                new com.subhub.app.stats.AchievementPreviewView(activity);
        subBody.addView(achievements, new LinearLayout.LayoutParams(-1, -2));
        achievements.load(data, "home-achievement-preview", new com.subhub.app.stats.StatsRepository(activity).load());
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
            LinearLayout row = PersonUi.row(card, R.drawable.ic_nav_limits, value.label, copy, null);
            row.setMinimumHeight(dp(48));
            row.setPadding(0, dp(4), 0, dp(4));
            if (!value.packageName.isEmpty()) try {
                android.widget.ImageView icon = (android.widget.ImageView) row.getChildAt(0);
                icon.setImageTintList(null);
                icon.setImageDrawable(activity.getPackageManager().getApplicationIcon(value.packageName));
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
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

    private static AssignedDom assignedDom(android.content.Context context) {
        try {
            KeyholderProvider provider = People.keyholder();
            KeyholderProvider.State state = provider.state();
            String name = provider.assignedDomName();
            if (state == KeyholderProvider.State.NONE || state == KeyholderProvider.State.UNAVAILABLE
                    && (name == null || name.trim().isEmpty())) return null;
            return new AssignedDom(name == null || name.trim().isEmpty() ? context.getString(R.string.role_your_dom) : name,
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
