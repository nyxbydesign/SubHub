package com.subhub.app.activity;

import android.content.Context;
import com.subhub.app.R;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;

/** Event copy is generated from typed facts rather than peer-supplied messages. */
public final class ActivityText {
    private ActivityText() {}

    public static String title(Context context, ActivityEntry entry) {
        switch (entry.event.kind) {
            case APP_OPENED: return context.getString(R.string.dom_activity_app_opened, entry.appLabel);
            case TRIBUTE_TRIGGERED: return context.getString(R.string.dom_activity_tribute_triggered);
            case APP_LIMIT_REACHED: return context.getString(R.string.dom_activity_app_limit, entry.appLabel);
            case SHARED_LIMIT_REACHED: return context.getString(R.string.dom_activity_shared_limit);
            default: throw new IllegalArgumentException("Unknown activity kind");
        }
    }

    public static String detail(Context context, ActivityEntry entry) {
        ActivityEvent event = entry.event;
        if (event.kind == ActivityEvent.Kind.TRIBUTE_TRIGGERED) {
            NumberFormat amount = NumberFormat.getCurrencyInstance(context.getResources().getConfiguration().getLocales().get(0));
            amount.setCurrency(Currency.getInstance(event.currency));
            return context.getString(R.string.dom_activity_tribute_detail,
                    amount.format(BigDecimal.valueOf(event.amountCents, 2)), reason(context, event.reason));
        }
        if (event.kind == ActivityEvent.Kind.APP_LIMIT_REACHED || event.kind == ActivityEvent.Kind.SHARED_LIMIT_REACHED)
            return context.getString(R.string.dom_activity_minutes, event.limitMinutes);
        return "";
    }

    public static int icon(ActivityEntry entry) {
        switch (entry.event.kind) {
            case APP_OPENED: return R.drawable.ic_settings_apps;
            case TRIBUTE_TRIGGERED: return R.drawable.ic_nav_money;
            default: return R.drawable.ic_nav_limits;
        }
    }

    private static String reason(Context context, String reason) {
        switch (reason) {
            case "watched_app_open": return context.getString(R.string.dom_activity_reason_app);
            case "censored_dwell": return context.getString(R.string.dom_activity_reason_dwell);
            case "censored_tap": return context.getString(R.string.dom_activity_reason_tap);
            case "tamper_attempt": return context.getString(R.string.dom_activity_reason_tamper);
            case "paid_pause": return context.getString(R.string.dom_activity_reason_pause);
            case "new_detection": return context.getString(R.string.dom_activity_reason_detection);
            default: return context.getString(R.string.dom_activity_reason_rule);
        }
    }
}
