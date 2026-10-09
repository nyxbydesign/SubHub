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

/** One compact page; detailed totals and additional sessions expand in place. */
public final class StatsActivity extends PreferencePage {
    private DailyStatsPanel daily;
    private LinearLayout totalsDetails, sessionList;
    private TextView lifetimeTime, lifetimeCensors, lifetimeSessions;
    private Button totalsToggle;
    private boolean expandedTotals, firstResume = true;
    private int sessionLimit = 3;
    private final Set<Long> expandedSessions = new HashSet<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            expandedTotals = state.getBoolean("stats_totals_expanded");
            sessionLimit = Math.max(3, Math.min(30, state.getInt("stats_session_limit", 3)));
        }
        if (state != null) {
            long[] expanded = state.getLongArray("stats_expanded_sessions");
            if (expanded != null) for (long start : expanded) expandedSessions.add(start);
        }
        page(R.string.statistics_title);
        LinearLayout summary = card(page);
        summary.setId(R.id.stats_lifetime_summary);
        heading(summary, R.string.stats_all_time);
        LinearLayout numbers = new LinearLayout(this);
        summary.addView(numbers, new LinearLayout.LayoutParams(-1, -2));
        lifetimeTime = metric(numbers, R.string.stats_time);
        lifetimeCensors = metric(numbers, R.string.stats_censors);
        lifetimeSessions = metric(numbers, R.string.stats_sessions);
        totalsDetails = new LinearLayout(this);
        totalsDetails.setOrientation(LinearLayout.VERTICAL);
        totalsDetails.setId(R.id.stats_totals_details);
        summary.addView(totalsDetails);
        totalsToggle =
                button(
                        summary,
                        getString(R.string.stats_more_totals),
                        () -> {
                            expandedTotals = !expandedTotals;
                            bindTotals();
                        });
        totalsToggle.setId(R.id.stats_totals_toggle);
        daily = new DailyStatsPanel(this, false, state);
        page.addView(daily, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams activityParams =
                (LinearLayout.LayoutParams) daily.getLayoutParams();
        activityParams.bottomMargin = dp(14);
        sessionList = card(page);
        bindTotals();
        bindSessions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
            return;
        }
        bindTotals();
        bindSessions();
        daily.refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        daily.save(state);
        state.putBoolean("stats_totals_expanded", expandedTotals);
        state.putInt("stats_session_limit", sessionLimit);
        long[] expanded = new long[expandedSessions.size()];
        int index = 0;
        for (Long start : expandedSessions) expanded[index++] = start;
        state.putLongArray("stats_expanded_sessions", expanded);
        super.onSaveInstanceState(state);
    }

    private TextView metric(LinearLayout parent, int name) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        parent.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(cell, "", 22, false);
        value.setTypeface(null, Typeface.BOLD);
        text(cell, getString(name), 12, true);
        return value;
    }

    private void bindTotals() {
        StatsRepository repository = new StatsRepository(this);
        StatsSnapshot stats = repository.load();
        lifetimeTime.setText(duration(stats.getTotalProtectedSeconds()));
        lifetimeCensors.setText(number(stats.getTotalBlocks()));
        lifetimeSessions.setText(number(stats.getSessions()));
        totalsToggle.setText(
                expandedTotals ? R.string.stats_fewer_totals : R.string.stats_more_totals);
        totalsDetails.removeAllViews();
        totalsDetails.setVisibility(
                expandedTotals ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!expandedTotals) return;
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
        PenanceManager wallet = new PenanceManager(this);
        for (String currency : new String[] {"EUR", "USD"}) {
            text(totalsDetails, currency, 13, true).setTypeface(null, Typeface.BOLD);
            row(
                    totalsDetails,
                    R.string.stats_assessed,
                    WalletCurrency.format(currency, repository.tributeCents(currency, false)));
            row(
                    totalsDetails,
                    R.string.stats_paid,
                    WalletCurrency.format(currency, wallet.getTotalPaidCents(currency)));
        }
    }

    private void bindSessions() {
        sessionList.removeAllViews();
        heading(sessionList, R.string.stats_recent_sessions);
        List<StatsRepository.SessionEntry> entries =
                new ArrayList<>(new StatsRepository(this).getSessionHistory());
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
            details.setVisibility(
                    expandedSessions.contains(entry.getStartMillis())
                            ? android.view.View.VISIBLE
                            : android.view.View.GONE);
            record.setMinimumHeight(dp(48));
            record.setFocusable(true);
            android.util.TypedValue feedback = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, feedback, true);
            record.setBackgroundResource(feedback.resourceId);
            record.setOnClickListener(
                    v -> {
                        boolean show = details.getVisibility() != android.view.View.VISIBLE;
                        details.setVisibility(
                                show ? android.view.View.VISIBLE : android.view.View.GONE);
                        if (show) expandedSessions.add(entry.getStartMillis());
                        else expandedSessions.remove(entry.getStartMillis());
                    });
        }
    }

    private void heading(LinearLayout parent, int title) {
        text(parent, getString(title), 16, false).setTypeface(null, Typeface.BOLD);
    }

    private void row(LinearLayout parent, int label, String value) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
        TextView name = text(line, getString(label), 13, true);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = text(line, value, 15, false);
        amount.setGravity(Gravity.END);
        amount.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
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
