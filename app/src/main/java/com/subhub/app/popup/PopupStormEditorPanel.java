package com.subhub.app.popup;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.atmosphere.AtmosphereDisclosure;
import com.subhub.app.capture.CensorImageEditor;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.databinding.PanelPopupStormBinding;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/** Popup Storm controls for standalone and target-scoped Contract editing. */
public final class PopupStormEditorPanel implements AutoCloseable {
    private final AppCompatActivity activity;
    private final PanelPopupStormBinding binding;
    private final SharedPreferences preferences;
    private final LocalRequests localRequests;
    private boolean updatingEnabled;
    private boolean showAllSettings;
    private CensorImageEditor remoteArtwork;
    private boolean closed;
    private EditText caption;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable statusUpdater = new Runnable() {
        @Override public void run() {
            if (closed || FeatureEditorSession.remote(activity)) return;
            refreshStatus();
            ui.postDelayed(this, 500);
        }
    };
    public PopupStormEditorPanel(AppCompatActivity activity, View root, boolean advancedExpanded,
            CensorImageEditor.Picker picker, LocalRequests localRequests) {
        this.activity = activity;
        this.localRequests = localRequests;
        binding = PanelPopupStormBinding.bind(root);
        preferences = PopupStormSettings.preferences(FeatureEditorSession.context(activity));
        binding.switchPopupStorm.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingEnabled) setEffectEnabled(checked);
        });
        if (!FeatureEditorSession.remote(activity)) PopupStormManager.get().reloadSettings(activity);
        binding.buttonAddFolder.setOnClickListener(view -> {
            if (localRequests != null && FeatureEditorSession.canEdit(activity, "popup"))
                localRequests.folder.launch(null);
        });
        binding.buttonStop.setOnClickListener(view -> {
            if (FeatureEditorSession.remote(activity)) return;
            PopupStormManager.get().stop();
            refreshStatus();
        });
        binding.buttonPreview.setOnClickListener(view -> preview());
        binding.presetSlider.setLabelFormatter(value -> IntensityPresets.values()[Math.round(value)].getDisplayName());
        binding.presetSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) applyPreset(Math.round(value));
        });
        binding.presetSlider.addOnSliderTouchListener(new com.google.android.material.slider.Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(com.google.android.material.slider.Slider slider) { }
            @Override public void onStopTrackingTouch(com.google.android.material.slider.Slider slider) {
                int step = Math.round(slider.getValue());
                if (!IntensityPresets.values()[step].name().equals(preferences.getString(PopupStormSettings.K_PRESET, "CUSTOM"))) {
                    applyPreset(step);
                }
            }
        });
        renderPreset();
        rebuildSettings();
        AtmosphereDisclosure.bind(binding.buttonAdvancedDetails, binding.dynamicSettings, advancedExpanded);
        if (FeatureEditorSession.remote(activity)) {
            binding.popupLocalActions.setVisibility(View.GONE);
            binding.buttonPreview.setVisibility(View.GONE);
            binding.buttonStop.setVisibility(View.GONE);
            binding.buttonAddFolder.setText(R.string.custom_images_add);
            remoteArtwork = new CensorImageEditor(activity, binding.buttonAddFolder,
                    binding.libraryCount, binding.foldersList, "popup", picker);
        }
    }

    public boolean advancedExpanded() {
        return binding.dynamicSettings.getVisibility() == View.VISIBLE;
    }

    public void showAllSettings() {
        showAllSettings = true;
        binding.buttonAdvancedDetails.setVisibility(View.GONE);
        binding.dynamicSettings.setVisibility(View.VISIBLE);
        updateAdvancedApplicability(FeatureEditorSession.canEdit(activity, "popup"));
    }

    public void resume() {
        applyEditState();
        ui.removeCallbacks(statusUpdater);
        if (!FeatureEditorSession.remote(activity)) {
            PopupStormManager.get().reloadSettings(activity);
            ui.post(statusUpdater);
        }
        mainDelayRefresh();
    }

    public void pause() {
        flush();
        ui.removeCallbacks(statusUpdater);
    }

    public boolean flush() {
        if (caption != null) saveText(caption, PopupStormSettings.K_DENIAL_CAPTION_TEXT, "NO");
        return true;
    }

    void overlayResult(boolean enable) {
        if (Settings.canDrawOverlays(activity)) {
            if (enable) completeEnable();
            else preview();
        } else if (enable) renderEnabled();
        else refreshStatus();
    }

    private void requestPreview() {
        if (FeatureEditorSession.remote(activity)) return;
        if (!FeatureEditorSession.canEdit(activity, "popup")) return;
        if (!preferences.getBoolean(PopupStormSettings.K_ACK, false)) {
            com.subhub.app.util.ThemedDialogs.builder(activity)
                    .setTitle(R.string.popup_photosensitivity_title)
                    .setMessage(R.string.popup_photosensitivity_body)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.popup_acknowledge, (dialog, which) -> {
                        if (!FeatureEditorSession.canEdit(activity, "popup")) return;
                        preferences.edit().putBoolean(PopupStormSettings.K_ACK, true).apply();
                        requestOverlayOrPreview();
                    }).show();
            return;
        }
        requestOverlayOrPreview();
    }

    private void requestOverlayOrPreview() {
        if (FeatureEditorSession.remote(activity)) return;
        if (!FeatureEditorSession.canEdit(activity, "popup")) return;
        if (!Settings.canDrawOverlays(activity)) {
            Toast.makeText(activity, R.string.popup_overlay_permission, Toast.LENGTH_LONG).show();
            localRequests.previewOverlay.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
        } else preview();
    }

    private void preview() {
        if (FeatureEditorSession.remote(activity)) return;
        if (!FeatureEditorSession.canEdit(activity, "popup")) return;
        PopupStormSettings current = PopupStormSettings.load(activity);
        if (!current.isAcknowledged() || !Settings.canDrawOverlays(activity)) {
            requestPreview();
            return;
        }
        PopupStormManager.PreviewResult result = PopupStormManager.get().preview(activity);
        if (result == PopupStormManager.PreviewResult.ALREADY_RUNNING) {
            Toast.makeText(activity, R.string.popup_preview_already_running, Toast.LENGTH_SHORT).show();
        } else if (result == PopupStormManager.PreviewResult.UNAVAILABLE) {
            Toast.makeText(activity, R.string.popup_preview_unavailable, Toast.LENGTH_SHORT).show();
        }
        refreshStatus();
    }

    private void renderPreset() {
        String selected = preferences.getString(PopupStormSettings.K_PRESET, "CUSTOM");
        IntensityPresets preset = null;
        for (IntensityPresets candidate : IntensityPresets.values()) {
            if (candidate.name().equals(selected)) preset = candidate;
        }
        binding.presetSlider.setValue(preset == null ? 1 : preset.ordinal());
        binding.presetValue.setText(preset == null
                ? activity.getString(R.string.popup_custom_intensity) : preset.getDisplayName());
        binding.presetHint.setVisibility(preset == null ? View.VISIBLE : View.GONE);
    }

    private void applyPreset(int step) {
        if (!FeatureEditorSession.canEdit(activity, "popup")) return;
        flush();
        IntensityPresets.values()[step].apply(FeatureEditorSession.context(activity));
        renderPreset();
        rebuildSettings();
        if (!FeatureEditorSession.remote(activity)) PopupStormManager.get().reloadSettings(activity);
    }

    private void rebuildSettings() {
        caption = null;
        binding.dynamicSettings.removeAllViews();
        LinearLayout behavior = card(R.string.popup_behavior);
        addFloatSlider(behavior, R.string.popup_spawn_rate, PopupStormSettings.K_SPAWN_RATE,
                0, 8, 2, .1f, "%.1f");
        addFloatSlider(behavior, R.string.popup_display_duration,
                PopupStormSettings.K_DISPLAY_DURATION, .3f, 6, 1, .1f, "%.1f");
        addIntSlider(behavior, R.string.popup_max_simultaneous,
                PopupStormSettings.K_MAX_SIMULTANEOUS, 1, 15, 8);
        addChoice(behavior, R.string.popup_position, PopupStormSettings.K_POSITION_MODE,
                new String[]{"random", "center"},
                new int[]{R.string.popup_random, R.string.popup_center}, "random");
        addChoice(behavior, R.string.popup_size_mode, PopupStormSettings.K_SIZE_MODE,
                new String[]{"random", "fixed"},
                new int[]{R.string.popup_random, R.string.popup_fixed}, "random");
        addIntSlider(behavior, R.string.popup_min_size, PopupStormSettings.K_MIN_SIZE, 80, 800, 160);
        addIntSlider(behavior, R.string.popup_max_size, PopupStormSettings.K_MAX_SIZE, 80, 1200, 380);
        addIntSlider(behavior, R.string.popup_fixed_size, PopupStormSettings.K_FIXED_SIZE, 80, 1200, 260);
        addToggle(behavior, R.string.popup_random_rotation, PopupStormSettings.K_RANDOM_ROT, false);
        addIntSlider(behavior, R.string.popup_rotation_max, PopupStormSettings.K_ROT_MAX, 0, 90, 25);
        addIntSlider(behavior, R.string.popup_fade_in, PopupStormSettings.K_FADE_IN, 0, 2000, 100);
        addIntSlider(behavior, R.string.popup_fade_out, PopupStormSettings.K_FADE_OUT, 0, 3000, 200);
        addToggle(behavior, R.string.popup_bouncing, PopupStormSettings.K_BOUNCING, false);
        addIntSlider(behavior, R.string.popup_bouncing_speed,
                PopupStormSettings.K_BOUNCING_SPEED, 10, 600, 60);
        addToggle(behavior, R.string.popup_tap_dismiss,
                PopupStormSettings.K_CLICK_DISMISS_ALL, true);

        LinearLayout modes = card(R.string.popup_modes);
        addToggle(modes, R.string.popup_burst_mode, PopupStormSettings.K_BURST_ENABLED, false);
        addFloatSlider(modes, R.string.popup_burst_frequency,
                PopupStormSettings.K_BURST_FREQUENCY, 5, 120, 30, 1, "%.0f");
        addFloatSlider(modes, R.string.popup_burst_duration,
                PopupStormSettings.K_BURST_DURATION, 1, 15, 4, .5f, "%.1f");
        addFloatSlider(modes, R.string.popup_burst_multiplier,
                PopupStormSettings.K_BURST_MULTIPLIER, 1, 5, 3, .5f, "%.1f");
        addIntSlider(modes, R.string.popup_denial_chance,
                PopupStormSettings.K_DENIAL_CHANCE, 0, 100, 0);
        addChoice(modes, R.string.popup_denial_style, PopupStormSettings.K_DENIAL_STYLE,
                new String[]{"blur", "pixelate", "mixed"},
                new int[]{R.string.popup_blur, R.string.popup_pixelate, R.string.popup_mixed}, "blur");
        addIntSlider(modes, R.string.popup_denial_intensity,
                PopupStormSettings.K_DENIAL_INTENSITY, 0, 100, 50);
        addToggle(modes, R.string.popup_denial_caption, PopupStormSettings.K_DENIAL_CAPTION, true);
        addTextField(modes, R.string.popup_caption_text,
                PopupStormSettings.K_DENIAL_CAPTION_TEXT, "NO");

        LinearLayout detection = card(R.string.popup_detection);
        addChoice(detection, R.string.popup_detection_mode, PopupStormSettings.K_DETECTION_MODE,
                new String[]{"off", "cover", "avoid"},
                new int[]{R.string.popup_off, R.string.popup_cover, R.string.popup_avoid}, "off");
        addIntSlider(detection, R.string.popup_avoid_padding,
                PopupStormSettings.K_AVOID_PADDING, 0, 400, 80);
        rebuildFolders();
        refreshStatus();
        applyEditState();
    }

    public void applyEditState() {
        if (closed) return;
        boolean editing = FeatureEditorSession.canEdit(activity, "popup");
        renderEnabled();
        binding.buttonPreview.setEnabled(editing);
        binding.buttonAddFolder.setEnabled(editing);
        binding.presetSlider.setEnabled(editing);
        setEnabledRecursive(binding.dynamicSettings, editing);
        if (showAllSettings) updateAdvancedApplicability(editing);
        rebuildFolders();
        // Stop remains available as an unconditional safety action.
    }

    private void renderEnabled() {
        if (closed) return;
        updatingEnabled = true;
        binding.switchPopupStorm.setChecked(preferences.getBoolean(PopupStormSettings.K_ENABLED, false));
        binding.switchPopupStorm.setEnabled(FeatureEditorSession.canEdit(activity, "popup"));
        updatingEnabled = false;
    }

    private void setEffectEnabled(boolean enabled) {
        if (FeatureEditorSession.internal(activity) && enabled && !preferences.getBoolean(PopupStormSettings.K_ACK, false)) {
            if (!FeatureEditorSession.canEdit(activity, "popup")) { renderEnabled(); return; }
            renderEnabled();
            com.subhub.app.util.ThemedDialogs.builder(activity)
                    .setTitle(R.string.popup_photosensitivity_title).setMessage(R.string.popup_photosensitivity_body)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> renderEnabled())
                    .setPositiveButton(R.string.popup_acknowledge, (dialog, which) -> {
                        if (!FeatureEditorSession.canEdit(activity, "popup")) { renderEnabled(); return; }
                        preferences.edit().putBoolean(PopupStormSettings.K_ACK, true)
                                .putBoolean(PopupStormSettings.K_ENABLED, true).apply();
                        renderEnabled();
                    }).show();
            return;
        }
        if (FeatureEditorSession.remote(activity)) {
            if (FeatureEditorSession.canEdit(activity, "popup")) preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, enabled).apply();
            renderEnabled(); return;
        }
        if (!FeatureEditorSession.canEdit(activity, "popup")) { renderEnabled(); return; }
        if (!enabled) {
            preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, false).apply();
            PopupStormManager.get().stop();
            if (!FeatureEditorSession.remote(activity)) PopupStormManager.get().reloadSettings(activity);
            renderEnabled();
            refreshStatus();
        } else if (!preferences.getBoolean(PopupStormSettings.K_ACK, false)) {
            renderEnabled();
            com.subhub.app.util.ThemedDialogs.builder(activity)
                    .setTitle(R.string.popup_photosensitivity_title)
                    .setMessage(R.string.popup_photosensitivity_body)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> renderEnabled())
                    .setPositiveButton(R.string.popup_acknowledge, (dialog, which) -> {
                        if (!FeatureEditorSession.canEdit(activity, "popup")) { renderEnabled(); return; }
                        preferences.edit().putBoolean(PopupStormSettings.K_ACK, true).apply();
                        requestEnableOverlay();
                    }).show();
        } else requestEnableOverlay();
    }

    private void requestEnableOverlay() {
        if (FeatureEditorSession.remote(activity)) return;
        if (!FeatureEditorSession.canEdit(activity, "popup")) { renderEnabled(); return; }
        if (!Settings.canDrawOverlays(activity)) {
            renderEnabled();
            Toast.makeText(activity, R.string.popup_overlay_permission, Toast.LENGTH_LONG).show();
            localRequests.enableOverlay.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
        } else completeEnable();
    }

    private void completeEnable() {
        if (FeatureEditorSession.remote(activity)) return;
        if (!FeatureEditorSession.canEdit(activity, "popup") || !Settings.canDrawOverlays(activity)
                || !preferences.getBoolean(PopupStormSettings.K_ACK, false)) {
            renderEnabled();
            return;
        }
        preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, true).apply();
        if (!FeatureEditorSession.remote(activity)) PopupStormManager.get().reloadSettings(activity);
        if (PopupStormActivationPolicy.shouldStart(ScreenCaptureService.isRunning(),
                ScreenshotAccessibilityService.isRecognitionActive())) PopupStormManager.get().start(activity);
        renderEnabled();
        refreshStatus();
    }

    private static void setEnabledRecursive(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                setEnabledRecursive(group.getChildAt(index), enabled);
            }
        }
    }

    private void updateAdvancedApplicability(boolean editing) {
        boolean fixedSize = "fixed".equals(preferences.getString(PopupStormSettings.K_SIZE_MODE, "random"));
        setAdvancedEnabled(editing && !fixedSize, PopupStormSettings.K_MIN_SIZE, PopupStormSettings.K_MAX_SIZE);
        setAdvancedEnabled(editing && fixedSize, PopupStormSettings.K_FIXED_SIZE);
        setAdvancedEnabled(editing && preferences.getBoolean(PopupStormSettings.K_RANDOM_ROT, false),
                PopupStormSettings.K_ROT_MAX);
        setAdvancedEnabled(editing && preferences.getBoolean(PopupStormSettings.K_BOUNCING, false),
                PopupStormSettings.K_BOUNCING_SPEED);
        setAdvancedEnabled(editing && preferences.getBoolean(PopupStormSettings.K_BURST_ENABLED, false),
                PopupStormSettings.K_BURST_FREQUENCY, PopupStormSettings.K_BURST_DURATION,
                PopupStormSettings.K_BURST_MULTIPLIER);
        boolean denialEnabled = editing && preferences.getInt(PopupStormSettings.K_DENIAL_CHANCE, 0) > 0;
        setAdvancedEnabled(denialEnabled, PopupStormSettings.K_DENIAL_STYLE, PopupStormSettings.K_DENIAL_INTENSITY,
                PopupStormSettings.K_DENIAL_CAPTION);
        setAdvancedEnabled(denialEnabled && preferences.getBoolean(PopupStormSettings.K_DENIAL_CAPTION, true),
                PopupStormSettings.K_DENIAL_CAPTION_TEXT);
        setAdvancedEnabled(editing && "avoid".equals(preferences.getString(PopupStormSettings.K_DETECTION_MODE, "off")),
                PopupStormSettings.K_AVOID_PADDING);
    }

    private void setAdvancedEnabled(boolean enabled, String... keys) {
        for (String key : keys) {
            View control = binding.dynamicSettings.findViewWithTag("popup-setting:" + key);
            if (control == null) control = binding.dynamicSettings.findViewWithTag("popup-choice:" + key);
            if (control != null) setEnabledRecursive(control, enabled);
        }
    }

    private LinearLayout card(int title) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = binding.dynamicSettings.getChildCount() == 0 ? dp(8) : dp(14);
        card.setLayoutParams(params);
        TextView header = (TextView) activity.getLayoutInflater().inflate(R.layout.view_form_label, card, false);
        header.setText(title);
        card.addView(header);
        binding.dynamicSettings.addView(card);
        return card;
    }

    private TextView formLabel(String text, LinearLayout parent) {
        TextView label = (TextView) activity.getLayoutInflater().inflate(R.layout.view_form_label, parent, false);
        label.setText(text);
        return label;
    }

    private void addToggle(LinearLayout parent, int title, String key, boolean defaultValue) {
        SwitchMaterial toggle = new com.subhub.app.util.StateToggle(activity);
        toggle.setTag("popup-setting:" + key);
        toggle.setText(title);
        toggle.setEnabled(FeatureEditorSession.canEdit(activity, "popup"));
        toggle.setChecked(preferences.getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!FeatureEditorSession.canEdit(activity, "popup")) return;
            preferences.edit().putBoolean(key, checked).apply();
            settingsChanged();
        });
        parent.addView(toggle);
    }

    private void addIntSlider(LinearLayout parent, int title, String key,
            int minimum, int maximum, int defaultValue) {
        int stored = Math.max(minimum, Math.min(maximum, preferences.getInt(key, defaultValue)));
        TextView value = formLabel(activity.getString(title) + ": " + stored, parent);
        parent.addView(value);
        SeekBar slider = new SeekBar(activity);
        slider.setTag("popup-setting:" + key);
        slider.setMinimumHeight(dp(48));
        slider.setMax(maximum - minimum);
        slider.setProgress(stored - minimum);
        slider.setEnabled(FeatureEditorSession.canEdit(activity, "popup"));
        slider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int actual = minimum + progress;
                value.setText(activity.getString(title) + ": " + actual);
                if (fromUser && FeatureEditorSession.canEdit(activity, "popup")) {
                    preferences.edit().putInt(key, actual).apply();
                    if (showAllSettings && PopupStormSettings.K_DENIAL_CHANCE.equals(key))
                        updateAdvancedApplicability(true);
                }
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { settingsChanged(); }
        });
        parent.addView(slider);
    }

    private void addFloatSlider(LinearLayout parent, int title, String key, float minimum,
            float maximum, float defaultValue, float step, String format) {
        float stored = Math.max(minimum, Math.min(maximum, preferences.getFloat(key, defaultValue)));
        int steps = Math.round((maximum - minimum) / step);
        TextView value = formLabel(activity.getString(title) + ": " + String.format(Locale.ROOT, format, stored), parent);
        parent.addView(value);
        SeekBar slider = new SeekBar(activity);
        slider.setTag("popup-setting:" + key);
        slider.setMinimumHeight(dp(48));
        slider.setMax(steps);
        slider.setProgress(Math.round((stored - minimum) / step));
        slider.setEnabled(FeatureEditorSession.canEdit(activity, "popup"));
        slider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float actual = minimum + progress * step;
                value.setText(activity.getString(title) + ": " + String.format(Locale.ROOT, format, actual));
                if (fromUser && FeatureEditorSession.canEdit(activity, "popup")) preferences.edit().putFloat(key, actual).apply();
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { settingsChanged(); }
        });
        parent.addView(slider);
    }

    private void addChoice(LinearLayout parent, int title, String key, String[] values,
            int[] labels, String defaultValue) {
        TextView heading = formLabel(activity.getString(title), parent);
        parent.addView(heading);
        com.subhub.app.util.SelectionGrid group = new com.subhub.app.util.SelectionGrid(activity, null);
        group.setTag("popup-choice:" + key);
        group.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String selected = preferences.getString(key, defaultValue);
        for (int index = 0; index < values.length; index++) {
            Button option = new Button(activity, null, 0, R.style.Widget_SubHub_SegmentedButton);
            option.setId(View.generateViewId());
            option.setTag(values[index]);
            option.setText(labels[index]);
            option.setEnabled(FeatureEditorSession.canEdit(activity, "popup"));
            option.setSelected(values[index].equals(selected));
            option.setMinHeight(dp(48));
            option.setMinimumHeight(dp(48));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(2), dp(2), dp(2), dp(2));
            group.addView(option, params);
            option.setOnClickListener(view -> {
                if (!FeatureEditorSession.canEdit(activity, "popup")) return;
                for (int child = 0; child < group.getChildCount(); child++) {
                    group.getChildAt(child).setSelected(group.getChildAt(child) == view);
                }
                preferences.edit().putString(key, String.valueOf(view.getTag())).apply();
                settingsChanged();
            });
        }
        parent.addView(group);
    }

    private void addTextField(LinearLayout parent, int title, String key, String defaultValue) {
        TextView heading = formLabel(activity.getString(title), parent);
        parent.addView(heading);
        EditText input = (EditText) activity.getLayoutInflater().inflate(R.layout.view_preference_input, parent, false);
        input.setId(View.generateViewId());
        input.setTag("popup-setting:" + key);
        heading.setLabelFor(input.getId());
        input.setMinimumHeight(dp(48));
        input.setSingleLine(true);
        input.setMaxLines(1);
        com.subhub.app.util.InputBounds.limit(input, 32);
        input.setText(preferences.getString(key, defaultValue));
        caption = input;
        if (FeatureEditorSession.remote(activity)) input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                saveText(input, key, defaultValue);
            }
            @Override public void afterTextChanged(android.text.Editable value) { }
        });
        com.subhub.app.util.UiIdentity.textSize(input, R.dimen.ui_text_body);
        input.setEnabled(FeatureEditorSession.canEdit(activity, "popup"));
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        View.OnFocusChangeListener save = (view, focused) -> {
            if (!focused) saveText(input, key, defaultValue);
        };
        input.setOnFocusChangeListener(save);
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveText(input, key, defaultValue);
                input.clearFocus();
                return true;
            }
            return false;
        });
        parent.addView(input);
    }

    private void saveText(EditText input, String key, String defaultValue) {
        if (!FeatureEditorSession.canEdit(activity, "popup")) return;
        String value = input.getText().toString().trim();
        if (value.isEmpty()) value = defaultValue;
        if (value.length() > 32) value = value.substring(0, 32);
        if (value.equals(preferences.getString(key, defaultValue))) return;
        preferences.edit().putString(key, value).apply();
        settingsChanged();
    }

    private void settingsChanged() {
        if (!FeatureEditorSession.canEdit(activity, "popup")) return;
        preferences.edit().remove(PopupStormSettings.K_PRESET).apply();
        renderPreset();
        if (showAllSettings) updateAdvancedApplicability(true);
        if (!FeatureEditorSession.remote(activity)) PopupStormManager.get().reloadSettings(activity);
        refreshStatus();
    }

    void onFolderPicked(Uri uri) {
        if (uri == null || FeatureEditorSession.remote(activity)
                || !FeatureEditorSession.canEdit(activity, "popup")) return;
        try {
            activity.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            Toast.makeText(activity, R.string.popup_preview_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> folders = new LinkedHashSet<>(preferences.getStringSet(
                PopupStormSettings.K_FOLDERS, Set.of()));
        if (folders.size() < 20) folders.add(uri.toString());
        preferences.edit().putStringSet(PopupStormSettings.K_FOLDERS, folders).apply();
        if (!FeatureEditorSession.remote(activity)) PopupStormManager.get().reloadSettings(activity);
        rebuildFolders();
        binding.libraryCount.setText(R.string.popup_library_scanning);
        mainDelayRefresh();
    }

    private void rebuildFolders() {
        if (FeatureEditorSession.remote(activity)) { if (remoteArtwork != null) remoteArtwork.refresh(); return; }
        binding.foldersList.removeAllViews();
        Set<String> folders = preferences.getStringSet(PopupStormSettings.K_FOLDERS, Set.of());
        if (folders == null || folders.isEmpty()) {
            TextView empty = label(activity.getString(R.string.popup_no_folders), 11, false, R.color.text_muted);
            empty.setPadding(0, dp(10), 0, 0);
            binding.foldersList.addView(empty);
        } else {
            for (String value : new ArrayList<>(folders)) {
                LinearLayout row = new LinearLayout(activity);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = label(prettyFolder(value), 11, false, R.color.text_secondary);
                name.setMinimumHeight(dp(48));
                row.addView(name, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button remove = new Button(activity);
                remove.setText(R.string.popup_remove);
                remove.setAllCaps(false);
                remove.setBackgroundResource(R.drawable.bg_outline_button);
                remove.setTextColor(activity.getColorStateList(R.color.outline_button_text));
                remove.setEnabled(FeatureEditorSession.canEdit(activity, "popup"));
                remove.setOnClickListener(view -> removeFolder(value));
                remove.setMinHeight(dp(48));
                remove.setMinimumHeight(dp(48));
                row.addView(remove, new LinearLayout.LayoutParams(
                        dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));
                binding.foldersList.addView(row);
            }
        }
        int images = Math.max(1, PopupStormManager.get().libraryImageCount());
        binding.libraryCount.setText(activity.getResources().getQuantityString(
                R.plurals.popup_library_count, images, images,
                folders == null ? 0 : folders.size()));
    }

    private void removeFolder(String value) {
        if (FeatureEditorSession.remote(activity) || !FeatureEditorSession.canEdit(activity, "popup")) return;
        Set<String> folders = new LinkedHashSet<>(preferences.getStringSet(
                PopupStormSettings.K_FOLDERS, Set.of()));
        folders.remove(value);
        preferences.edit().putStringSet(PopupStormSettings.K_FOLDERS, folders).apply();
        try { activity.getContentResolver().releasePersistableUriPermission(
                Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) {}
        if (!FeatureEditorSession.remote(activity)) PopupStormManager.get().reloadSettings(activity);
        rebuildFolders();
        mainDelayRefresh();
    }

    private String prettyFolder(String value) {
        try {
            String last = Uri.parse(value).getLastPathSegment();
            return last == null ? value : Uri.decode(last).replace("primary:", "");
        } catch (Exception ignored) { return value; }
    }

    private void refreshStatus() {
        if (FeatureEditorSession.remote(activity)) return;
        PopupStormManager manager = PopupStormManager.get();
        CharSequence label = manager.isPreviewing()
                ? activity.getString(R.string.popup_preview_active, (int) ((manager.remainingPreviewMillis() + 999L) / 1000L))
                : activity.getText(R.string.popup_preview);
        if (!TextUtils.equals(binding.buttonPreview.getText(), label)) binding.buttonPreview.setText(label);
    }

    private void mainDelayRefresh() {
        binding.getRoot().postDelayed(() -> {
            if (closed) return;
            rebuildFolders();
            refreshStatus();
        }, 450);
    }

    private TextView label(String text, float size, boolean bold, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        com.subhub.app.util.UiIdentity.textSize(view, size == 12
                ? R.dimen.ui_text_label : R.dimen.ui_text_caption);
        view.setTextColor(activity.getColor(color));
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @Override public void close() {
        closed = true;
        ui.removeCallbacksAndMessages(null);
        if (remoteArtwork != null) remoteArtwork.close();
    }

    /** Registers the physical device's picker and overlay flows before the host starts. */
    public static final class LocalRequests {
        private final ActivityResultLauncher<Uri> folder;
        private final ActivityResultLauncher<Intent> previewOverlay;
        private final ActivityResultLauncher<Intent> enableOverlay;

        public LocalRequests(AppCompatActivity host, Supplier<PopupStormEditorPanel> panel) {
            folder = host.registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), result -> {
                PopupStormEditorPanel current = panel.get();
                if (current != null) current.onFolderPicked(result);
            });
            previewOverlay = host.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), ignored -> {
                PopupStormEditorPanel current = panel.get();
                if (current != null) current.overlayResult(false);
            });
            enableOverlay = host.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), ignored -> {
                PopupStormEditorPanel current = panel.get();
                if (current != null) current.overlayResult(true);
            });
        }
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
    }
}
