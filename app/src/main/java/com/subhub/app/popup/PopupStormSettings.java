package com.subhub.app.popup;

import android.content.Context;
import android.content.SharedPreferences;

import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.settings.SettingsRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bounded local settings for the recovered Popup Storm behavior. */
public final class PopupStormSettings {
    public static final String K_ACK = "popup_storm_photosensitivity_acknowledged";
    public static final String K_AVOID_PADDING = "popup_storm_avoid_padding_px";
    public static final String K_BOUNCING = "popup_storm_bouncing_enabled";
    public static final String K_BOUNCING_SPEED = "popup_storm_bouncing_speed_pps";
    public static final String K_BURST_DURATION = "popup_storm_burst_duration";
    public static final String K_BURST_ENABLED = "popup_storm_burst_enabled";
    public static final String K_BURST_FREQUENCY = "popup_storm_burst_frequency";
    public static final String K_BURST_MULTIPLIER = "popup_storm_burst_multiplier";
    public static final String K_CLICK_DISMISS_ALL = "popup_storm_click_dismisses_all";
    public static final String K_DENIAL_CAPTION = "popup_storm_denial_caption";
    public static final String K_DENIAL_CAPTION_TEXT = "popup_storm_denial_caption_text";
    public static final String K_DENIAL_CHANCE = "popup_storm_denial_chance";
    public static final String K_DENIAL_INTENSITY = "popup_storm_denial_intensity";
    public static final String K_DENIAL_STYLE = "popup_storm_denial_style";
    public static final String K_DETECTION_MODE = "popup_storm_detection_mode";
    public static final String K_DISPLAY_DURATION = "popup_storm_display_duration";
    public static final String K_ENABLED = "popup_storm_enabled";
    public static final String K_FADE_IN = "popup_storm_fade_in_ms";
    public static final String K_FADE_OUT = "popup_storm_fade_out_ms";
    public static final String K_FIXED_SIZE = "popup_storm_fixed_size_px";
    public static final String K_FOLDERS = "popup_storm_folders";
    public static final String K_MAX_SIMULTANEOUS = "popup_storm_max_simultaneous";
    public static final String K_MAX_SIZE = "popup_storm_max_size_px";
    public static final String K_MIN_SIZE = "popup_storm_min_size_px";
    public static final String K_POSITION_MODE = "popup_storm_position_mode";
    public static final String K_PACK_DIR = "popup_storm_pack_dir";
    public static final String K_PRESET = "popup_storm_active_preset";
    public static final String K_RANDOM_ROT = "popup_storm_random_rotation";
    public static final String K_ROT_MAX = "popup_storm_rotation_max_deg";
    public static final String K_SIZE_MODE = "popup_storm_size_mode";
    public static final String K_SPAWN_RATE = "popup_storm_spawn_rate";
    public static final int MAX_FOLDER_IMAGES = 5000;
    public static final int MAX_SIMULTANEOUS = 15;
    public static final float MAX_SPAWN_RATE = 8f;

    private final boolean enabled;
    private final boolean acknowledged;
    private final List<String> folders;
    private final String packImageDir;
    private final float spawnRate;
    private final float displayDuration;
    private final int maxSimultaneous;
    private final String positionMode;
    private final String sizeMode;
    private final int minSize;
    private final int maxSize;
    private final int fixedSize;
    private final boolean randomRotation;
    private final int rotationMax;
    private final int fadeInMs;
    private final int fadeOutMs;
    private final boolean burstEnabled;
    private final float burstFrequency;
    private final float burstDuration;
    private final float burstMultiplier;
    private final int denialChance;
    private final String denialStyle;
    private final int denialIntensity;
    private final boolean denialCaption;
    private final String denialCaptionText;
    private final boolean clickDismissesAll;
    private final boolean bouncing;
    private final int bouncingSpeed;
    private final String detectionMode;
    private final int avoidPadding;

    private PopupStormSettings(SharedPreferences preferences, boolean allowed) {
        enabled = allowed && preferences.getBoolean(K_ENABLED, false);
        acknowledged = preferences.getBoolean(K_ACK, false);
        Set<String> stored = preferences.getStringSet(K_FOLDERS, Collections.emptySet());
        folders = Collections.unmodifiableList(new ArrayList<>(stored == null
                ? Collections.emptySet() : new LinkedHashSet<>(stored)));
        packImageDir = preferences.getString(K_PACK_DIR,
                preferences.getString(CustomImageManager.PACK_DIR_KEY, ""));
        spawnRate = clamp(preferences.getFloat(K_SPAWN_RATE, 2f), 0f, MAX_SPAWN_RATE);
        displayDuration = clamp(preferences.getFloat(K_DISPLAY_DURATION, 1f), .2f, 8f);
        maxSimultaneous = clamp(preferences.getInt(K_MAX_SIMULTANEOUS, 8), 1, MAX_SIMULTANEOUS);
        positionMode = choice(preferences.getString(K_POSITION_MODE, "random"), "random", "center");
        sizeMode = choice(preferences.getString(K_SIZE_MODE, "random"), "random", "fixed");
        minSize = clamp(preferences.getInt(K_MIN_SIZE, 160), 40, 800);
        maxSize = clamp(preferences.getInt(K_MAX_SIZE, 380), Math.max(40, minSize), 1200);
        fixedSize = clamp(preferences.getInt(K_FIXED_SIZE, 260), 40, 1200);
        randomRotation = preferences.getBoolean(K_RANDOM_ROT, false);
        rotationMax = clamp(preferences.getInt(K_ROT_MAX, 25), 0, 90);
        fadeInMs = clamp(preferences.getInt(K_FADE_IN, 100), 0, 2000);
        fadeOutMs = clamp(preferences.getInt(K_FADE_OUT, 200), 0, 3000);
        burstEnabled = preferences.getBoolean(K_BURST_ENABLED, false);
        burstFrequency = clamp(preferences.getFloat(K_BURST_FREQUENCY, 30f), 5f, 300f);
        burstDuration = clamp(preferences.getFloat(K_BURST_DURATION, 4f), 1f, 30f);
        burstMultiplier = clamp(preferences.getFloat(K_BURST_MULTIPLIER, 3f), 1f, 6f);
        denialChance = clamp(preferences.getInt(K_DENIAL_CHANCE, 0), 0, 100);
        denialStyle = choice(preferences.getString(K_DENIAL_STYLE, "blur"),
                "blur", "pixelate", "mixed");
        denialIntensity = clamp(preferences.getInt(K_DENIAL_INTENSITY, 50), 0, 100);
        denialCaption = preferences.getBoolean(K_DENIAL_CAPTION, true);
        String caption = preferences.getString(K_DENIAL_CAPTION_TEXT, "NO");
        denialCaptionText = cleanCaption(caption);
        clickDismissesAll = preferences.getBoolean(K_CLICK_DISMISS_ALL, true);
        bouncing = preferences.getBoolean(K_BOUNCING, false);
        bouncingSpeed = clamp(preferences.getInt(K_BOUNCING_SPEED, 60), 10, 600);
        detectionMode = choice(preferences.getString(K_DETECTION_MODE, "off"),
                "off", "cover", "avoid");
        avoidPadding = clamp(preferences.getInt(K_AVOID_PADDING, 80), 0, 400);
    }

    public static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static PopupStormSettings load(Context context) {
        return new PopupStormSettings(preferences(context), com.subhub.app.security.SubFeaturePolicy.allowed(context, "popup"));
    }

    public boolean isEnabled() { return enabled; }
    public boolean isAcknowledged() { return acknowledged; }
    public List<String> getFolders() { return folders; }
    public String getPackImageDir() { return packImageDir == null ? "" : packImageDir; }
    public float getSpawnRate() { return spawnRate; }
    public float getDisplayDuration() { return displayDuration; }
    public int getMaxSimultaneous() { return maxSimultaneous; }
    public String getPositionMode() { return positionMode; }
    public String getSizeMode() { return sizeMode; }
    public int getMinSize() { return minSize; }
    public int getMaxSize() { return maxSize; }
    public int getFixedSize() { return fixedSize; }
    public boolean isRandomRotation() { return randomRotation; }
    public int getRotationMax() { return rotationMax; }
    public int getFadeInMs() { return fadeInMs; }
    public int getFadeOutMs() { return fadeOutMs; }
    public boolean isBurstEnabled() { return burstEnabled; }
    public float getBurstFrequency() { return burstFrequency; }
    public float getBurstDuration() { return burstDuration; }
    public float getBurstMultiplier() { return burstMultiplier; }
    public int getDenialChance() { return denialChance; }
    public String getDenialStyle() { return denialStyle; }
    public int getDenialIntensity() { return denialIntensity; }
    public boolean isDenialCaption() { return denialCaption; }
    public String getDenialCaptionText() { return denialCaptionText; }
    public boolean isClickDismissesAll() { return clickDismissesAll; }
    public boolean isBouncing() { return bouncing; }
    public int getBouncingSpeed() { return bouncingSpeed; }
    public String getDetectionMode() { return detectionMode; }
    public int getAvoidPadding() { return avoidPadding; }

    private static String cleanCaption(String value) {
        String safe = value == null ? "NO" : value.trim().replaceAll("[\\p{Cntrl}]", "");
        if (safe.isEmpty()) safe = "NO";
        return safe.substring(0, Math.min(32, safe.length()));
    }

    private static String choice(String value, String... allowed) {
        for (String item : allowed) if (item.equals(value)) return value;
        return allowed[0];
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
