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
    public AchievementPreviewView(android.content.Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_home_composition_stat);
        int padding = PersonUi.dp(context, 14);
        setPadding(padding, padding, padding, padding);
        setMinimumHeight(PersonUi.dp(context, 48));
        setFocusable(true);
        setClickable(true);
        setOnClickListener(view -> context.startActivity(new Intent(context, AchievementsActivity.class)));
        setTag("achievement_preview");
    }

    public void load(AsyncUiScope data, String key, StatsSnapshot stats) {
        android.content.Context app = getContext().getApplicationContext();
        data.load(key, () -> HomeAchievementPreview.load(app, stats), this::render, failure -> {
            removeAllViews();
            PersonUi.text(this, getContext().getString(R.string.achievements_title), true);
        });
    }

    private void render(HomeAchievementPreview preview) {
        removeAllViews();
        PersonUi.text(this, getContext().getString(R.string.achievements_title), true);
        PersonUi.text(this, getContext().getString(R.string.achievements_progress_compact,
                preview.achievements.getUnlockedCount(), preview.achievements.getTotalCount()), false);
        LinearLayout badges = new LinearLayout(getContext());
        badges.setGravity(Gravity.CENTER_VERTICAL);
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
        if (preview.next == null) PersonUi.text(this, getContext().getString(R.string.achievements_all_complete), false);
        else PersonUi.text(this, getContext().getString(R.string.achievements_next_fmt,
                getContext().getString(preview.next.getName()), preview.progress.getCurrent() + " / "
                        + preview.progress.getTarget()), false);
        ProgressBar progress = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(preview.next == null ? 100 : preview.progress.percent());
        progress.setProgressTintList(ColorStateList.valueOf(getContext().getColor(R.color.accent_text)));
        addView(progress, new LayoutParams(-1, PersonUi.dp(getContext(), 6)));
    }
}
