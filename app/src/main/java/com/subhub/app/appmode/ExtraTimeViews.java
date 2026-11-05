package com.subhub.app.appmode;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import com.subhub.app.util.UiIdentity;

/** Extra-time cards use the same identity, spacing and controls as the app's person pages. */
final class ExtraTimeViews {
    private ExtraTimeViews() { }

    static LinearLayout card(LinearLayout parent, String tag) {
        LinearLayout value = PersonUi.card(parent);
        value.setTag(tag);
        return value;
    }

    static void app(LinearLayout parent, String packageName, String label, boolean local) {
        Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, PersonUi.dp(context, 8));
        ImageView icon = new ImageView(context);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (local) com.subhub.app.util.AppIcons.bind(icon, packageName, R.drawable.ic_settings_apps);
        else {
            icon.setImageResource(R.drawable.ic_settings_apps);
            icon.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.accent_text)));
        }
        row.addView(icon, new LinearLayout.LayoutParams(PersonUi.dp(context, 36), PersonUi.dp(context, 36)));
        TextView title = new TextView(context);
        title.setText(label);
        title.setTextColor(context.getColor(R.color.text_primary));
        UiIdentity.textSize(title, R.dimen.ui_text_section);
        title.setTypeface(null, Typeface.BOLD);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        size.setMarginStart(PersonUi.dp(context, 12));
        row.addView(title, size);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    static TextView status(LinearLayout parent, CharSequence copy, boolean accent) {
        TextView value = PersonUi.text(parent, copy, false);
        UiIdentity.textSize(value, R.dimen.ui_text_label);
        value.setTypeface(null, Typeface.BOLD);
        value.setTextColor(parent.getContext().getColor(accent ? R.color.accent_text : R.color.text_secondary));
        value.setPadding(0, PersonUi.dp(parent.getContext(), 4), 0, PersonUi.dp(parent.getContext(), 4));
        return value;
    }

    static void usage(LinearLayout parent, long usedMillis, int dailyMinutes, int bonusMinutes) {
        Context context = parent.getContext();
        if (dailyMinutes <= 0) {
            PersonUi.text(parent, context.getString(R.string.extra_time_app_usage_today), false);
            requestUsage(parent, usedMillis);
            if (bonusMinutes > 0) PersonUi.text(parent,
                    context.getString(R.string.extra_time_bonus_today, bonusMinutes), false);
            return;
        }
        int total = Math.max(0, dailyMinutes) + Math.max(0, bonusMinutes);
        PersonUi.text(parent, bonusMinutes > 0 ? context.getString(R.string.extra_time_daily_bonus,
                dailyMinutes, bonusMinutes) : context.getString(R.string.extra_time_daily, dailyMinutes), false);
        long used = Math.max(0, usedMillis);
        ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(total <= 0 ? 0 : (int) Math.min(1000, used * 1000 / (total * 60_000L)));
        progress.setProgressTintList(ColorStateList.valueOf(context.getColor(R.color.accent_mid)));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(context.getColor(R.color.outline_subtle)));
        String description = context.getString(R.string.extra_time_usage, used / 60_000L, total);
        progress.setContentDescription(description);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, PersonUi.dp(context, 5));
        size.topMargin = PersonUi.dp(context, 6);
        size.bottomMargin = PersonUi.dp(context, 4);
        parent.addView(progress, size);
        TextView value = PersonUi.text(parent, description, false);
        UiIdentity.textSize(value, R.dimen.ui_text_caption);
    }

    /** A frozen request does not establish which configured budget was active at submission. */
    static void requestUsage(LinearLayout parent, long usedMillis) {
        PersonUi.text(parent, parent.getContext().getString(R.string.extra_time_app_used,
                Math.max(0L, usedMillis) / 60_000L), false);
    }
}
