package com.subhub.app.stats;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import com.subhub.app.util.AsyncUiScope;

/** Compact achievement progress shared by the overview and local statistics. */
public final class AchievementPreviewView extends LinearLayout {
    private java.util.List<Object> rendered;
    private final boolean compact;
    public AchievementPreviewView(android.content.Context context) {
        this(context, false);
    }

    public AchievementPreviewView(android.content.Context context, boolean compact) {
        super(context);
        this.compact = compact;
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_home_composition_stat);
        int padding = PersonUi.dp(context, 14);
        setPadding(padding, compact ? PersonUi.dp(context, 4) : padding, padding, compact ? PersonUi.dp(context, 4) : padding);
        if (!compact) com.subhub.app.util.UiIdentity.cardPadding(this);
        if (compact) setBackgroundResource(R.drawable.bg_overview_card);
        setMinimumHeight(PersonUi.dp(context, 48));
        setFocusable(true);
        setClickable(true);
        setOnClickListener(view -> context.startActivity(new Intent(context, AchievementsActivity.class)));
        setTag("achievement_preview");
    }

    public void load(AsyncUiScope data, String key, StatsSnapshot stats) {
        android.content.Context app = getContext().getApplicationContext();
        data.load(key, () -> HomeAchievementPreview.load(app, stats), this::render, failure -> {
            rendered = null;
            removeAllViews();
            com.subhub.app.util.UiIdentity.sectionIcon(
                PersonUi.text(this, getContext().getString(R.string.achievements_title), true),
                R.drawable.ic_overview_achievement);
        });
    }

    public void load(AsyncUiScope data, String key) { load(data, key, null); }

    void render(HomeAchievementPreview preview) {
        if (!preview.achievements.isAccessible()) { rendered = null; removeAllViews(); return; }
        java.util.List<Object> signature = new java.util.ArrayList<>();
        java.util.Collections.addAll(signature, preview.fingerprint, preview.achievements.getTotalCount(),
                preview.achievements.progressCurrency(), preview.next == null || preview.progress.isKnown());
        for (AchievementManager.Achievement achievement : preview.achievements.all())
            java.util.Collections.addAll(signature, achievement.getId(), preview.achievements.isUnlocked(achievement.getId()),
                    preview.achievements.getUnlockedAt(achievement.getId()));
        if (signature.equals(rendered) && getChildCount() > 0) return;
        rendered = signature;
        removeAllViews();
        if (compact) {
            com.subhub.app.people.OverviewUi.row(this, R.drawable.ic_overview_achievement,
                    getContext().getString(R.string.achievements_title),
                    getContext().getString(R.string.overview_achievements_count,
                            preview.achievements.getUnlockedCount(), preview.achievements.getTotalCount()),
                    () -> getContext().startActivity(new Intent(getContext(), AchievementsActivity.class)));
            recentBadges(preview.achievements);
            nextAchievement(preview);
            return;
        }
        com.subhub.app.util.UiIdentity.sectionIcon(
                PersonUi.text(this, getContext().getString(R.string.achievements_title), true),
                R.drawable.ic_overview_achievement);
        PersonUi.text(this, getContext().getString(R.string.achievements_progress_compact,
                preview.achievements.getUnlockedCount(), preview.achievements.getTotalCount()), false);
        LinearLayout badges = new LinearLayout(getContext());
        badges.setGravity(Gravity.TOP);
        addView(badges, new LayoutParams(-1, -2));
        java.util.List<AchievementManager.Achievement> visible = new java.util.ArrayList<>();
        for (AchievementManager.Achievement achievement : preview.achievements.all())
            if (!achievement.isHidden() || preview.achievements.isUnlocked(achievement.getId())) visible.add(achievement);
        visible.sort((left, right) -> Boolean.compare(
                preview.achievements.isUnlocked(right.getId()), preview.achievements.isUnlocked(left.getId())));
        int shown = 0;
        for (AchievementManager.Achievement achievement : visible) {
            AchievementBadgeView badge = new AchievementBadgeView(getContext());
            badge.bind(achievement.getBadgeArtRes(), preview.achievements.isUnlocked(achievement.getId()),
                    false, getContext().getString(achievement.getName()));
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            badges.addView(cell, new LayoutParams(0, -2, 1));
            cell.addView(badge, new LayoutParams(PersonUi.dp(getContext(), 56), PersonUi.dp(getContext(), 56)));
            TextView label = PersonUi.text(cell, getContext().getString(achievement.getName()), false);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(11);
            if (++shown == 3) break;
        }
        nextAchievement(preview);
    }

    private void nextAchievement(HomeAchievementPreview preview) {
        TextView next = PersonUi.text(this, preview.next == null
                ? getContext().getString(R.string.achievements_all_complete)
                : getContext().getString(R.string.achievements_next_fmt,
                        getContext().getString(preview.next.getName()), formatProgress(preview)), false);
        next.setTag("achievement_next");
        ProgressBar progress = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        if (preview.next != null && !preview.progress.isKnown()) progress.setVisibility(GONE);
        progress.setProgress(preview.next == null ? 100 : preview.progress.percent());
        progress.setProgressTintList(ColorStateList.valueOf(getContext().getColor(R.color.accent_text)));
        addView(progress, new LayoutParams(-1, PersonUi.dp(getContext(), 6)));
    }

    private void recentBadges(AchievementManager achievements) {
        java.util.List<AchievementManager.Achievement> unlocked = new java.util.ArrayList<>();
        for (AchievementManager.Achievement achievement : achievements.all())
            if (achievements.isUnlocked(achievement.getId())) unlocked.add(achievement);
        unlocked.sort(java.util.Comparator.comparingLong(
                (AchievementManager.Achievement achievement) -> achievements.getUnlockedAt(achievement.getId())).reversed());
        if (unlocked.isEmpty()) return;
        LinearLayout badges = new LinearLayout(getContext());
        badges.setGravity(Gravity.TOP);
        badges.setTag("achievement_recent_badges");
        LayoutParams size = new LayoutParams(-1, -2); size.bottomMargin = PersonUi.dp(getContext(), 6);
        addView(badges, size);
        for (AchievementManager.Achievement achievement : unlocked.subList(0, Math.min(3, unlocked.size()))) {
            LinearLayout cell = new LinearLayout(getContext()); cell.setOrientation(VERTICAL); cell.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            badges.addView(cell, new LayoutParams(0, -2, 1));
            AchievementBadgeView badge = new AchievementBadgeView(getContext());
            badge.bind(achievement.getBadgeArtRes(), true, false, getContext().getString(achievement.getName()));
            badge.setTag("achievement_recent:" + achievement.getId());
            cell.addView(badge, new LayoutParams(PersonUi.dp(getContext(), 56), PersonUi.dp(getContext(), 56)));
            TextView title = PersonUi.text(cell, getContext().getString(achievement.getName()), false);
            title.setTextSize(11); title.setGravity(Gravity.CENTER);
            title.setTag("achievement_recent_title:" + achievement.getId());
        }
    }

    private String formatProgress(HomeAchievementPreview preview) {
        if (!preview.progress.isKnown()) return getContext().getString(R.string.dom_progress_unavailable);
        long current = preview.progress.getCurrent(), target = preview.progress.getTarget();
        if ("dom_tribute".equals(preview.next.getCategory()) || preview.next.getId().startsWith("wallet_paid_")) {
            String currency = preview.achievements.progressCurrency();
            return com.subhub.app.penance.WalletCurrency.format(currency, current) + " / "
                    + com.subhub.app.penance.WalletCurrency.format(currency, target);
        }
        if ("dom_time".equals(preview.next.getCategory()))
            return StatsSnapshot.formatDuration(current) + " / " + StatsSnapshot.formatDuration(target);
        return java.text.NumberFormat.getIntegerInstance().format(current) + " / "
                + java.text.NumberFormat.getIntegerInstance().format(target);
    }
}
