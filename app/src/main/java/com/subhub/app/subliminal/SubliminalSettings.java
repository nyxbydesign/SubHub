package com.subhub.app.subliminal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable, local-only configuration for faint foreground messages. */
public final class SubliminalSettings {
    public static final int MAX_OPACITY_PERCENT = 35;

    public enum Preset { GENTLE, NORMAL, STRICT, ULTRA }

    private final Preset preset;
    private final boolean advanced;
    private final int opacityPercent;
    private final long visibleMillis;
    private final long minimumIntervalMillis;
    private final long maximumIntervalMillis;
    private final int textSizeSp;
    private final Set<String> enabledPacks;
    private final String customPhrases;

    SubliminalSettings(Preset preset, boolean advanced, int opacityPercent,
            long visibleMillis, long minimumIntervalMillis, long maximumIntervalMillis,
            int textSizeSp, Set<String> enabledPacks, String customPhrases) {
        this.preset = preset;
        this.advanced = advanced;
        this.opacityPercent = opacityPercent;
        this.visibleMillis = visibleMillis;
        this.minimumIntervalMillis = minimumIntervalMillis;
        this.maximumIntervalMillis = maximumIntervalMillis;
        this.textSizeSp = textSizeSp;
        this.enabledPacks = Collections.unmodifiableSet(new LinkedHashSet<>(enabledPacks));
        this.customPhrases = customPhrases == null ? "" : customPhrases;
    }

    public Preset getPreset() { return preset; }
    public boolean isAdvanced() { return advanced; }
    public int getOpacityPercent() { return opacityPercent; }
    public long getVisibleMillis() { return visibleMillis; }
    public long getMinimumIntervalMillis() { return minimumIntervalMillis; }
    public long getMaximumIntervalMillis() { return maximumIntervalMillis; }
    public int getTextSizeSp() { return textSizeSp; }
    public Set<String> getEnabledPacks() { return enabledPacks; }
    public String getCustomPhrases() { return customPhrases; }
}
