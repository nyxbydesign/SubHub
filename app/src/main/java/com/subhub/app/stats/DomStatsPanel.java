package com.subhub.app.stats;

import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import com.subhub.app.penance.WalletCurrency;
import com.subhub.app.util.AsyncUiScope;
import java.text.NumberFormat;

/** A page-owned projection refreshes current gauges without accumulating receipts. */
final class DomStatsPanel extends LinearLayout implements DefaultLifecycleObserver {
    private final AppCompatActivity activity;
    private final AsyncUiScope data;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::load;
    private boolean started;
    private String rendered;

    DomStatsPanel(AppCompatActivity activity) {
        super(activity);
        this.activity = activity;
        data = AsyncUiScope.forPage(activity);
        setOrientation(VERTICAL);
        setSaveEnabled(false);
        setTag("dom_statistics");
        activity.getLifecycle().addObserver(this);
    }

    @Override public void onStart(LifecycleOwner owner) { started = true; load(); }
    @Override public void onStop(LifecycleOwner owner) {
        started = false;
        clock.removeCallbacks(refresh);
        data.cancel("dom-statistics");
        removeAllViews(); rendered = null;
    }

    private void load() {
        clock.removeCallbacks(refresh);
        if (!started) return;
        if (!AchievementRole.DOM.accessible(getContext())) {
            removeAllViews(); rendered = null; activity.finish(); return;
        }
        android.content.Context app = getContext().getApplicationContext();
        data.load("dom-statistics", () -> {
            DomStatsSnapshot stats = new DomStatsRepository(app).load();
            return new Loaded(stats, HomeAchievementPreview.loadDom(app, stats));
        }, loaded -> {
            if (!started || !AchievementRole.DOM.accessible(app)) { removeAllViews(); return; }
            render(loaded);
        }, failure -> {
            removeAllViews(); rendered = null;
            if (started && AchievementRole.DOM.accessible(app))
                PersonUi.text(this, getContext().getString(R.string.role_person_unavailable), false);
        });
        clock.postDelayed(refresh, 5_000);
    }

    private void render(Loaded loaded) {
        DomStatsSnapshot value = loaded.stats;
        String signature = value.subs + ":" + value.totalsKnown + ":" + value.totalsFresh + ":"
                + value.walletKnown + ":" + value.sessionSeconds + ":" + value.sessions + ":"
                + value.activeKnown + ":" + value.activeSubs + ":" + value.hardcoreKnown + ":"
                + value.hardcoreSubs + ":" + value.paidCents + ":" + value.assessedCents
                + ":" + loaded.achievements.fingerprint;
        if (signature.equals(rendered)) return;
        rendered = signature;
        removeAllViews();
        LinearLayout totals = card(R.string.dom_category_subs);
        metric(totals, "dom_stats_paired", R.string.dom_stats_paired, number(value.subs));
        metric(totals, "dom_stats_time", R.string.dom_stats_time,
                value.hasTotals() ? StatsSnapshot.formatDuration(value.sessionSeconds) : unknown());
        metric(totals, "dom_stats_sessions", R.string.dom_stats_sessions,
                value.hasTotals() ? number(value.sessions) : unknown());
        if (value.totalsKnown < value.subs) note(totals, R.string.dom_stats_totals_coverage, value.totalsKnown, value.subs);
        if (value.totalsFresh < value.totalsKnown)
            note(totals, R.string.dom_stats_stale_totals, value.totalsKnown - value.totalsFresh);

        LinearLayout current = card(R.string.dom_stats_current);
        metric(current, "dom_stats_active", R.string.dom_stats_active,
                gauge(value.activeSubs, value.activeKnown, value.subs));
        metric(current, "dom_stats_hardcore", R.string.dom_stats_hardcore,
                gauge(value.hardcoreSubs, value.hardcoreKnown, value.subs));
        if (value.activeKnown < value.subs) note(current, R.string.dom_stats_runtime_coverage, value.activeKnown, value.subs);
        if (value.hardcoreKnown < value.subs) note(current, R.string.dom_stats_hardcore_coverage, value.hardcoreKnown, value.subs);

        LinearLayout paid = card(R.string.dom_stats_tribute), assessed = card(R.string.dom_stats_assessed);
        for (String currency : value.paidCents.keySet()) {
            metric(paid, "dom_paid_" + currency, currency,
                    value.hasWalletTotals() ? WalletCurrency.format(currency, value.paidCents.get(currency)) : unknown());
            metric(assessed, "dom_assessed_" + currency, currency,
                    value.hasWalletTotals() ? WalletCurrency.format(currency, value.assessedCents.get(currency)) : unknown());
        }
        if (value.walletKnown < value.subs) note(assessed, R.string.dom_stats_wallet_coverage, value.walletKnown, value.subs);
        PersonUi.text(this, getContext().getString(R.string.dom_stats_scope), false);
        AchievementPreviewView achievements = new AchievementPreviewView(getContext());
        addView(achievements, new LayoutParams(-1, -2));
        achievements.render(loaded.achievements);
    }

    private LinearLayout card(int title) {
        LinearLayout card = PersonUi.card(this);
        PersonUi.text(card, getContext().getString(title), true);
        return card;
    }
    private void metric(LinearLayout parent, String tag, int label, String value) {
        metric(parent, tag, getContext().getString(label), value);
    }
    private void metric(LinearLayout parent, String tag, String label, String value) {
        LinearLayout row = PersonUi.row(parent, 0, label, value, null);
        row.setTag(tag);
    }
    private void note(LinearLayout target, int label, Object... values) {
        PersonUi.text(target, getContext().getString(label, values), false);
    }
    private String number(long value) { return NumberFormat.getIntegerInstance().format(value); }
    private String unknown() { return getContext().getString(R.string.role_unknown_data); }
    private String gauge(int active, int known, int total) {
        if (total > 0 && known == 0) return unknown();
        return known < total ? getContext().getString(R.string.dom_stats_confirmed_count, active) : number(active);
    }
    private static final class Loaded {
        final DomStatsSnapshot stats;
        final HomeAchievementPreview achievements;
        Loaded(DomStatsSnapshot stats, HomeAchievementPreview achievements) {
            this.stats = stats; this.achievements = achievements;
        }
    }
}
