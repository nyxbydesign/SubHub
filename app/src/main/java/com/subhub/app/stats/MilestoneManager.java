package com.subhub.app.stats;

import android.content.Context;
import android.content.SharedPreferences;

import com.subhub.app.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Recovered 20-step block milestone ladder with persistent presentation state. */
public final class MilestoneManager {
    private static final String KEY_LAST_SHOWN = "last_milestone_shown";
    private static final List<Integer> ALL = Collections.unmodifiableList(Arrays.asList(
            5, 10, 15, 25, 30, 50, 75, 100, 150, 250,
            300, 500, 750, 1000, 1500, 2500, 3000, 5000, 7500, 10000));
    private static final List<Integer> MAJOR = Collections.unmodifiableList(Arrays.asList(
            10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000));
    private static final Map<Integer, Integer> MESSAGES = messages();

    private MilestoneManager() {}

    public static Result takeUnseen(Context context, long totalBlocks) {
        SharedPreferences preferences = context.getSharedPreferences(
                StatsRepository.PREFS_NAME, Context.MODE_PRIVATE);
        int shown = preferences.getInt(KEY_LAST_SHOWN, 0);
        int reached = 0;
        for (int value : ALL) if (totalBlocks >= value) reached = value;
        if (reached <= shown) return null;
        preferences.edit().putInt(KEY_LAST_SHOWN, reached).apply();
        return new Result(reached, MESSAGES.get(reached), MAJOR.contains(reached));
    }

    static void acknowledgeHistory(Context context, long totalBlocks) {
        SharedPreferences preferences = context.getSharedPreferences(StatsRepository.PREFS_NAME, Context.MODE_PRIVATE);
        int reached = preferences.getInt(KEY_LAST_SHOWN, 0);
        for (int value : ALL) if (totalBlocks >= value) reached = Math.max(reached, value);
        if (!preferences.edit().putInt(KEY_LAST_SHOWN, reached).commit())
            throw new IllegalStateException("Historical milestone persistence unavailable");
    }

    public static int next(long current) {
        for (int value : ALL) if (value > current) return value;
        return ALL.get(ALL.size() - 1);
    }

    public static float progress(long current) {
        int next = next(current);
        int previous = 0;
        for (int value : ALL) if (value <= current) previous = value;
        if (next <= previous) return 1f;
        return Math.max(0f, Math.min(1f, (float) (current - previous) / (next - previous)));
    }

    private static Map<Integer, Integer> messages() {
        Map<Integer, Integer> values = new HashMap<>();
        values.put(5, R.string.milestone_5); values.put(10, R.string.milestone_10);
        values.put(15, R.string.milestone_15); values.put(25, R.string.milestone_25);
        values.put(30, R.string.milestone_30); values.put(50, R.string.milestone_50);
        values.put(75, R.string.milestone_75); values.put(100, R.string.milestone_100);
        values.put(150, R.string.milestone_150); values.put(250, R.string.milestone_250);
        values.put(300, R.string.milestone_300); values.put(500, R.string.milestone_500);
        values.put(750, R.string.milestone_750); values.put(1000, R.string.milestone_1000);
        values.put(1500, R.string.milestone_1500); values.put(2500, R.string.milestone_2500);
        values.put(3000, R.string.milestone_3000); values.put(5000, R.string.milestone_5000);
        values.put(7500, R.string.milestone_7500); values.put(10000, R.string.milestone_10000);
        return Collections.unmodifiableMap(values);
    }

    public static final class Result {
        private final int value;
        private final int message;
        private final boolean major;
        Result(int value, int message, boolean major) {
            this.value = value; this.message = message; this.major = major;
        }
        public int getValue() { return value; }
        public int getMessage() { return message; }
        public boolean isMajor() { return major; }
    }
}
