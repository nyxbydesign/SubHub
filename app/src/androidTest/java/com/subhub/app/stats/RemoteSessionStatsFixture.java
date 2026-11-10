package com.subhub.app.stats;

import android.content.Context;

/** Uses real statistics persistence without starting the shared daily collector. */
public final class RemoteSessionStatsFixture {
    private RemoteSessionStatsFixture() { }
    public static StatsRepository create(Context context) { return new StatsRepository(context, false); }
}
