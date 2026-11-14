package com.subhub.app.penance;

import androidx.annotation.StringRes;
import com.subhub.app.R;

/** Independently selectable local-balance triggers. */
public enum PenanceInfraction {
    NEW_DETECTION("new_detection", 100, true),
    CENSORED_DWELL("censored_dwell", 150, false),
    CENSORED_TAP("censored_tap", 250, false),
    WATCHED_APP_OPEN("watched_app_open", 50, false),
    TAMPER_ATTEMPT("tamper_attempt", 500, false),
    TASK_FAILURE("task_failure", 100, false),
    EXTRA_TIME_REQUEST("extra_time_request", 100, false),
    PAID_PAUSE("paid_pause", PaidPauseManager.DEFAULT_PRICE_CENTS, false);

    private final String preferenceKey;
    private final int defaultCents;
    private final boolean enabledByDefault;

    PenanceInfraction(String preferenceKey, int defaultCents, boolean enabledByDefault) {
        this.preferenceKey = preferenceKey;
        this.defaultCents = defaultCents;
        this.enabledByDefault = enabledByDefault;
    }

    public String preferenceKey() { return preferenceKey; }
    public int defaultCents() { return defaultCents; }
    public boolean enabledByDefault() { return enabledByDefault; }

    @StringRes int historyLabel() {
        return switch (this) {
            case NEW_DETECTION -> R.string.penance_history_detection;
            case CENSORED_DWELL -> R.string.penance_history_dwell;
            case CENSORED_TAP -> R.string.penance_history_tap;
            case WATCHED_APP_OPEN -> R.string.penance_history_app_open;
            case TAMPER_ATTEMPT -> R.string.penance_history_tamper;
            case TASK_FAILURE -> R.string.tasks_tribute_rule;
            case EXTRA_TIME_REQUEST -> R.string.extra_time_tribute_rule_enabled;
            case PAID_PAUSE -> R.string.paid_pause_history_label;
        };
    }
}
