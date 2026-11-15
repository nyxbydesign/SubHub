package com.subhub.app.people;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.R;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.commitment.ServiceDurationView;

/** Reads the existing duration selection without instantiating editable controls. */
public final class ServiceDurationSettings {
    private ServiceDurationSettings() {}

    public static String label(Context context, boolean conceal) {
        SharedPreferences prefs = context.getSharedPreferences("subhub_service_duration_ui", Context.MODE_PRIVATE);
        return label(context, conceal && prefs.getBoolean("hidden", false),
                prefs.getLong("duration", ServiceDurationView.PERMANENT), prefs.getLong("minimum", 3_600_000L),
                prefs.getLong("maximum", 86_400_000L));
    }

    static String label(Context context, boolean hidden, long duration, long minimum, long maximum) {
        if (hidden) return context.getString(R.string.pact_time_hidden);
        if (duration == ServiceDurationView.PERMANENT) return context.getString(R.string.role_permanent_duration);
        if (duration == ServiceDurationView.RANDOM) return context.getString(R.string.role_random_duration,
                CommitmentManager.formatDuration(minimum), CommitmentManager.formatDuration(maximum));
        return CommitmentManager.formatDuration(Math.max(0L, duration));
    }
}
