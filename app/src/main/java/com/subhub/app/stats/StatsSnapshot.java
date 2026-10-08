package com.subhub.app.stats;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Immutable aggregate compatible with the Android feature-source statistics schema. */
public final class StatsSnapshot {
    private final long totalBlocks;
    private final long totalSessionSeconds;
    private final int sessions;
    private final int currentStreak;
    private final String lastSessionDate;
    private final long totalProtectedSeconds;
    private final int currentSessionBlocks;
    private final int peakSessionBlocks;
    private final long longestSessionSeconds;
    private final int browserSessions;
    private final int browserPages;
    private final long exportedImages;
    private final int customPhrases;
    private final int censorStyleChanges;
    private final boolean borderColorChanged;
    private final Set<String> censorStylesTried;
    private final Set<String> borderEffectsTried;
    private final Set<String> activeDates;
    private final long allCategoryCensors;
    private final long currentSessionSeconds;
    private final long subliminalImpressions;
    private final long currentSessionSubliminals;
    private final long limitedAppMillis;
    private final long currentSessionLimitedAppMillis;
    private final long limitInterventions;
    private final long currentSessionLimitInterventions;
    private final long tributeEvents;
    private final long currentSessionTributeEvents;
    private final long tributeCents;
    private final long currentSessionTributeCents;
    private final long tamperEvents;
    private final long currentSessionTamperEvents;
    private final long popupImpressions;
    private final long currentSessionPopupImpressions;

    StatsSnapshot(long totalBlocks, long totalSessionSeconds, int sessions, int currentStreak,
            String lastSessionDate, long totalProtectedSeconds, int currentSessionBlocks,
            int peakSessionBlocks, long longestSessionSeconds, int browserSessions,
            int browserPages, long exportedImages, int customPhrases,
            int censorStyleChanges, boolean borderColorChanged, Set<String> censorStylesTried,
            Set<String> borderEffectsTried, Set<String> activeDates, long allCategoryCensors,
            long currentSessionSeconds, long subliminalImpressions,
            long currentSessionSubliminals, long limitedAppMillis,
            long currentSessionLimitedAppMillis, long limitInterventions,
            long currentSessionLimitInterventions, long tributeEvents,
            long currentSessionTributeEvents, long tributeCents,
            long currentSessionTributeCents, long tamperEvents,
            long currentSessionTamperEvents, long popupImpressions,
            long currentSessionPopupImpressions) {
        this.totalBlocks = totalBlocks;
        this.totalSessionSeconds = totalSessionSeconds;
        this.sessions = sessions;
        this.currentStreak = currentStreak;
        this.lastSessionDate = lastSessionDate;
        this.totalProtectedSeconds = totalProtectedSeconds;
        this.currentSessionBlocks = currentSessionBlocks;
        this.peakSessionBlocks = peakSessionBlocks;
        this.longestSessionSeconds = longestSessionSeconds;
        this.browserSessions = browserSessions;
        this.browserPages = browserPages;
        this.exportedImages = exportedImages;
        this.customPhrases = customPhrases;
        this.censorStyleChanges = censorStyleChanges;
        this.borderColorChanged = borderColorChanged;
        this.censorStylesTried = immutable(censorStylesTried);
        this.borderEffectsTried = immutable(borderEffectsTried);
        this.activeDates = immutable(activeDates);
        this.allCategoryCensors = allCategoryCensors;
        this.currentSessionSeconds = Math.max(0, currentSessionSeconds);
        this.subliminalImpressions = Math.max(0, subliminalImpressions);
        this.currentSessionSubliminals = Math.max(0, currentSessionSubliminals);
        this.limitedAppMillis = Math.max(0, limitedAppMillis);
        this.currentSessionLimitedAppMillis = Math.max(0, currentSessionLimitedAppMillis);
        this.limitInterventions = Math.max(0, limitInterventions);
        this.currentSessionLimitInterventions = Math.max(0, currentSessionLimitInterventions);
        this.tributeEvents = Math.max(0, tributeEvents);
        this.currentSessionTributeEvents = Math.max(0, currentSessionTributeEvents);
        this.tributeCents = Math.max(0, tributeCents);
        this.currentSessionTributeCents = Math.max(0, currentSessionTributeCents);
        this.tamperEvents = Math.max(0, tamperEvents);
        this.currentSessionTamperEvents = Math.max(0, currentSessionTamperEvents);
        this.popupImpressions = Math.max(0, popupImpressions);
        this.currentSessionPopupImpressions = Math.max(0, currentSessionPopupImpressions);
    }

    public long getTotalBlocks() { return totalBlocks; }
    public long getTotalSessionSeconds() { return totalSessionSeconds; }
    public int getSessions() { return sessions; }
    public int getCurrentStreak() { return currentStreak; }
    public String getLastSessionDate() { return lastSessionDate; }
    public long getTotalProtectedSeconds() { return totalProtectedSeconds; }
    public int getCurrentSessionBlocks() { return currentSessionBlocks; }
    public int getPeakSessionBlocks() { return peakSessionBlocks; }
    public long getLongestSessionSeconds() { return longestSessionSeconds; }
    public int getBrowserSessions() { return browserSessions; }
    public int getBrowserPages() { return browserPages; }
    public long getExportedImages() { return exportedImages; }
    public int getCustomPhrases() { return customPhrases; }
    public int getCensorStyleChanges() { return censorStyleChanges; }
    public boolean isBorderColorChanged() { return borderColorChanged; }
    public Set<String> getCensorStylesTried() { return censorStylesTried; }
    public Set<String> getBorderEffectsTried() { return borderEffectsTried; }
    public Set<String> getActiveDates() { return activeDates; }
    public long getAllCategoryCensors() { return allCategoryCensors; }

    /** Elapsed time for the currently active protection session, or zero while idle. */
    public long getCurrentSessionSeconds() { return currentSessionSeconds; }
    public long getSubliminalImpressions() { return subliminalImpressions; }
    public long getCurrentSessionSubliminals() { return currentSessionSubliminals; }
    public long getLimitedAppMillis() { return limitedAppMillis; }
    public long getCurrentSessionLimitedAppMillis() { return currentSessionLimitedAppMillis; }
    public long getLimitInterventions() { return limitInterventions; }
    public long getCurrentSessionLimitInterventions() { return currentSessionLimitInterventions; }
    public long getTributeEvents() { return tributeEvents; }
    public long getCurrentSessionTributeEvents() { return currentSessionTributeEvents; }
    public long getTributeCents() { return tributeCents; }
    public long getCurrentSessionTributeCents() { return currentSessionTributeCents; }
    public long getTamperEvents() { return tamperEvents; }
    public long getCurrentSessionTamperEvents() { return currentSessionTamperEvents; }
    public long getPopupImpressions() { return popupImpressions; }
    public long getCurrentSessionPopupImpressions() { return currentSessionPopupImpressions; }

    /** Discrete feature events, excluding durations and payment value. */
    public long getActivityEvents() {
        return totalBlocks + limitInterventions + tributeEvents + subliminalImpressions
                + popupImpressions;
    }

    /** Discrete feature events recorded during the active service. */
    public long getCurrentSessionActivityEvents() {
        return currentSessionBlocks + currentSessionLimitInterventions
                + currentSessionTributeEvents + currentSessionSubliminals
                + currentSessionPopupImpressions;
    }

    public static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
        return String.format(Locale.ROOT, "%dm", minutes);
    }

    public static String formatClock(long totalSeconds) {
        long safe = Math.max(0, totalSeconds);
        long hours = safe / 3600;
        long minutes = (safe % 3600) / 60;
        long seconds = safe % 60;
        if (hours > 0) return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private static Set<String> immutable(Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
