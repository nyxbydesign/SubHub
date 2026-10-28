package com.subhub.app.stats;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

import com.subhub.app.R;
import com.subhub.app.penance.*;
import com.subhub.app.util.PreferencePage;

import java.text.DateFormat;
import java.util.*;

/** Current service, lifetime totals, dated activity and recorded sessions share one page. */
public final class StatsActivity extends PreferencePage {
    private DailyStatsPanel daily;
    private RemoteStatsSource remote;
    private AchievementRole workspace;
    private boolean domOverview;
    private final android.os.Handler remoteClock = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshRemote = new Runnable() {
        @Override public void run() {
            if (remote == null && !domOverview || isFinishing()) return;
            try {
                if (remote != null) remote.requireAccess();
                if (!workspace.accessible(StatsActivity.this)) { finish(); return; }
                bindTotals();
                daily.refreshIfNeeded();
            }
            catch (RuntimeException unavailable) { finish(); return; }
            remoteClock.postDelayed(this, domOverview ? 5000 : 2000);
        }
    };
    private LinearLayout totalsDetails, sessionList, currentSession, allowances;
    private TextView lifetimeTime, lifetimeCensors, lifetimeSessions;
    private AchievementPreviewView achievements;
    private LocalRetainedStatistics localFacts;
    private boolean firstResume = true;
    private int sessionLimit = 3;
    private com.subhub.app.util.AsyncUiScope data;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        workspace = AchievementRole.current(this);
        if (!workspace.accessible(this)) { finish(); return; }
        domOverview = workspace == AchievementRole.DOM
                && !getIntent().hasExtra(com.subhub.app.people.PersonRoutes.EXTRA_PERSON_ID);
        if (!domOverview && !com.subhub.app.people.PersonRoutes.isLocalTarget(getIntent())) {
            try { remote = new RemoteStatsSource(this, com.subhub.app.people.PersonRoutes.requireId(getIntent())); }
            catch (RuntimeException unavailable) { finish(); return; }
        }
        if (state != null) {
            sessionLimit = Math.max(3, Math.min(30, state.getInt("stats_session_limit", 3)));
        }
        page(R.string.role_stats);
        if (domOverview) page.setTag("dom_statistics");
        if (!getIntent().hasExtra(com.subhub.app.people.PersonRoutes.EXTRA_PERSON_ID))
            StatsSections.add(this, page, false);
        data = com.subhub.app.util.AsyncUiScope.forPage(this);
        currentSession = card(page);
        currentSession.setId(R.id.profile_stats_current);
        LinearLayout summary = card(page);
        summary.setId(R.id.stats_lifetime_summary);
        heading(summary, R.string.stats_all_time);
        LinearLayout numbers = new LinearLayout(this);
        numbers.setOrientation(getResources().getConfiguration().screenWidthDp < 360
                || getResources().getConfiguration().fontScale >= 1.3f
                ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        summary.addView(numbers, new LinearLayout.LayoutParams(-1, -2));
        lifetimeTime = metric(numbers, domOverview ? R.string.dom_stats_time : R.string.stats_time);
        lifetimeCensors = metric(numbers, domOverview ? R.string.dom_stats_paired : R.string.stats_censors);
        lifetimeSessions = metric(numbers, domOverview ? R.string.dom_stats_sessions : R.string.stats_sessions);
        if (domOverview) {
            lifetimeTime.setTag("dom_stats_time");
            lifetimeCensors.setTag("dom_stats_paired");
            lifetimeSessions.setTag("dom_stats_sessions");
        }
        daily = new DailyStatsPanel(this, false, state,
                domOverview ? new DomDailyStatsSource(this) : remote);
        page.addView(daily, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams activityParams =
                (LinearLayout.LayoutParams) daily.getLayoutParams();
        activityParams.bottomMargin = dp(14);
        allowances = new LinearLayout(this);
        allowances.setOrientation(LinearLayout.VERTICAL);
        allowances.setId(R.id.profile_stats_allowances);
        page.addView(allowances, new LinearLayout.LayoutParams(-1, -2));
        sessionList = card(page);
        sessionList.setTag("stats_session_history");
        totalsDetails = card(page);
        totalsDetails.setId(R.id.stats_totals_details);
        achievements = new AchievementPreviewView(this);
        achievements.setId(R.id.stats_achievements);
        page.addView(achievements, new LinearLayout.LayoutParams(-1, -2));
        bindTotals();
        bindSessions();
        bindAllowances();
        com.subhub.app.util.SubHubNavigation.attachToPage(this, page,
                com.subhub.app.util.SubHubNavigation.Screen.STATS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (workspace == null || !workspace.accessible(this)) { finish(); return; }
        page.setVisibility(android.view.View.VISIBLE);
        if (remote != null || domOverview) {
            try { if (remote != null) remote.requireAccess(); } catch (RuntimeException unavailable) { finish(); return; }
            remoteClock.removeCallbacks(refreshRemote); remoteClock.post(refreshRemote);
        }
        if (isFinishing()) return;
        com.subhub.app.util.SubHubNavigation.bind(this, findViewById(android.R.id.content),
                com.subhub.app.util.SubHubNavigation.Screen.STATS);
        boolean profile = getIntent().hasExtra(com.subhub.app.people.PersonRoutes.EXTRA_PERSON_ID);
        boolean dom = workspace == AchievementRole.DOM && profile;
        android.view.View root = findViewById(android.R.id.content);
        android.widget.TextView role = com.subhub.app.util.PrimaryHeader.editLockButton(root);
        role.setVisibility(dom ? android.view.View.GONE : android.view.View.VISIBLE);
        com.subhub.app.security.ControllerEditMode.renderButton(this, role);
        com.subhub.app.util.PrimaryHeader.backButton(root).setVisibility(
                dom ? android.view.View.VISIBLE : android.view.View.GONE);
        com.subhub.app.util.PrimaryHeader.bind(root, 0, R.string.role_stats,
                dom ? R.string.role_this_device : 0);
        if (remote != null) {
            try {
                com.subhub.app.people.PersonSnapshot person = remote.person();
                com.subhub.app.util.PrimaryHeader.subtitle(root).setText(person.name + " · " + com.subhub.app.people.ProfileViews.targetDiscriminator(this, person));
                com.subhub.app.util.PrimaryHeader.subtitle(root).setVisibility(android.view.View.VISIBLE);
                android.view.View nav = root.findViewById(R.id.bottom_navigation); if (nav != null) nav.setVisibility(android.view.View.GONE);
            } catch (Exception unavailable) { finish(); return; }
        } else if (dom) com.subhub.app.util.PrimaryHeader.subtitle(root).setText(com.subhub.app.people.ProfileNames.get(this, false));
        if (firstResume) {
            firstResume = false;
            return;
        }
        bindTotals();
        bindSessions();
        bindAllowances();
        daily.refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (daily != null) daily.save(state);
        state.putInt("stats_session_limit", sessionLimit);
        super.onSaveInstanceState(state);
    }

    private TextView metric(LinearLayout parent, int name) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        boolean horizontal = parent.getOrientation() == LinearLayout.HORIZONTAL;
        parent.addView(cell, new LinearLayout.LayoutParams(horizontal ? 0 : -1, -2, horizontal ? 1 : 0));
        TextView value = text(cell, "", 22, false);
        value.setTag("stats:metric-value");
        value.setTypeface(null, Typeface.BOLD);
        text(cell, getString(name), 12, true).setTag("stats:metric-label");
        return value;
    }

    @Override protected void onPause() {
        if (workspace == AchievementRole.DOM && page != null) page.setVisibility(android.view.View.INVISIBLE);
        if (data != null) data.cancel("dom-statistics");
        if (data != null) data.cancel("local-statistics");
        remoteClock.removeCallbacks(refreshRemote);
        super.onPause();
    }

    private void bindTotals() {
        if (domOverview) { bindDomTotals(); return; }
        if (remote != null) { bindRemoteTotals(); return; }
        if (data.isPending("local-statistics")) return;
        android.content.Context app = getApplicationContext();
        data.load("local-statistics", () -> {
            LocalRetainedStatistics facts = LocalRetainedStatistics.load(app);
            HomeAchievementPreview preview = null;
            if (workspace == AchievementRole.SUB) preview = HomeAchievementPreview.loadLocal(app, facts);
            else HistoricalAchievementBackfill.local(app, new AchievementManager(app), facts);
            return new LocalOverview(facts, preview);
        }, loaded -> {
            if (!workspace.accessible(this)) { finish(); return; }
            localFacts = loaded.facts;
            if (loaded.achievements != null) achievements.render(loaded.achievements);
            else achievements.setVisibility(android.view.View.GONE);
            bindLocalTotals(loaded.facts);
            bindSessions();
        }, failure -> {
            lifetimeTime.setText(unknown()); lifetimeCensors.setText(unknown()); lifetimeSessions.setText(unknown());
            totalsDetails.removeAllViews(); text(totalsDetails, getString(R.string.role_person_unavailable), 13, true);
        });
    }

    private static final class LocalOverview {
        final LocalRetainedStatistics facts;
        final HomeAchievementPreview achievements;
        LocalOverview(LocalRetainedStatistics facts, HomeAchievementPreview achievements) {
            this.facts = facts; this.achievements = achievements;
        }
    }

    private void bindLocalTotals(LocalRetainedStatistics facts) {
        StatsRepository repository = facts.repository;
        StatsSnapshot stats = facts.stats;
        lifetimeTime.setText(duration(stats.getTotalProtectedSeconds()));
        lifetimeCensors.setText(number(stats.getTotalBlocks()));
        lifetimeSessions.setText(number(stats.getSessions()));
        bindCurrent(repository, stats);
        totalsDetails.removeAllViews();
        heading(totalsDetails, R.string.profile_stats_feature_totals);
        row(totalsDetails, R.string.statistics_longest, duration(stats.getLongestSessionSeconds()));
        row(
                totalsDetails,
                R.string.statistics_current_streak,
                getString(R.string.stats_days_value, stats.getCurrentStreak()));
        row(
                totalsDetails,
                R.string.stats_limited_app_time,
                duration(stats.getLimitedAppMillis() / 1000));
        row(totalsDetails, R.string.stats_limit_stops, number(stats.getLimitInterventions()));
        row(totalsDetails, R.string.stats_whispers, number(stats.getSubliminalImpressions()));
        row(totalsDetails, R.string.stats_popups, number(stats.getPopupImpressions()));
        row(totalsDetails, R.string.daily_photos, number(stats.getExportedImages()));
        row(totalsDetails, R.string.stats_tributes, number(stats.getTributeEvents()));
        for (String currency : new String[] {"EUR", "USD"}) {
            text(totalsDetails, currency, 13, true).setTypeface(null, Typeface.BOLD);
            row(
                    totalsDetails,
                    R.string.stats_assessed,
                    WalletCurrency.format(currency, facts.assessed.get(currency)));
            row(
                    totalsDetails,
                    R.string.stats_paid,
                    WalletCurrency.format(currency, facts.paid.get(currency)));
        }
    }

    private void bindDomTotals() {
        if (data.isPending("dom-statistics")) return;
        android.content.Context app = getApplicationContext();
        data.load("dom-statistics", () -> {
            DomStatsRepository.Loaded value = new DomStatsRepository(app).loadWithHistory();
            return new DomOverview(value.stats, HomeAchievementPreview.loadDom(app, value));
        }, loaded -> {
            if (!workspace.accessible(this)) { page.setVisibility(android.view.View.INVISIBLE); finish(); return; }
            DomStatsSnapshot value = loaded.stats;
            lifetimeTime.setText(value.hasTotals() ? duration(value.sessionSeconds) : unknown());
            lifetimeCensors.setText(number(value.subs));
            lifetimeSessions.setText(value.hasTotals() ? number(value.sessions) : unknown());
            currentSession.removeAllViews();
            heading(currentSession, R.string.dom_stats_current);
            row(currentSession, R.string.dom_stats_active,
                    gauge(value.activeSubs, value.activeKnown, value.subs)).setTag("dom_stats_active");
            row(currentSession, R.string.dom_stats_hardcore,
                    gauge(value.hardcoreSubs, value.hardcoreKnown, value.subs)).setTag("dom_stats_hardcore");
            if (value.activeKnown < value.subs)
                text(currentSession, getString(R.string.dom_stats_runtime_coverage, value.activeKnown, value.subs), 12, true);
            if (value.hardcoreKnown < value.subs)
                text(currentSession, getString(R.string.dom_stats_hardcore_coverage, value.hardcoreKnown, value.subs), 12, true);
            totalsDetails.removeAllViews();
            heading(totalsDetails, R.string.profile_stats_feature_totals);
            for (String currency : value.paidCents.keySet()) {
                text(totalsDetails, currency, 13, true).setTypeface(null, Typeface.BOLD);
                row(totalsDetails, R.string.stats_assessed,
                        value.hasWalletTotals() ? WalletCurrency.format(currency, value.assessedCents.get(currency)) : unknown())
                        .setTag("dom_assessed_" + currency);
                row(totalsDetails, R.string.stats_paid,
                        value.hasWalletTotals() ? WalletCurrency.format(currency, value.paidCents.get(currency)) : unknown())
                        .setTag("dom_paid_" + currency);
            }
            if (value.totalsKnown < value.subs)
                text(totalsDetails, getString(R.string.dom_stats_totals_coverage, value.totalsKnown, value.subs), 12, true);
            if (value.totalsFresh < value.totalsKnown)
                text(totalsDetails, getString(R.string.dom_stats_stale_totals, value.totalsKnown - value.totalsFresh), 12, true);
            if (value.walletKnown < value.subs)
                text(totalsDetails, getString(R.string.dom_stats_wallet_coverage, value.walletKnown, value.subs), 12, true);
            text(totalsDetails, getString(R.string.dom_stats_scope), 12, true);
            achievements.render(loaded.achievements);
        }, failure -> {
            if (!workspace.accessible(this)) { finish(); return; }
            lifetimeTime.setText(unknown()); lifetimeCensors.setText(unknown()); lifetimeSessions.setText(unknown());
            currentSession.removeAllViews(); totalsDetails.removeAllViews(); achievements.removeAllViews();
            text(totalsDetails, getString(R.string.role_person_unavailable), 13, true);
        });
    }

    private String unknown() { return getString(R.string.role_unknown_data); }
    private String gauge(int active, int known, int total) {
        if (total > 0 && known == 0) return unknown();
        return known < total ? getString(R.string.dom_stats_confirmed_count, active) : number(active);
    }
    private static final class DomOverview {
        final DomStatsSnapshot stats;
        final HomeAchievementPreview achievements;
        DomOverview(DomStatsSnapshot stats, HomeAchievementPreview achievements) {
            this.stats = stats; this.achievements = achievements;
        }
    }

    private void bindCurrent(StatsRepository repository, StatsSnapshot stats) {
        currentSession.removeAllViews();
        boolean active = repository.observedSessionActive();
        currentSession.setVisibility(active ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!active) return;
        heading(currentSession, R.string.profile_stats_current_session);
        row(currentSession, R.string.stats_time, duration(stats.getCurrentSessionSeconds()));
        row(currentSession, R.string.stats_censors, number(stats.getCurrentSessionBlocks()));
        row(currentSession, R.string.stats_limited_app_time, duration(stats.getCurrentSessionLimitedAppMillis() / 1000));
        row(currentSession, R.string.stats_limit_stops, number(stats.getCurrentSessionLimitInterventions()));
        row(currentSession, R.string.stats_tributes, number(stats.getCurrentSessionTributeEvents()));
        for (String currency : new String[] {"EUR", "USD"}) {
            row(currentSession, R.string.stats_assessed,
                    WalletCurrency.format(currency, repository.tributeCents(currency, true)) + " " + currency);
        }
    }

    private void bindRemoteTotals() {
        org.json.JSONObject stats = remote.summary();
        achievements.setVisibility(android.view.View.GONE);
        lifetimeTime.setText(remoteMetric(stats,"protectedSeconds",true));
        lifetimeCensors.setText(remoteMetric(stats,"censors",false));
        lifetimeSessions.setText(remoteMetric(stats,"sessions",false));
        totalsDetails.removeAllViews(); heading(totalsDetails,R.string.profile_stats_feature_totals);
        try {
            com.subhub.app.people.PersonSnapshot person = remote.person();
            text(totalsDetails,getString(R.string.role_last_synced,person.lastSyncedAt <= 0 ? getString(R.string.role_unknown_data)
                    : DateFormat.getDateTimeInstance().format(new Date(person.lastSyncedAt))),13,true);
            if (person.sync.stats != com.subhub.app.people.PersonSnapshot.Freshness.CURRENT)
                text(totalsDetails,getString(R.string.role_stale_data),13,true);
            currentSession.removeAllViews();currentSession.setVisibility(person.serviceActive?android.view.View.VISIBLE:android.view.View.GONE);
            if(person.serviceActive){heading(currentSession,R.string.profile_stats_current_session);
                row(currentSession,R.string.stats_time,remoteMetric(stats,"currentSeconds",true));
                row(currentSession,R.string.stats_censors,remoteMetric(stats,"currentCensors",false));
                row(currentSession,R.string.stats_limit_stops,remoteMetric(stats,"currentLimitStops",false));
                row(currentSession,R.string.stats_tributes,remoteMetric(stats,"currentTributes",false));}
        } catch (Exception unavailable) { currentSession.setVisibility(android.view.View.GONE); }
        row(totalsDetails,R.string.statistics_longest,getString(R.string.role_unknown_data));
        row(totalsDetails,R.string.statistics_current_streak,getString(R.string.role_unknown_data));
        row(totalsDetails,R.string.stats_limited_app_time,stats==null?getString(R.string.role_unknown_data):duration(stats.optLong("limitMillis")/1000));
        row(totalsDetails,R.string.stats_limit_stops,remoteMetric(stats,"limitStops",false));
        row(totalsDetails,R.string.stats_whispers,remoteMetric(stats,"whispers",false));
        row(totalsDetails,R.string.stats_popups,remoteMetric(stats,"popups",false));
        row(totalsDetails,R.string.stats_tributes,remoteMetric(stats,"tributes",false));
        for(String currency:new String[]{"EUR","USD"}){
            text(totalsDetails,currency,13,true).setTypeface(null,Typeface.BOLD);
            row(totalsDetails,R.string.stats_assessed,stats==null?getString(R.string.role_unknown_data):WalletCurrency.format(currency,stats.optLong("assessed"+currency)));
            row(totalsDetails,R.string.stats_paid,stats==null?getString(R.string.role_unknown_data):WalletCurrency.format(currency,stats.optLong("paid"+currency)));
        }
    }
    private String remoteMetric(org.json.JSONObject stats,String key,boolean time) {
        if(stats==null||!stats.has(key))return getString(R.string.role_unknown_data);
        return time?duration(stats.optLong(key)):number(stats.optLong(key));
    }

    private void bindAllowances() {
        if (domOverview) { allowances.setVisibility(android.view.View.GONE); return; }
        if (remote != null) {
            data.load("stats-app-allowances",remote::person,person->{allowances.removeAllViews();com.subhub.app.people.PersonUi.allowances(allowances,person);},failure->{
                allowances.removeAllViews();text(allowances,getString(R.string.role_unknown_data),13,true);
            });
            return;
        }
        android.content.Context app = getApplicationContext();
        com.subhub.app.people.PersonRepository repository = com.subhub.app.people.People.repository(app);
        data.load("stats-app-allowances", () -> repository.load(com.subhub.app.people.PersonId.LOCAL), person -> {
            allowances.removeAllViews();
            com.subhub.app.people.PersonUi.allowances(allowances, person);
        }, failure -> {
            allowances.removeAllViews();
            text(allowances, getString(R.string.role_unknown_data), 13, true);
        });
    }

    private void bindSessions() {
        sessionList.removeAllViews();
        heading(sessionList, R.string.stats_recent_sessions);
        if (remote != null || domOverview) { text(sessionList,getString(R.string.stats_remote_history_private),13,true);return; }
        if (localFacts == null) { text(sessionList, getString(R.string.role_loading), 13, true); return; }
        List<StatsRepository.SessionEntry> entries = new ArrayList<>(localFacts.history.sessions());
        Collections.reverse(entries);
        entries.removeIf(
                e ->
                        e.getDurationSeconds() == 0
                                && e.getActivityEvents() == 0
                                && e.getLimitedAppMillis() == 0);
        if (entries.isEmpty()) {
            text(sessionList, getString(R.string.statistics_no_history), 13, true);
            return;
        }
        for (int i = 0; i < Math.min(sessionLimit, entries.size()); i++)
            session(sessionList, entries.get(i));
        if (entries.size() > 3) {
            Button more =
                    button(
                            sessionList,
                            getString(
                                    sessionLimit >= entries.size()
                                            ? R.string.stats_fewer_sessions
                                            : R.string.stats_more_sessions),
                            () -> {
                                sessionLimit =
                                        sessionLimit >= entries.size()
                                                ? 3
                                                : Math.min(entries.size(), sessionLimit + 3);
                                bindSessions();
                            });
            more.setId(R.id.stats_sessions_more);
        }
    }

    private void session(LinearLayout parent, StatsRepository.SessionEntry entry) {
        LinearLayout record = new LinearLayout(this);
        record.setOrientation(LinearLayout.VERTICAL);
        record.setPadding(0, dp(6), 0, dp(6));
        record.setTag("stats:session");
        parent.addView(record, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        record.addView(header, new LinearLayout.LayoutParams(-1, -2));
        TextView date =
                text(
                        header,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(new Date(entry.getStartMillis())),
                        14,
                        false);
        date.setTypeface(null, Typeface.BOLD);
        date.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        TextView time = text(header, duration(entry.getDurationSeconds()), 14, true);
        time.setGravity(Gravity.END);
        time.setPadding(dp(8), dp(5), 0, dp(7));
        time.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        List<String> brief = new ArrayList<>();
        if (entry.getBlocks() > 0)
            brief.add(getString(R.string.stats_history_censors, entry.getBlocks()));
        if (entry.getSubliminals() > 0)
            brief.add(getString(R.string.stats_history_whispers, entry.getSubliminals()));
        if (entry.getLimitInterventions() > 0)
            brief.add(getString(R.string.stats_history_stops, entry.getLimitInterventions()));
        if (entry.getTributeEvents() > 0)
            brief.add(getString(R.string.stats_history_tributes, entry.getTributeEvents()));
        if (entry.getPopupImpressions() > 0)
            brief.add(getString(R.string.stats_history_popups, entry.getPopupImpressions()));
        if (!brief.isEmpty()) text(record, String.join(" · ", brief), 12, true);
        boolean hasDetails = entry.getLimitedAppMillis() > 0 || entry.getTributeCents() > 0;
        if (hasDetails) {
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            record.addView(details);
            if (entry.getLimitedAppMillis() > 0)
                row(
                        details,
                        R.string.stats_limited_app_time,
                        duration(entry.getLimitedAppMillis() / 1000));
            if (entry.getTributeCents() > 0)
                row(
                        details,
                        R.string.stats_assessed,
                        WalletCurrency.format("EUR", entry.getTributeCents()));
        }
    }

    private void heading(LinearLayout parent, int title) {
        text(parent, getString(title), 16, false).setTypeface(null, Typeface.BOLD);
    }

    private LinearLayout row(LinearLayout parent, int label, String value) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
        TextView name = text(line, getString(label), 13, true);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = text(line, value, 15, false);
        amount.setGravity(Gravity.END);
        amount.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return line;
    }

    private String duration(long seconds) {
        return seconds > 0 && seconds < 60
                ? getString(R.string.stats_seconds_value, seconds)
                : StatsSnapshot.formatDuration(seconds);
    }

    private String number(long value) {
        return java.text.NumberFormat.getIntegerInstance().format(value);
    }
}
