package com.subhub.app.people;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;
import com.subhub.app.util.UiIdentity;

/** Native row composition shared by both role experiences. */
public final class PersonUi {
    private PersonUi() {}

    public static LinearLayout column(Context context) {
        LinearLayout value = new LinearLayout(context);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    public static LinearLayout card(LinearLayout parent) {
        LinearLayout value = column(parent.getContext());
        value.setBackgroundResource(R.drawable.bg_card);
        int inset = dp(parent.getContext(), 16);
        value.setPadding(inset, inset, inset, inset);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(parent.getContext(), 14);
        parent.addView(value, params);
        return value;
    }

    public static TextView text(LinearLayout parent, CharSequence copy, boolean heading) {
        TextView value = new TextView(parent.getContext());
        value.setText(copy);
        UiIdentity.textSize(value, heading ? R.dimen.ui_text_section : R.dimen.ui_text_body);
        value.setTextColor(parent.getContext().getColor(heading ? R.color.text_primary : R.color.text_secondary));
        value.setLineSpacing(dp(parent.getContext(), 3), 1f);
        value.setPadding(0, dp(parent.getContext(), 4), 0, dp(parent.getContext(), 6));
        if (heading) {
            value.setTypeface(null, Typeface.BOLD);
            ViewCompat.setAccessibilityHeading(value, true);
        }
        parent.addView(value, new LinearLayout.LayoutParams(-1, -2));
        return value;
    }

    public static LinearLayout row(LinearLayout parent, int icon, CharSequence title,
            CharSequence detail, Runnable action) {
        Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 64));
        row.setPadding(0, dp(context, 8), 0, dp(context, 8));
        if (icon != 0) {
            ImageView image = new ImageView(context);
            image.setImageResource(icon);
            image.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_text)));
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
            imageParams.setMarginEnd(dp(context, 14));
            row.addView(image, imageParams);
        }
        LinearLayout copy = column(context);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView label = text(copy, title, true);
        label.setPadding(0, 0, 0, 0);
        ViewCompat.setAccessibilityHeading(label, false);
        if (detail != null && detail.length() > 0) {
            TextView description = text(copy, detail, false);
            description.setPadding(0, dp(context, 4), 0, 0);
        }
        if (action != null) {
            TextView arrow = new TextView(context);
            arrow.setText("›");
            arrow.setTextSize(24);
            arrow.setTextColor(context.getColor(R.color.text_secondary));
            arrow.setGravity(Gravity.CENTER);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(context, 24), -2));
            row.setFocusable(true);
            row.setClickable(true);
            android.util.TypedValue ripple = new android.util.TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            row.setForeground(context.getDrawable(ripple.resourceId));
            row.setOnClickListener(v -> action.run());
            ViewCompat.setScreenReaderFocusable(row, true);
        }
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    public static TextView action(LinearLayout parent, int label, Runnable action, boolean primary) {
        TextView button = (TextView) android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.view_ux_action, parent, false);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(parent.getContext(), 48));
        button.setBackgroundResource(primary ? R.drawable.bg_role_primary : R.drawable.bg_card);
        button.setTextColor(parent.getContext().getColor(primary ? R.color.primary_action_text : R.color.accent_text));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(parent.getContext(), 8);
        params.bottomMargin = dp(parent.getContext(), 8);
        parent.addView(button, params);
        return button;
    }

    public static void allowances(LinearLayout parent, PersonSnapshot snapshot) {
        if (snapshot.allowances.isEmpty()) return;
        LinearLayout card = card(parent);
        text(card, parent.getContext().getString(R.string.role_app_time), true);
        for (PersonSnapshot.Allowance value : snapshot.allowances) {
            row(card, R.drawable.ic_nav_limits, value.label,
                    value.usedMillis < 0 ? parent.getContext().getString(R.string.role_allowance_unknown, value.dailyMinutes)
                            : parent.getContext().getString(R.string.role_allowance_usage,
                                    value.usedMillis / 60_000L, value.dailyMinutes), null);
            if (value.usedMillis < 0) continue;
            ProgressBar progress = new ProgressBar(parent.getContext(), null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            long maximum = value.dailyMinutes * 60_000L;
            progress.setProgress(maximum <= 0 ? 0 : (int) Math.min(1000, value.usedMillis * 1000 / maximum));
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(parent.getContext().getColor(R.color.accent_text)));
            progress.setContentDescription(value.label);
            card.addView(progress, new LinearLayout.LayoutParams(-1, dp(parent.getContext(), 6)));
        }
    }

    public static String connection(Context context, PersonSnapshot person) {
        switch (person.connection) {
            case LOCAL: return context.getString(R.string.role_local_data);
            case PENDING: return context.getString(R.string.role_connection_pending);
            case PAIRED: return context.getString(R.string.role_connection_paired);
            case CONNECTED: return context.getString(R.string.role_connection_connected);
            case OFFLINE: return context.getString(R.string.role_connection_offline);
            case REVOKED: return context.getString(R.string.role_connection_revoked);
            case ERROR: return context.getString(R.string.role_connection_error);
            default: return context.getString(R.string.role_connection_unpaired);
        }
    }

    public static int icon(String section) {
        switch (section) {
            case "censor": return R.drawable.ic_nav_censor;
            case "apps":
            case "limits": return R.drawable.ic_nav_limits;
            case "wallet": return R.drawable.ic_nav_money;
            default: return R.drawable.ic_atmosphere;
        }
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
