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
    private final OverviewSession session;
    private LinearLayout domPeople;
    private com.subhub.app.stats.AchievementPreviewView domAchievements;
    private LinearLayout domFavorite, domSummary, domRecent, subUsage;
    private TextView subTasksDetail;
    private com.subhub.app.stats.AchievementPreviewView subAchievements;
    private List<Object> renderedFavorite, renderedSummary, renderedRecent, renderedUsage, renderedRelationship;
    private long lastRefresh;
    private boolean displayedDom, sessionPage;
    private TaskSummary taskSummary;
    private PersonSnapshot subPerson;
    private AssignedDom subDom;

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
        LinearLayout.LayoutParams relationshipParams = new LinearLayout.LayoutParams(-1, -2);
        relationshipParams.topMargin = activity.getResources().getDimensionPixelSize(R.dimen.primary_header_content_gap);
        content.addView(relationship, content.indexOfChild(service), relationshipParams);
        View status = activity.findViewById(R.id.protection_status_row);
        ((android.view.ViewGroup) status.getParent()).removeView(status);
        ((LinearLayout) service).addView(status, 0, new LinearLayout.LayoutParams(-1, -2));
        status.setVisibility(View.VISIBLE);
        reparent(activity.findViewById(R.id.sub_wallet_pay));
        reparent(activity.findViewById(R.id.sub_wallet_pause));
        session = new OverviewSession(activity);
    }

    private void reparent(View child) {
        ((android.view.ViewGroup) child.getParent()).removeView(child);
        ((LinearLayout) service).addView(child, new LinearLayout.LayoutParams(-1, -2));
    }

    public void setSessionPage(boolean value) { sessionPage = value; }

    public boolean overviewSession() { return !sessionPage && !domWorkspace(); }

    public void renderOverviewSession(boolean active, boolean paused) { session.state(active, paused); }

    public void refresh(boolean force) {
        boolean dom = domWorkspace();
        session.bind(overviewSession());
        domBody.setVisibility(dom && !sessionPage ? View.VISIBLE : View.GONE);
        subBody.setVisibility(!dom && !sessionPage ? View.VISIBLE : View.GONE);
        service.setVisibility(!dom || sessionPage ? View.VISIBLE : View.GONE);
        if (dom || sessionPage) relationship.setVisibility(View.GONE);
        oldDashboard.setVisibility(View.GONE);
        if (dom && !ControllerPinManager.isDomModeActive()) {
            data.cancel("role-home");
            domPeople = null;
            domAchievements = null;
            resetDomSignatures();
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
                    return new HomeData(values, null, null);
                for (PersonId id : repository.people()) {
                    if (!ControllerPinManager.isDomModeActive() || !DeviceRolePreferences.supportsDom(application)) break;
                    values.add(repository.load(id));
                }
            } else values.add(repository.load(PersonId.LOCAL));
            return new HomeData(PersonDisplayOrder.sorted(values, locale), dom ? null : assignedDom(application),
                    dom ? null : TaskSummary.read(application));
        }, loaded -> {
            if (dom != domWorkspace() || sessionPage
                    || dom && !ControllerPinManager.isDomModeActive()) return;
            if (dom) renderDom(loaded.people);
            else if (!loaded.people.isEmpty()) {
                taskSummary = loaded.tasks;
                renderSub(loaded.people.get(0), loaded.dom);
            }
        }, failure -> {
            if (dom != domWorkspace()
                    || dom && !ControllerPinManager.isDomModeActive()) return;
            relationship.setVisibility(View.GONE);
            if (dom) resetDomSignatures();
            else renderedRelationship = null;
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
        ensureDomSections();
        domAchievements.load(data, "home-achievement-preview");
        PersonSnapshot favorite = favorites.resolve(people);
        List<Object> signature = favorite == null ? java.util.Collections.singletonList(favorites.identity())
                : personSignature(favorite, true);
        if (!signature.equals(renderedFavorite)) {
            renderedFavorite = signature;
            domFavorite.removeAllViews();
            if (favorite == null) {
                LinearLayout empty = OverviewUi.card(domFavorite);
                OverviewUi.heading(empty, R.string.home_favorite_title, 0, null);
                PersonUi.action(empty, R.string.home_favorite_choose, () -> {
                if (domWorkspace() && ControllerPinManager.isDomModeActive())
                    activity.startActivity(new Intent(activity, PeopleActivity.class));
                }, true).setTag("home_choose_favorite");
            } else favoriteOverview(favorite);
        }
        signature = new ArrayList<>();
        for (PersonSnapshot person : people) java.util.Collections.addAll(signature,
                person.id, person.connection, person.serviceActive, person.sync.runtime);
        if (!signature.equals(renderedSummary)) {
            renderedSummary = signature;
            domSummary.removeAllViews();
            homeSummary(people);
        }
        List<PersonSnapshot> recent = recentAdditions(people, favorite);
        signature = new ArrayList<>();
        for (PersonSnapshot person : recent) signature.addAll(personSignature(person, false));
        if (!signature.equals(renderedRecent)) {
            renderedRecent = signature;
            domRecent.removeAllViews();
            if (!recent.isEmpty()) {
                LinearLayout recentCard = OverviewUi.card(domRecent);
                OverviewUi.heading(recentCard, R.string.home_recent_additions, R.string.overview_view_subs, () -> {
                    if (domWorkspace() && ControllerPinManager.isDomModeActive())
                        activity.startActivity(new Intent(activity, PeopleActivity.class));
                });
                for (PersonSnapshot person : recent)
                    identityHeader(recentCard, person, false).setTag("home_recent_person:" + person.id.value());
            }
        }
    }

    private void resetDomSignatures() {
        renderedFavorite = null; renderedSummary = null; renderedRecent = null;
    }

    private List<Object> personSignature(PersonSnapshot person, boolean favorite) {
        List<Object> signature = new ArrayList<>(java.util.Arrays.asList(person.id, person.name, person.profile,
                ProfileViews.targetDiscriminator(activity, person), PersonUi.connection(activity, person)));
        if (favorite) java.util.Collections.addAll(signature, person.serviceLabel,
                person.sync.runtime, person.sync.stats, person.protectedSeconds, person.activityEvents);
        return signature;
    }

    private void homeSummary(List<PersonSnapshot> people) {
        int sessions = 0, subs = 0, local = 0, paired = 0, unknownSessions = 0, online = 0, offline = 0, unknownDevices = 0;
        java.util.Set<String> counted = new java.util.HashSet<>();
        for (PersonSnapshot person : people) {
            if (person.connection == PersonSnapshot.Connection.REVOKED
                    || person.connection == PersonSnapshot.Connection.PENDING
                    || person.connection == PersonSnapshot.Connection.UNPAIRED) continue;
            if (!counted.add(FavoriteSubStore.identity(person.id))) continue;
            if (person.sync.runtime == PersonSnapshot.Freshness.CURRENT) {
                if (person.serviceActive) sessions++;
            } else unknownSessions++;
            subs++;
            if (person.id.isLocal()) { local++; continue; }
            paired++;
            if (person.connection == PersonSnapshot.Connection.CONNECTED) online++;
            else if (person.connection == PersonSnapshot.Connection.OFFLINE) offline++;
            else unknownDevices++;
        }
        LinearLayout summaries = new LinearLayout(activity);
        boolean stacked = activity.getResources().getConfiguration().screenWidthDp < 360
                || activity.getResources().getConfiguration().fontScale >= 1.3f;
        summaries.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        summaries.setBaselineAligned(false);
        domSummary.addView(summaries, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout inSession = summary(summaries, stacked, R.drawable.ic_overview_clock,
                R.string.home_in_session, unknownSessions == 0 ? Integer.toString(sessions)
                        : activity.getString(R.string.overview_unknown));
        inSession.setTag("home_in_session");
        if (unknownSessions > 0) PersonUi.text(inSession,
                activity.getString(R.string.home_status_unknown, unknownSessions), false);
        if (unknownSessions > 0 && sessions > 0) PersonUi.text(inSession,
                activity.getString(R.string.overview_confirmed_active, sessions), false).setTextSize(12);
        List<String> status = new ArrayList<>();
        if (local > 0) status.add(activity.getString(R.string.home_local_sub_count, local));
        if (paired > 0) status.add(activity.getString(R.string.home_devices_counts, online, offline));
        if (unknownDevices > 0) status.add(activity.getString(R.string.home_status_unknown, unknownDevices));
        LinearLayout pairedSubs = summary(summaries, stacked, R.drawable.ic_nav_people,
                R.string.home_subs_total, Integer.toString(subs), String.join(" · ", status));
        pairedSubs.setTag("home_devices");
    }

    private LinearLayout summary(LinearLayout parent, boolean stacked, int icon, int label, String value) {
        return summary(parent, stacked, icon, label, value, "");
    }

    private LinearLayout summary(LinearLayout parent, boolean stacked, int icon, int label, String value, String status) {
        LinearLayout tile = PersonUi.column(activity);
        tile.setBackgroundResource(R.drawable.bg_overview_card); tile.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams size = stacked ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -1, 1);
        size.bottomMargin = dp(10);
        if (!stacked && parent.getChildCount() > 0) size.setMarginStart(dp(10));
        parent.addView(tile, size);
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView image = new android.widget.ImageView(activity); image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(activity.getColor(R.color.text_primary)));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(image, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout copy = PersonUi.column(activity);
        LinearLayout.LayoutParams copySize = new LinearLayout.LayoutParams(0, -2, 1); copySize.setMarginStart(dp(12));
        row.addView(copy, copySize);
        copy.addView(OverviewUi.text(activity, value, 22, true), new LinearLayout.LayoutParams(-1, -2));
        String subtitle = activity.getString(label) + (status.isEmpty() ? "" : " · " + status);
        TextView caption = OverviewUi.text(activity, subtitle, 12, false);
        if (label == R.string.home_subs_total) caption.setTag("home_subs_label");
        copy.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        tile.addView(row, new LinearLayout.LayoutParams(-1, -2));
        OverviewUi.click(tile, () -> {
            if (domWorkspace() && ControllerPinManager.isDomModeActive())
                activity.startActivity(new Intent(activity, PeopleActivity.class));
        });
        return tile;
    }

    private void ensureDomSections() {
        if (domPeople != null && domPeople.getParent() == domBody
                && domFavorite != null && domFavorite.getParent() == domPeople) return;
        domBody.removeAllViews();
        domPeople = PersonUi.column(activity);
        domPeople.setTag("home_people_composition");
        domBody.addView(domPeople, new LinearLayout.LayoutParams(-1, -2));
        resetDomSignatures();
        domFavorite = PersonUi.column(activity); domSummary = PersonUi.column(activity); domRecent = PersonUi.column(activity);
        for (LinearLayout slot : new LinearLayout[]{domFavorite, domSummary})
            domPeople.addView(slot, new LinearLayout.LayoutParams(-1, -2));
        com.subhub.app.journal.JournalPreview.addOverview(OverviewUi.card(domPeople), true,
                () -> com.subhub.app.journal.JournalRoutes.own(activity, true));
        domPeople.addView(domRecent, new LinearLayout.LayoutParams(-1, -2));
        PersonUi.text(domFavorite, activity.getString(R.string.role_loading), false);
        com.subhub.app.activity.ActivityPreview.addOverview(domBody, data, "home-activity-preview");
        domAchievements = achievementPreview(domBody);
        helpSafety(domBody, "home_help:dom");
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
        LinearLayout card = OverviewUi.card(domFavorite);
        card.setTag("home_favorite:" + person.id.value());
        card.setBackgroundResource(R.drawable.bg_home_favorite);
        OverviewUi.heading(card, R.string.home_favorite_title, R.string.overview_view_profile, () -> openPerson(person));
        LinearLayout identity = identityHeader(card, person, true);
        identity.setTag("home_favorite_identity:" + person.id.value());
        LinearLayout copy = identity.findViewWithTag("home_identity_copy");
        if (person.sync.runtime == PersonSnapshot.Freshness.UNKNOWN && person.sync.stats == PersonSnapshot.Freshness.UNKNOWN) {
            TextView unknown = OverviewUi.text(activity, activity.getString(R.string.overview_session_stats_unknown), 13, false);
            unknown.setTag("home_favorite_unknown:" + person.id.value()); copy.addView(unknown); return;
        }
        String session = person.sync.runtime == PersonSnapshot.Freshness.UNKNOWN
                ? activity.getString(R.string.overview_session_unknown) : person.serviceLabel;
        if (person.sync.runtime == PersonSnapshot.Freshness.STALE)
            session += " · " + activity.getString(R.string.role_stale_data);
        TextView sessionText = OverviewUi.text(activity, session, 13, false);
        sessionText.setTag("home_favorite_session:" + person.id.value()); copy.addView(sessionText);
        String stats = person.sync.stats == PersonSnapshot.Freshness.UNKNOWN
                ? activity.getString(R.string.overview_stats_unknown)
                : activity.getString(R.string.overview_protected_activity,
                        StatsSnapshot.formatDuration(person.protectedSeconds), person.activityEvents);
        if (person.sync.stats == PersonSnapshot.Freshness.STALE)
            stats += " · " + activity.getString(R.string.role_stale_data);
        TextView statsText = OverviewUi.text(activity, stats, 12, false);
        statsText.setTag("home_favorite_stats:" + person.id.value()); copy.addView(statsText);
    }

    private LinearLayout identityHeader(LinearLayout parent, PersonSnapshot person, boolean favorite) {
        OverviewUi.Identity identity = OverviewUi.identity(parent, person.profile, false,
                (favorite ? "home_favorite:" : "home_recent:") + person.id.value());
        identity.copy.setTag("home_identity_copy");
        identity.name.setTag((favorite ? "home_favorite_name:" : "home_recent_name:") + person.id.value());
        if (person.connection != PersonSnapshot.Connection.LOCAL) {
            TextView connection = OverviewUi.text(activity, PersonUi.connection(activity, person), 12, false);
            connection.setTag("home_recent_connection:" + person.id.value());
            if (person.connection == PersonSnapshot.Connection.CONNECTED)
                connection.setTextColor(activity.getColor(R.color.home_composition_current));
            if (person.connection == PersonSnapshot.Connection.CONNECTED
                    && activity.getResources().getConfiguration().fontScale < 1.3f) {
                identity.copy.removeView(identity.name);
                LinearLayout line = new LinearLayout(activity); line.setGravity(Gravity.CENTER_VERTICAL);
                line.addView(identity.name, new LinearLayout.LayoutParams(0, -2, 1));
                LinearLayout.LayoutParams statusSize = new LinearLayout.LayoutParams(-2, -2); statusSize.setMarginStart(dp(8));
                line.addView(connection, statusSize); identity.copy.addView(line, 0);
            } else identity.copy.addView(connection);
        }
        OverviewUi.click(identity.view, () -> openPerson(person));
        return identity.view;
    }

    private void openPerson(PersonSnapshot person) {
        if (domWorkspace() && ControllerPinManager.isDomModeActive()) PersonRoutes.open(activity, person.id);
    }

    private String taskSubtitle() {
        try {
            com.subhub.app.tasks.TaskRepository repo = new com.subhub.app.tasks.TaskRepository(activity, PersonId.LOCAL);
            if (!repo.allowed()) return activity.getString(R.string.tasks_disabled);
            if (!repo.supported()) return activity.getString(R.string.tasks_update_devices);
            if (taskSummary == null || !taskSummary.scope.equals(repo.target().scope) || taskSummary.active < 0)
                return activity.getString(R.string.tasks_profile_unavailable);
            return activity.getResources().getQuantityString(R.plurals.tasks_active_count,
                    taskSummary.active, taskSummary.active);
        } catch (RuntimeException unavailable) { return activity.getString(R.string.tasks_profile_unavailable); }
    }

    private void renderSub(PersonSnapshot person, AssignedDom dom) {
        subPerson = person; subDom = dom;
        if (subAchievements == null || subAchievements.getParent() != subBody) ensureSubSections();
        List<Object> relationSignature = java.util.Arrays.asList(person.contractDom, person.contractDomId,
                person.sync.contract, person.sync.desiredRevision, person.sync.appliedRevision,
                dom == null ? null : dom.name, dom == null ? null : dom.status);
        boolean assigned = person.contractDomId != null || dom != null;
        if (!relationSignature.equals(renderedRelationship)) {
            renderedRelationship = relationSignature;
            relationship.removeAllViews();
            if (assigned) {
                LinearLayout linked = OverviewUi.card(relationship);
                Runnable open = () -> { if (!domWorkspace()) activity.startActivity(new Intent(activity, ContractActivity.class)); };
                OverviewUi.heading(linked, R.string.overview_your_dom, R.string.overview_open_contract, open);
                ProfileData profile = person.contractDom != null ? person.contractDom
                        : ProfileData.empty(dom.name);
                OverviewUi.Identity identity = OverviewUi.identity(linked, profile, true, "home_contract_dom");
                if (!profile.about.isEmpty()) {
                    TextView about = OverviewUi.text(activity, profile.about, 13, false);
                    about.setMaxLines(2); about.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    about.setTag("home_dom_about"); identity.copy.addView(about);
                }
                OverviewUi.click(identity.view, open);
                identity.view.setTag("home_assigned_dom_connection");
                if (person.sync.hasPendingConfiguration()) PersonUi.text(linked, activity.getString(R.string.role_changes_pending), false);
                if (person.sync.contract != PersonSnapshot.Freshness.CURRENT) PersonUi.text(linked,
                        activity.getString(person.sync.contract == PersonSnapshot.Freshness.STALE
                                ? R.string.role_stale_data : R.string.role_unknown_data), false);
            }
        }
        relationship.setVisibility(assigned ? View.VISIBLE : View.GONE);
        String subtitle = taskSubtitle();
        if (!android.text.TextUtils.equals(subTasksDetail.getText(), subtitle)) subTasksDetail.setText(subtitle);
        List<Object> usageSignature = new ArrayList<>();
        usageSignature.add(selection.allUsage);
        for (PersonSnapshot.Allowance value : person.allowances) java.util.Collections.addAll(usageSignature,
                value.packageName, value.label, value.dailyMinutes, value.usedMillis);
        if (!usageSignature.equals(renderedUsage)) {
            renderedUsage = usageSignature; subUsage.removeAllViews(); usage(person, dom);
        }
        subAchievements.load(data, "home-achievement-preview");
    }

    private void ensureSubSections() {
        subBody.removeAllViews();
        renderedUsage = null; renderedRelationship = null;
        com.subhub.app.journal.JournalPreview.addOverview(OverviewUi.card(subBody), false,
                () -> com.subhub.app.journal.JournalRoutes.own(activity, false));
        LinearLayout tasks = OverviewUi.row(OverviewUi.compactCard(subBody), R.drawable.ic_nav_tasks,
                activity.getString(R.string.tasks_title), taskSubtitle(), () -> {
                    if (!domWorkspace()) activity.startActivity(new Intent(activity, com.subhub.app.tasks.TasksActivity.class)
                            .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()));
                });
        tasks.setTag("home_tasks"); subTasksDetail = tasks.findViewWithTag("overview_row_value");
        subUsage = PersonUi.column(activity); subBody.addView(subUsage, new LinearLayout.LayoutParams(-1, -2));
        subAchievements = achievementPreview(subBody);
        helpSafety(subBody, "home_help");
    }

    private void helpSafety(LinearLayout parent, String tag) {
        OverviewUi.row(OverviewUi.compactCard(parent), R.drawable.ic_tab_help, activity.getString(R.string.role_help_safety), "",
                () -> activity.startActivity(new Intent(activity, HelpActivity.class))).setTag(tag);
    }

    private com.subhub.app.stats.AchievementPreviewView achievementPreview(LinearLayout parent) {
        com.subhub.app.stats.AchievementPreviewView preview = new com.subhub.app.stats.AchievementPreviewView(activity, true);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_group);
        parent.addView(preview, size);
        preview.load(data, "home-achievement-preview");
        return preview;
    }

    private void usage(PersonSnapshot person, AssignedDom dom) {
        if (person.allowances.isEmpty()) return;
        LinearLayout card = OverviewUi.card(subUsage); card.setTag("home_app_time");
        List<PersonSnapshot.Allowance> values = new ArrayList<>(person.allowances);
        values.sort((a, b) -> {
            if (a.packageName.isEmpty() != b.packageName.isEmpty()) return a.packageName.isEmpty() ? -1 : 1;
            long left = a.usedMillis < 0 ? Long.MAX_VALUE : Math.max(0, a.dailyMinutes * 60_000L - a.usedMillis);
            long right = b.usedMillis < 0 ? Long.MAX_VALUE : Math.max(0, b.dailyMinutes * 60_000L - b.usedMillis);
            int compared = Long.compare(left, right);
            return compared != 0 ? compared : a.label.compareToIgnoreCase(b.label);
        });
        OverviewUi.heading(card, R.string.role_app_time, selection.allUsage
                ? R.string.profile_show_less : R.string.overview_show_all, values.size() <= 3 ? null : () -> {
                    selection.allUsage = !selection.allUsage; renderSub(subPerson, subDom);
                });
        if (values.size() > 3) {
            android.view.ViewGroup header = (android.view.ViewGroup) card.getChildAt(0);
            header.getChildAt(header.getChildCount() - 1).setTag("home_usage_all");
        }
        int shown = selection.allUsage ? values.size() : Math.min(3, values.size());
        for (int index = 0; index < shown; index++) {
            PersonSnapshot.Allowance value = values.get(index);
            if (index > 0) OverviewUi.divider(card);
            String copy = value.usedMillis < 0
                    ? activity.getString(R.string.role_allowance_unknown, value.dailyMinutes)
                    : activity.getString(R.string.role_allowance_usage, value.usedMillis / 60_000L, value.dailyMinutes);
            LinearLayout row = OverviewUi.row(card, R.drawable.ic_nav_limits, value.label, copy, null);
            row.setMinimumHeight(dp(32)); row.setPadding(0, dp(4), 0, dp(4));
            com.subhub.app.util.AppIcons.bind((android.widget.ImageView) row.getChildAt(0), value.packageName,
                    value.packageName.isEmpty() ? R.drawable.ic_nav_limits : R.drawable.ic_settings_apps);
            if (value.usedMillis < 0) continue;
            ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000); progress.setProgress(value.dailyMinutes <= 0 ? 0 : (int) Math.min(1000,
                    value.usedMillis / (value.dailyMinutes * 60_000d) * 1000));
            progress.setProgressTintList(ColorStateList.valueOf(activity.getColor(R.color.accent_text)));
            progress.setContentDescription(value.label);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(4));
            size.setMarginStart(dp(36)); size.bottomMargin = dp(8); card.addView(progress, size);
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

    private int dp(int value) { return PersonUi.dp(activity, value); }

    private static final class AssignedDom {
        final String name;
        final int status;
        AssignedDom(String name, int status) { this.name = name; this.status = status; }
    }

    private static final class TaskSummary {
        final String scope;
        final int active;
        TaskSummary(String scope, int active) { this.scope = scope; this.active = active; }
        static TaskSummary read(android.content.Context app) {
            try {
                com.subhub.app.tasks.TaskRepository repo = new com.subhub.app.tasks.TaskRepository(app, PersonId.LOCAL);
                String scope = repo.target().scope;
                if (!repo.allowed() || !repo.supported()) return new TaskSummary(scope, -1);
                int count = com.subhub.app.tasks.TaskEngine.activeCount(repo.list(System.currentTimeMillis()));
                if (!scope.equals(repo.target().scope)) return new TaskSummary("", -1);
                return new TaskSummary(scope, count);
            } catch (Exception unavailable) { return new TaskSummary("", -1); }
        }
    }

    private static final class HomeData {
        final List<PersonSnapshot> people;
        final AssignedDom dom;
        final TaskSummary tasks;
        HomeData(List<PersonSnapshot> people, AssignedDom dom, TaskSummary tasks) {
            this.people = people; this.dom = dom; this.tasks = tasks;
        }
    }
}
