package com.subhub.app.penance;

/** Independently selectable local-balance triggers. */
public enum PenanceInfraction {
    NEW_DETECTION("new_detection", 100, true),
    CENSORED_DWELL("censored_dwell", 150, false),
    CENSORED_TAP("censored_tap", 250, false),
    WATCHED_APP_OPEN("watched_app_open", 50, false),
    TAMPER_ATTEMPT("tamper_attempt", 500, false),
    TASK_FAILURE("task_failure", 100, false),
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
}
