package com.subhub.app.stats;

import com.subhub.app.util.PrimaryHeader;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.subhub.app.R;
import com.subhub.app.databinding.ActivityAchievementsBinding;
import com.subhub.app.util.PremiumMotion;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Illustrated, grouped achievement catalog for both recovered and SubHub-native milestones. */
public final class AchievementsActivity extends AppCompatActivity {
    private static final String[] CATEGORY_ORDER = {
            "sessions", "time", "streaks", "app_mode", "limits", "pact", "hardcore",
            "blocks", "peaks", "censor", "appearance", "phrases", "packs", "custom",
            "export", "subliminal", "wallet_setup", "wallet_payments", "wallet",
            "control", "hidden"
    };
    private ActivityAchievementsBinding binding;
    private AchievementManager manager;
    private StatsSnapshot stats;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAchievementsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.achievements_title, false);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
    }

    @Override protected void onResume() {
        super.onResume();
        manager = new AchievementManager(this);
        stats = new StatsRepository(this).load();
        manager.checkAchievements(stats);
        manager.takePendingNotifications();
        rebuild();
    }

    private void rebuild() {
        binding.achievementList.removeAllViews();
        binding.achievementBadgeStrip.removeAllViews();
        binding.achievementProgress.setText(getString(R.string.achievements_progress_compact,
                manager.getUnlockedCount(), manager.getTotalCount()));
        binding.achievementOverallProgress.setProgress(Math.round(
                manager.getUnlockedCount() * 100f / Math.max(1, manager.getTotalCount())));
        addShowcaseBadges();
        renderNextMilestone();

        for (Map.Entry<String, List<AchievementManager.Achievement>> entry
                : orderedGroups(manager.all()).entrySet()) {
            addCategoryHeader(entry.getKey());
            for (AchievementManager.Achievement achievement : entry.getValue()) addRow(achievement);
        }
    }

    /** Keep feature milestones ahead of completion, including newly added categories. */
    static Map<String, List<AchievementManager.Achievement>> orderedGroups(
            List<AchievementManager.Achievement> achievements) {
        Map<String, List<AchievementManager.Achievement>> remaining = new LinkedHashMap<>();
        for (AchievementManager.Achievement achievement : achievements) {
            remaining.computeIfAbsent(displayCategory(achievement), category -> new ArrayList<>())
                    .add(achievement);
        }
        for (Map.Entry<String, List<AchievementManager.Achievement>> entry : remaining.entrySet()) {
            if (isTieredCategory(entry.getKey())) {
                // List.sort is stable: equal targets retain their deliberate catalog order.
                // Compare real requirements, not legacy numeric suffixes or unlock state.
                entry.getValue().sort(Comparator.comparingLong(
                        achievement -> {
                            long target = AchievementManager.target(achievement.getId());
                            return target > 0 ? target : Long.MAX_VALUE;
                        }));
            }
        }
        // Reserve the terminal group before appending uncatalogued feature groups.
        List<AchievementManager.Achievement> completion = remaining.remove("special");
        Map<String, List<AchievementManager.Achievement>> ordered = new LinkedHashMap<>();
        for (String category : CATEGORY_ORDER) {
            List<AchievementManager.Achievement> group = remaining.remove(category);
            if (group != null) ordered.put(category, group);
        }
        ordered.putAll(remaining);
        if (completion != null) {
            // Future special milestones must not follow the all-achievements milestone.
            List<AchievementManager.Achievement> terminal = new ArrayList<>();
            for (AchievementManager.Achievement achievement : completion) {
                if (!"legend".equals(achievement.getId())) terminal.add(achievement);
            }
            for (AchievementManager.Achievement achievement : completion) {
                if ("legend".equals(achievement.getId())) terminal.add(achievement);
            }
            ordered.put("special", terminal);
        }
        return ordered;
    }

    static String displayCategory(AchievementManager.Achievement achievement) {
        switch (achievement.getId()) {
            case "color_picker": case "border_artist": case "style_explorer":
                return "appearance";
            case "first_custom_phrase": case "phrase_library": return "phrases";
            case "pack_curator": return "packs";
            case "wallet_keeper": case "tribute_rulesmith": case "paypal_vault":
            case "paid_pause": return "wallet_setup";
            case "wallet_paid_10": case "wallet_paid_100":
            case "wallet_paid_500": case "wallet_paid_1000": return "wallet_payments";
            default: return achievement.getCategory();
        }
    }

    private static boolean isTieredCategory(String category) {
        switch (category) {
            case "sessions": case "time": case "streaks": case "app_mode":
            case "blocks": case "peaks": case "censor": case "appearance":
            case "phrases": case "export": case "subliminal": case "wallet_payments":
                return true;
            default: return false;
        }
    }

    private void addShowcaseBadges() {
        List<AchievementManager.Achievement> values = representativeBadges(8);
        for (int index = 0; index < values.size(); index++) {
            AchievementManager.Achievement achievement = values.get(index);
            boolean unlocked = manager.isUnlocked(achievement.getId());
            boolean concealed = achievement.isHidden() && !unlocked;

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(dp(88),
                    LinearLayout.LayoutParams.MATCH_PARENT);
            if (index > 0) cellParams.leftMargin = dp(6);
            cell.setLayoutParams(cellParams);
            cell.setBackgroundResource(R.drawable.bg_achievement_preview_cell);
            cell.setClickable(true);
            cell.setFocusable(true);
            cell.setOnClickListener(view -> showAchievementDetails(achievement));

            AchievementBadgeView badge = badge(achievement, unlocked, concealed, 72);
            cell.addView(badge);
            TextView label = text(concealed ? getString(R.string.achievement_hidden_name)
                    : getString(achievement.getName()), 10,
                    unlocked ? R.color.text_primary : R.color.text_muted, unlocked);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(4);
            label.setLayoutParams(labelParams);
            cell.addView(label);
            binding.achievementBadgeStrip.addView(cell);
        }
    }

    private List<AchievementManager.Achievement> representativeBadges(int maximum) {
        List<AchievementManager.Achievement> selected = new ArrayList<>();
        Set<Integer> artwork = new LinkedHashSet<>();
        for (int pass = 0; pass < 2 && selected.size() < maximum; pass++) {
            boolean wantUnlocked = pass == 0;
            for (AchievementManager.Achievement achievement : manager.all()) {
                if (manager.isUnlocked(achievement.getId()) != wantUnlocked
                        || !artwork.add(achievement.getBadgeArtRes())) continue;
                selected.add(achievement);
                if (selected.size() == maximum) break;
            }
        }
        return selected;
    }

    private void renderNextMilestone() {
        AchievementManager.Achievement next = nextVisibleLocked();
        if (next == null) {
            binding.achievementNextName.setText(R.string.achievements_all_complete);
            binding.achievementNextProgress.setProgress(100);
            return;
        }
        AchievementManager.Progress progress = manager.progress(next, stats);
        binding.achievementNextName.setText(getString(R.string.achievements_next_fmt,
                getString(next.getName()), formatProgress(next, progress)));
        binding.achievementNextProgress.setProgress(progress.percent());
    }

    private AchievementManager.Achievement nextVisibleLocked() {
        for (List<AchievementManager.Achievement> group : orderedGroups(manager.all()).values()) {
            for (AchievementManager.Achievement value : group) {
                if (!manager.isUnlocked(value.getId()) && !value.isHidden()
                        && manager.progress(value, stats).isCountable()) return value;
            }
        }
        return null;
    }

    private void addCategoryHeader(String category) {
        TextView header = text(getString(categoryTitle(category)), 12,
                R.color.accent_mid, true);
        header.setAllCaps(true);
        header.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = binding.achievementList.getChildCount() == 0 ? dp(2) : dp(20);
        params.bottomMargin = dp(9);
        header.setLayoutParams(params);
        binding.achievementList.addView(header);
    }

    private void addRow(AchievementManager.Achievement achievement) {
        boolean unlocked = manager.isUnlocked(achievement.getId());
        boolean concealed = achievement.isHidden() && !unlocked;
        AchievementManager.Progress progress = manager.progress(achievement, stats);

        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(20));
        card.setCardElevation(dp(3));
        card.setCardBackgroundColor(getColor(unlocked
                ? R.color.surface_card_raised : R.color.surface_card));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(getColor(unlocked ? R.color.accent_hot : R.color.outline_subtle));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.addView(row);

        row.addView(badge(achievement, unlocked, concealed, 80));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        contentParams.leftMargin = dp(15);
        content.setLayoutParams(contentParams);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(concealed ? getString(R.string.achievement_hidden_name)
                : getString(achievement.getName()), 16, R.color.text_primary, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(title);
        if (unlocked) {
            TextView tag = text(getString(R.string.achievement_unlocked_tag), 9,
                    R.color.accent_hot, true);
            tag.setAllCaps(true);
            tag.setLetterSpacing(0.1f);
            tag.setBackgroundResource(R.drawable.bg_achievement_tag);
            tag.setGravity(Gravity.CENTER);
            tag.setPadding(dp(8), dp(3), dp(8), dp(3));
            LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tagParams.leftMargin = dp(8);
            tag.setLayoutParams(tagParams);
            titleRow.addView(tag);
        }
        content.addView(titleRow);

        TextView description = text(concealed ? getString(R.string.achievement_hidden_desc)
                : getString(achievement.getDescription()), 12, R.color.text_secondary, false);
        description.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descriptionParams.topMargin = dp(5);
        description.setLayoutParams(descriptionParams);
        content.addView(description);

        if (unlocked) {
            TextView date = text(getString(R.string.achievement_unlocked_on,
                    formatUnlockDate(manager.getUnlockedAt(achievement.getId()))), 10,
                    R.color.text_muted, false);
            LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            dateParams.topMargin = dp(6);
            date.setLayoutParams(dateParams);
            content.addView(date);
        }

        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams progressRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressRowParams.topMargin = dp(11);
        progressRow.setLayoutParams(progressRowParams);
        if (progress.isCountable() && !concealed) {
            ProgressBar bar = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(progress.percent());
            bar.setProgressTintList(ColorStateList.valueOf(getColor(
                    unlocked ? R.color.accent_hot : R.color.accent_mid)));
            bar.setProgressBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.outline_subtle)));
            bar.setLayoutParams(new LinearLayout.LayoutParams(0, dp(6), 1f));
            progressRow.addView(bar);
            TextView count = text(formatProgress(achievement, progress), 10,
                    unlocked ? R.color.accent_hot : R.color.text_muted, true);
            LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            countParams.leftMargin = dp(10);
            count.setLayoutParams(countParams);
            progressRow.addView(count);
        } else {
            TextView status = text(concealed ? getString(R.string.achievement_secret)
                    : getString(unlocked ? R.string.achievement_unlocked_tag
                    : R.string.achievement_locked), 9,
                    unlocked ? R.color.accent_hot : R.color.text_muted, true);
            progressRow.addView(status);
        }
        content.addView(progressRow);
        row.addView(content);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> showAchievementDetails(achievement));
        binding.achievementList.addView(card);
    }

    private void showAchievementDetails(AchievementManager.Achievement achievement) {
        boolean unlocked = manager.isUnlocked(achievement.getId());
        boolean concealed = achievement.isHidden() && !unlocked;
        AchievementManager.Progress progress = manager.progress(achievement, stats);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View content = LayoutInflater.from(this).inflate(
                R.layout.dialog_achievement_details, null, false);
        AchievementBadgeView badge = content.findViewById(R.id.achievement_detail_badge);
        CharSequence name = concealed ? getString(R.string.achievement_hidden_name)
                : getString(achievement.getName());
        badge.bind(achievement.getBadgeArtRes(), unlocked, concealed, name);
        ((TextView) content.findViewById(R.id.achievement_detail_status)).setText(
                concealed ? R.string.achievement_secret
                        : unlocked ? R.string.achievement_unlocked_tag
                        : R.string.achievement_locked);
        ((TextView) content.findViewById(R.id.achievement_detail_name)).setText(name);
        ((TextView) content.findViewById(R.id.achievement_detail_description)).setText(
                concealed ? getString(R.string.achievement_hidden_desc)
                        : getString(achievement.getDescription()));
        TextView progressView = content.findViewById(R.id.achievement_detail_progress);
        progressView.setText(concealed || !progress.isCountable()
                ? getString(concealed ? R.string.achievement_secret
                        : unlocked ? R.string.achievement_unlocked_tag
                        : R.string.achievement_locked)
                : getString(R.string.achievement_progress_detail,
                        formatProgress(achievement, progress)));
        TextView dateView = content.findViewById(R.id.achievement_detail_date);
        if (unlocked) {
            dateView.setText(getString(R.string.achievement_unlocked_on,
                    formatUnlockDate(manager.getUnlockedAt(achievement.getId()))));
        } else {
            dateView.setVisibility(View.GONE);
        }
        content.findViewById(R.id.achievement_detail_close)
                .setOnClickListener(view -> dialog.dismiss());
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.6f;
            window.setAttributes(attributes);
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32),
                    dp(440)), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        PremiumMotion.styleDialog(dialog);
    }

    private String formatUnlockDate(long timestamp) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                .format(new Date(timestamp));
    }

    private AchievementBadgeView badge(AchievementManager.Achievement achievement,
            boolean unlocked, boolean concealed, int sizeDp) {
        AchievementBadgeView badge = new AchievementBadgeView(this);
        badge.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        CharSequence description = concealed ? getString(R.string.achievement_hidden_name)
                : getString(achievement.getName());
        badge.bind(achievement.getBadgeArtRes(), unlocked, concealed, description);
        return badge;
    }

    private String formatProgress(AchievementManager.Achievement achievement,
            AchievementManager.Progress progress) {
        String category = achievement.getCategory();
        if ("time".equals(category)) {
            return compactDuration(progress.getCurrent()) + " / "
                    + compactDuration(progress.getTarget());
        }
        if (achievement.getId().startsWith("wallet_paid_")) {
            return formatEuros(progress.getCurrent()) + " / "
                    + formatEuros(progress.getTarget());
        }
        return String.format(Locale.getDefault(), "%,d / %,d",
                progress.getCurrent(), progress.getTarget());
    }

    private String formatEuros(long cents) {
        return String.format(Locale.getDefault(), "€%,.2f", Math.max(0L, cents) / 100.0);
    }

    private String compactDuration(long seconds) {
        if (seconds < 3600) return Math.max(0, seconds / 60) + "m";
        float hours = seconds / 3600f;
        if (hours == Math.round(hours)) return Math.round(hours) + "h";
        return String.format(Locale.getDefault(), "%.1fh", hours);
    }

    private int categoryTitle(String category) {
        switch (category) {
            case "blocks": return R.string.achievement_category_blocks;
            case "time": return R.string.achievement_category_time;
            case "sessions": return R.string.achievement_category_sessions;
            case "peaks": return R.string.achievement_category_peaks;
            case "streaks": return R.string.achievement_category_streaks;
            case "custom": return R.string.achievement_category_custom;
            case "appearance": return R.string.achievement_display_appearance;
            case "phrases": return R.string.achievement_display_phrases;
            case "packs": return R.string.achievement_display_packs;
            case "wallet_setup": return R.string.achievement_display_tribute_setup;
            case "wallet_payments": return R.string.achievement_display_tribute_payments;
            case "export": return R.string.achievement_category_export;
            case "app_mode": return R.string.achievement_category_app_mode;
            case "limits": return R.string.achievement_category_limits;
            case "pact": return R.string.achievement_category_pacts;
            case "hardcore": return R.string.achievement_category_hardcore;
            case "censor": return R.string.achievement_category_text_filter;
            case "control": return R.string.achievement_category_control;
            case "wallet": return R.string.achievement_category_wallet;
            case "subliminal": return R.string.achievement_category_subliminal;
            case "hidden": return R.string.achievement_category_hidden;
            case "special": return R.string.achievement_category_special;
            default: return R.string.achievements_title;
        }
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(color));
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.create(view.getTypeface(), Typeface.BOLD));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
