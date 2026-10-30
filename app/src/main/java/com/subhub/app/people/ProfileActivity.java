package com.subhub.app.people;

/** The primary Profile destination retains Statistics and Achievements in the same host. */
public final class ProfileActivity extends com.subhub.app.stats.StatsActivity {
    @Override protected boolean initiallyShowProfile() { return true; }
}
