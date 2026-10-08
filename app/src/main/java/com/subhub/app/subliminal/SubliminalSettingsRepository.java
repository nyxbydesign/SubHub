package com.subhub.app.subliminal;

import android.content.Context;
import android.content.SharedPreferences;

import com.subhub.app.settings.SettingsRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Stores and resolves the standalone on-device subliminal phrase library. */
public final class SubliminalSettingsRepository {
    public static final String KEY_PRESET = "subliminal_preset";
    public static final String KEY_ADVANCED = "subliminal_advanced";
    public static final String KEY_OPACITY = "subliminal_opacity_percent";
    public static final String KEY_VISIBLE_MS = "subliminal_visible_ms";
    public static final String KEY_MIN_INTERVAL_MS = "subliminal_min_interval_ms";
    public static final String KEY_MAX_INTERVAL_MS = "subliminal_max_interval_ms";
    public static final String KEY_TEXT_SIZE = "subliminal_text_size_sp";
    public static final String KEY_PACKS = "subliminal_enabled_packs";
    public static final String KEY_CUSTOM = "subliminal_custom_phrases";

    public static final String PACK_OBEDIENCE = "obedience";
    public static final String PACK_FOCUS = "focus";
    public static final String PACK_BETA = "beta";
    public static final String PACK_FINDOM = "findom";
    public static final String PACK_CUSTOM = "custom";

    private static final Set<String> KNOWN_PACKS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(PACK_OBEDIENCE, PACK_FOCUS, PACK_BETA,
                    PACK_FINDOM, PACK_CUSTOM)));
    private static final Set<String> DEFAULT_PACKS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(PACK_OBEDIENCE, PACK_FOCUS)));

    private final SharedPreferences preferences;

    public SubliminalSettingsRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public SubliminalSettings load() {
        SubliminalSettings.Preset preset = parsePreset(preferences.getString(KEY_PRESET, "normal"));
        Values defaults = valuesFor(preset);
        boolean advanced = preferences.getBoolean(KEY_ADVANCED, false);
        int opacity = advanced ? clamp(preferences.getInt(KEY_OPACITY, defaults.opacity), 1, 15)
                : defaults.opacity;
        long visible = advanced ? clamp(preferences.getLong(KEY_VISIBLE_MS, defaults.visible),
                800L, 4_000L) : defaults.visible;
        long minimum = advanced ? clamp(preferences.getLong(KEY_MIN_INTERVAL_MS, defaults.minimum),
                5_000L, 300_000L) : defaults.minimum;
        long maximum = advanced ? clamp(preferences.getLong(KEY_MAX_INTERVAL_MS, defaults.maximum),
                minimum, 300_000L) : defaults.maximum;
        int textSize = advanced ? clamp(preferences.getInt(KEY_TEXT_SIZE, defaults.textSize),
                14, 28) : defaults.textSize;
        Set<String> packs = cleanPacks(preferences.getStringSet(KEY_PACKS, DEFAULT_PACKS));
        return new SubliminalSettings(preset, advanced, opacity, visible, minimum, maximum,
                textSize, packs, normalizeCustom(preferences.getString(KEY_CUSTOM, "")));
    }

    public void savePreset(SubliminalSettings.Preset preset) {
        preferences.edit().putString(KEY_PRESET,
                (preset == null ? SubliminalSettings.Preset.NORMAL : preset)
                        .name().toLowerCase(Locale.ROOT)).apply();
    }

    public void saveAdvanced(boolean enabled, int opacity, long visible, long minimum,
            long maximum, int textSize) {
        long cleanMinimum = clamp(minimum, 5_000L, 300_000L);
        preferences.edit().putBoolean(KEY_ADVANCED, enabled)
                .putInt(KEY_OPACITY, clamp(opacity, 1, 15))
                .putLong(KEY_VISIBLE_MS, clamp(visible, 800L, 4_000L))
                .putLong(KEY_MIN_INTERVAL_MS, cleanMinimum)
                .putLong(KEY_MAX_INTERVAL_MS, clamp(maximum, cleanMinimum, 300_000L))
                .putInt(KEY_TEXT_SIZE, clamp(textSize, 14, 28)).apply();
    }

    public void savePacks(Set<String> packs) {
        preferences.edit().putStringSet(KEY_PACKS, new LinkedHashSet<>(cleanPacks(packs))).apply();
    }

    public void saveCustomPhrases(String phrases) {
        preferences.edit().putString(KEY_CUSTOM, normalizeCustom(phrases)).apply();
    }

    public List<String> phrases(SubliminalSettings settings) {
        if (settings == null) settings = load();
        return resolvePhrases(settings);
    }

    /** Deduplicate only the display pool; the saved, case-preserving custom text is unchanged. */
    static List<String> resolvePhrases(SubliminalSettings settings) {
        Set<String> result = new LinkedHashSet<>();
        Set<String> packs = settings.getEnabledPacks();
        if (packs.contains(PACK_OBEDIENCE)) result.addAll(Arrays.asList(
                "There you are. Back in line.", "Ask nicely. I like manners.",
                "Less fuss. More composure.", "That restraint suits you.",
                "Good. Keep that attitude.", "The rule hasn't changed, trouble.",
                "You know the drill. Show me.", "A little patience looks good on you.",
                "No grand speech. Just follow through.", "Steady now. Make it look effortless.",
                "Nicely done. Don't get smug.", "One instruction. Your full attention.",
                "Settle down. You've got this.", "That is more like it.",
                "Save the theatrics. Keep the promise.", "Quiet confidence. Better manners.",
                "Consider this your raised eyebrow.", "Oh, you heard me.",
                "Good form. Keep it tidy.", "Your chosen rules. Your chance to shine."));
        if (packs.contains(PACK_FOCUS)) result.addAll(Arrays.asList(
                "Eyes up. You're drifting.", "Less fidgeting. More composure.",
                "Back to it, trouble.", "One thing at a time. Do it well.",
                "Drop the performance. Find your focus.", "Take a breath. Start clean.",
                "Unclench your shoulders. Look alive.", "Posture check. Nicely now.",
                "Slow is fine. Sloppy isn't the aim.", "Give this moment some attention.",
                "Catch that wandering thought.", "A little poise, please.",
                "Less rushing. More intention.", "Finish the thought before chasing another.",
                "Bring your attention back here.", "Soft shoulders. Steady hands.",
                "Find your pace. Hold it.", "That distraction can wait.",
                "Make the next move a deliberate one.", "Composed looks good on you."));
        if (packs.contains(PACK_BETA)) result.addAll(Arrays.asList(
                "Cute attempt, beta. The rule still stands.", "All that bravado. Adorable.",
                "Behave, beta. You know this part.", "Big talk. Let's see some manners.",
                "Back in line, little show-off.", "Not the main character this time.",
                "That pout isn't changing the script.", "Eyes down, beta. Stay composed.",
                "The spectator seat has your name on it.", "Nice try, cuck. Still the same rules.",
                "Less swagger. Better follow-through.", "Oh, look. The beta has an opinion.",
                "You can be cheeky and still behave.", "Save the victory lap, trouble.",
                "A little less ego. A little more grace.", "Still trying to charm your way past it?",
                "Stay in character. You chose this role.", "No need to impress. Just be attentive.",
                "Caught showing off again, beta.", "Good manners beat that big speech."));
        if (packs.contains(PACK_FINDOM)) result.addAll(Arrays.asList(
                "Velvet-rope energy. Mind your manners.", "Admire the luxury. Lose the entitlement.",
                "A little tribute. A lot of theatre.", "The VIP list is looking selective.",
                "Champagne attitude. Impeccable composure.", "That view has exclusive-access energy.",
                "Luxury likes a little patience.", "The crown is not taking suggestions.",
                "Bring your best manners to the throne.", "Worth the wait? Naturally.",
                "A raised eyebrow. A velvet rope.", "The tribute theatre has a dress code: poise.",
                "Exclusive doesn't mean entitled.", "Elegance first. Grand gestures optional.",
                "Put that swagger on the guest list.", "All that sparkle. Still the same rules.",
                "No shortcuts past the velvet rope.", "A little ceremony. A lot of composure.",
                "Keep your composure. The crown is watching.", "The throne appreciates good manners."));
        if (packs.contains(PACK_CUSTOM)) {
            for (String line : settings.getCustomPhrases().split("\\R")) {
                String clean = line.trim();
                if (!clean.isEmpty()) result.add(clean);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    public static Values valuesFor(SubliminalSettings.Preset preset) {
        switch (preset == null ? SubliminalSettings.Preset.NORMAL : preset) {
            case GENTLE: return new Values(3, 2_400L, 45_000L, 90_000L, 18);
            case STRICT: return new Values(7, 1_600L, 15_000L, 40_000L, 20);
            case ULTRA: return new Values(10, 1_200L, 8_000L, 25_000L, 21);
            case NORMAL:
            default: return new Values(5, 2_000L, 25_000L, 60_000L, 19);
        }
    }

    private static SubliminalSettings.Preset parsePreset(String value) {
        try { return SubliminalSettings.Preset.valueOf(
                (value == null ? "NORMAL" : value).trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return SubliminalSettings.Preset.NORMAL; }
    }

    private static Set<String> cleanPacks(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) for (String value : values) if (KNOWN_PACKS.contains(value)) {
            result.add(value);
        }
        return Collections.unmodifiableSet(result);
    }

    private static String normalizeCustom(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        for (String line : value.split("\\R")) {
            String clean = line.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
            if (clean.isEmpty()) continue;
            lines.add(clean.length() <= 120 ? clean : clean.substring(0, 120));
            if (lines.size() >= 200) break;
        }
        return String.join("\n", lines);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class Values {
        public final int opacity;
        public final long visible;
        public final long minimum;
        public final long maximum;
        public final int textSize;
        Values(int opacity, long visible, long minimum, long maximum, int textSize) {
            this.opacity = opacity;
            this.visible = visible;
            this.minimum = minimum;
            this.maximum = maximum;
            this.textSize = textSize;
        }
    }
}
