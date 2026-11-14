package com.subhub.app.people;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
        return UiIdentity.addCard(parent, parent.getContext(), R.drawable.bg_card);
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
        row.setMinimumHeight(dp(context, 48));
        row.setPadding(0, dp(context, 6), 0, dp(context, 6));
        if (icon != 0) {
            ImageView image = new ImageView(context);
            image.setImageResource(icon);
            image.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_text)));
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
            imageParams.setMarginEnd(dp(context, 12));
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
            UiIdentity.addActionChevron(row);
            UiIdentity.bindClick(row, action);
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
        button.setTextColor(parent.getContext().getColor(primary ? R.color.text_primary : R.color.accent_text));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(parent.getContext(), 8);
        params.bottomMargin = dp(parent.getContext(), 8);
        parent.addView(button, params);
        return button;
    }

    /** App-time content for an existing card; daily usage is distinct from session counters. */
    public static void allowances(LinearLayout parent, PersonSnapshot snapshot) {
        if (snapshot.allowances.isEmpty()
                && !com.subhub.app.security.DeviceRolePreferences.isDomWorkspace(parent.getContext())) return;
        LinearLayout card = parent;
        UiIdentity.sectionIcon(text(card, parent.getContext().getString(R.string.role_app_time_daily), true),
                R.drawable.ic_nav_limits);
        for (PersonSnapshot.Allowance value : PersonSnapshot.Allowance.ordered(snapshot.allowances)) {
            LinearLayout app = row(card, R.drawable.ic_nav_limits, value.label,
                    value.usedMillis < 0 ? parent.getContext().getString(R.string.role_allowance_unknown, value.dailyMinutes)
                            : parent.getContext().getString(R.string.role_allowance_usage,
                                    value.usedMillis / 60_000L, value.displayMinutes()), null);
            int fallback = value.packageName.isEmpty() ? R.drawable.ic_nav_limits : R.drawable.ic_settings_apps;
            app.getChildAt(0).setTag("allowance_app_icon:" + value.packageName);
            if (value.appIcon != null || !snapshot.id.isLocal() && !DummySubPreview.ID.equals(snapshot.id))
                com.subhub.app.remote.RemoteUi.bindAppIcon((ImageView) app.getChildAt(0), value.appIcon, fallback);
            else com.subhub.app.util.AppIcons.bind((ImageView) app.getChildAt(0), value.packageName, fallback);
            if (value.usedMillis < 0) continue;
            com.subhub.app.appmode.AllowanceProgress.add(card, value);
        }
        com.subhub.app.appmode.ExtraTimeEntry.review(card, snapshot.id);
    }

    public static String connection(Context context, PersonSnapshot person) {
        return connection(context, person.connection, person.lastOnlineAt);
    }

    /** Last seen uses verified Online history. */
    public static String connection(Context context, PersonSnapshot.Connection state, long lastOnlineAt) {
        switch (state) {
            case LOCAL: return context.getString(R.string.role_local_data);
            case PENDING: return context.getString(R.string.role_connection_pending);
            case PAIRED: return context.getString(R.string.role_connection_paired);
            case CONNECTED: return context.getString(R.string.role_connection_connected);
            case IDLE:
            case OFFLINE:
                boolean idle = state == PersonSnapshot.Connection.IDLE;
                if (lastOnlineAt <= 0 || lastOnlineAt > System.currentTimeMillis())
                    return context.getString(idle ? R.string.role_connection_idle : R.string.role_connection_offline);
                return context.getString(idle ? R.string.role_idle_last_online : R.string.role_offline_last_online,
                        ProfileViews.formatLastSeen(lastOnlineAt));
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
