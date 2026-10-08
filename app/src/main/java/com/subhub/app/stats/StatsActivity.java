package com.subhub.app.stats;

import com.subhub.app.util.PrimaryHeader;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.databinding.ActivityStatsBinding;
import com.subhub.app.penance.PenanceManager;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Full local statistics, trends, and milestones. */
public final class StatsActivity extends AppCompatActivity {
    private ActivityStatsBinding binding;
    private DailyStatsPanel daily;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStatsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        daily = new DailyStatsPanel(this, false); daily.restore(savedInstanceState);
        android.widget.LinearLayout content = (android.widget.LinearLayout) ((android.widget.ScrollView) binding.getRoot()).getChildAt(0);
        android.widget.LinearLayout.LayoutParams dailyParams = new android.widget.LinearLayout.LayoutParams(-1,-2); dailyParams.topMargin = dp(14);
        content.addView(daily, 1, dailyParams);
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.statistics_title, false);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        daily.refresh();
    }

    private void render() {
        StatsRepository repository = new StatsRepository(this);
        StatsSnapshot stats = repository.load();
        binding.totalBlocks.setText(String.valueOf(stats.getActivityEvents()));
        binding.totalTime.setText(StatsSnapshot.formatDuration(stats.getTotalProtectedSeconds()));
        binding.totalSessions.setText(String.valueOf(stats.getSessions()));
        binding.detailStats.setText(featureTotals(stats));

        int next = MilestoneManager.next(stats.getTotalBlocks());
        binding.milestoneLabel.setText(getString(
                R.string.milestone_next, next, Math.min(stats.getTotalBlocks(), next)));
        binding.milestoneProgress.setMax(next);
        binding.milestoneProgress.setProgress((int) Math.min(stats.getTotalBlocks(), next));

        List<StatsRepository.SessionEntry> history = repository.getSessionHistory();
        binding.sessionTrend.setEntries(history);
        binding.historyList.removeAllViews();
        List<StatsRepository.SessionEntry> reverse = new ArrayList<>(history);
        Collections.reverse(reverse);
        for (int index = 0; index < reverse.size(); index++) {
            StatsRepository.SessionEntry entry = reverse.get(index);
            TextView row = new TextView(this);
            row.setText(getString(R.string.statistics_session_row,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(new Date(entry.getStartMillis())),
                    StatsSnapshot.formatDuration(entry.getDurationSeconds()),
                    sessionSummary(entry)));
            row.setTextColor(getColor(R.color.text_secondary));
            row.setTextSize(11);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(42));
            binding.historyList.addView(row);
        }
        binding.historyEmpty.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override protected void onSaveInstanceState(Bundle state) { daily.save(state); super.onSaveInstanceState(state); }

    @Override protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private String featureTotals(StatsSnapshot stats) {
        List<String> lines = new ArrayList<>();
        lines.add(getString(R.string.statistics_current_streak) + ": "
                + stats.getCurrentStreak() + " days");
        lines.add(getString(R.string.statistics_longest) + ": "
                + StatsSnapshot.formatDuration(stats.getLongestSessionSeconds()));
        lines.add(getString(R.string.stats_censors) + ": " + stats.getTotalBlocks());
        lines.add(getString(R.string.stats_limited_app_time) + ": "
                + StatsSnapshot.formatDuration(stats.getLimitedAppMillis() / 1_000L));
        lines.add(getString(R.string.stats_limit_stops) + ": "
                + stats.getLimitInterventions());
        lines.add(getString(R.string.stats_tributes) + ": " + stats.getTributeEvents());
        for (String currency : new String[]{"EUR","USD"}) {
            lines.add(getString(currency.equals("EUR") ? R.string.daily_assessed_eur : R.string.daily_assessed_usd) + ": "
                    + com.subhub.app.penance.WalletCurrency.format(currency,new StatsRepository(this).tributeCents(currency,false)));
            lines.add(getString(currency.equals("EUR") ? R.string.daily_paid_eur : R.string.daily_paid_usd) + ": "
                    + com.subhub.app.penance.WalletCurrency.format(currency,new PenanceManager(this).getTotalPaidCents(currency)));
        }
        lines.add(getString(R.string.stats_whispers) + ": "
                + stats.getSubliminalImpressions());
        lines.add(getString(R.string.stats_popups) + ": " + stats.getPopupImpressions());
        lines.add(getString(R.string.statistics_active_days) + ": "
                + stats.getActiveDates().size());
        return String.join("\n", lines);
    }

    private String sessionSummary(StatsRepository.SessionEntry entry) {
        List<String> values = new ArrayList<>();
        if (entry.getBlocks() > 0) values.add(getString(
                R.string.stats_history_censors, entry.getBlocks()));
        if (entry.getLimitedAppMillis() > 0) values.add(getString(
                R.string.stats_history_limited,
                StatsSnapshot.formatDuration(entry.getLimitedAppMillis() / 1_000L)));
        if (entry.getLimitInterventions() > 0) values.add(getString(
                R.string.stats_history_stops, entry.getLimitInterventions()));
        if (entry.getTributeEvents() > 0) values.add(getString(
                R.string.stats_history_tributes, entry.getTributeEvents()));
        if (entry.getSubliminals() > 0) values.add(getString(
                R.string.stats_history_whispers, entry.getSubliminals()));
        if (entry.getPopupImpressions() > 0) values.add(getString(
                R.string.stats_history_popups, entry.getPopupImpressions()));
        return values.isEmpty() ? getString(R.string.stats_history_no_activity)
                : String.join(" · ", values);
    }
}
