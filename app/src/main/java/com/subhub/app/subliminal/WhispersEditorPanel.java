package com.subhub.app.subliminal;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.SeekBar;

import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.atmosphere.AtmosphereDisclosure;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.databinding.PanelWhispersBinding;
import com.subhub.app.settings.FeatureModuleManager;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Whispers controls shared by the standalone and Contract editors. */
public final class WhispersEditorPanel implements AutoCloseable {
    private final AppCompatActivity activity;
    private final PanelWhispersBinding binding;
    private final SubliminalSettingsRepository repository;
    private final FeatureModuleManager modules;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private boolean loading;
    private boolean customChanged;
    private boolean showAllSettings;
    private final Runnable saveCustom = this::flush;

    public WhispersEditorPanel(AppCompatActivity activity, View root, boolean advancedExpanded) {
        this.activity = activity;
        binding = PanelWhispersBinding.bind(root);
        binding.opacity.setMax(SubliminalSettings.MAX_OPACITY_PERCENT - 1);
        repository = new SubliminalSettingsRepository(FeatureEditorSession.context(activity));
        modules = new FeatureModuleManager(FeatureEditorSession.context(activity));
        com.subhub.app.util.InputBounds.limit(binding.customPhrases, com.subhub.app.util.InputBounds.SUBLIMINAL_PHRASES);
        AtmosphereDisclosure.bind(binding.whispersAdvancedButton, binding.whispersAdvancedContent, advancedExpanded);
        bindListeners();
        render(repository.load());
    }

    public boolean advancedExpanded() {
        return binding.whispersAdvancedContent.getVisibility() == View.VISIBLE;
    }

    public void showAllSettings() {
        showAllSettings = true;
        binding.whispersAdvancedButton.setVisibility(View.GONE);
        binding.whispersAdvancedContent.setVisibility(View.VISIBLE);
        render(repository.load());
    }

    public boolean flush() {
        main.removeCallbacks(saveCustom);
        if (customChanged && canEdit()) {
            customChanged = false;
            repository.saveCustomPhrases(binding.customPhrases.getText().toString());
        }
        return true;
    }

    public void resume() {
        flush();
        render(repository.load());
    }

    private void refreshAccess() {
        boolean editable = canEdit();
        binding.switchWhispers.setEnabled(editable);
        binding.whispersPresetSlider.setEnabled(editable);
        binding.whispersPreview.setEnabled(editable);
        binding.advancedEnabled.setEnabled(editable);
        for (SeekBar bar : new SeekBar[] {binding.opacity, binding.duration, binding.minInterval,
                binding.maxInterval, binding.textSize}) bar.setEnabled(editable && binding.advancedEnabled.isChecked());
        for (CheckBox pack : packChecks()) pack.setEnabled(editable);
        binding.customPhrases.setEnabled(editable && binding.packCustom.isChecked());
    }

    private boolean canEdit() {
        return FeatureEditorSession.canEdit(activity, "subliminal");
    }

    private void renderCustomPhrases() {
        boolean selected = binding.packCustom.isChecked();
        binding.customPhrasesSection.setVisibility(showAllSettings || selected ? View.VISIBLE : View.GONE);
        binding.customPhrases.setEnabled(canEdit() && selected);
    }

    private void bindListeners() {
        binding.switchWhispers.setOnCheckedChangeListener((button, checked) -> {
            if (loading || !canEdit()) return;
            flush();
            modules.setSubliminalEnabled(checked);
            render(repository.load());
        });
        binding.whispersPresetSlider.setLabelFormatter(value -> presetLabel(Math.round(value)));
        binding.whispersPresetSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (loading || !fromUser || !canEdit()) return;
            flush();
            repository.savePreset(SubliminalSettings.Preset.values()[Math.round(value)]);
            render(repository.load());
        });
        binding.advancedEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (loading || !canEdit()) return;
            flush();
            saveAdvanced(checked);
            render(repository.load());
        });
        SeekBar.OnSeekBarChangeListener advancedListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && !loading) {
                    saveAdvanced(true);
                    updateAdvancedLabels();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        };
        binding.opacity.setOnSeekBarChangeListener(advancedListener);
        binding.duration.setOnSeekBarChangeListener(advancedListener);
        binding.minInterval.setOnSeekBarChangeListener(advancedListener);
        binding.maxInterval.setOnSeekBarChangeListener(advancedListener);
        binding.textSize.setOnSeekBarChangeListener(advancedListener);
        for (CheckBox check : packChecks()) {
            check.setOnCheckedChangeListener((button, checked) -> {
                if (!loading && canEdit()) {
                    repository.savePacks(selectedPacks());
                    renderCustomPhrases();
                }
            });
        }
        binding.customPhrases.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (loading || !canEdit()) return;
                customChanged = true;
                FeatureEditorSession.editorInputChanged(activity, "subliminal");
                main.removeCallbacks(saveCustom);
                main.postDelayed(saveCustom, 400L);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        binding.whispersPreview.setOnClickListener(view -> preview());
    }

    private void render(SubliminalSettings settings) {
        loading = true;
        binding.switchWhispers.setChecked(modules.isSubliminalEnabled());
        binding.whispersPresetSlider.setValue(settings.getPreset().ordinal());
        binding.whispersPresetValue.setText(presetLabel(settings.getPreset().ordinal()));
        binding.advancedEnabled.setChecked(settings.isAdvanced());
        binding.advancedPanel.setVisibility(showAllSettings || settings.isAdvanced() ? View.VISIBLE : View.GONE);
        binding.opacity.setProgress(settings.getOpacityPercent() - 1);
        binding.duration.setProgress((int) ((settings.getVisibleMillis() - 800L) / 100L));
        binding.minInterval.setProgress((int) (settings.getMinimumIntervalMillis() / 1_000L) - 5);
        binding.maxInterval.setProgress((int) (settings.getMaximumIntervalMillis() / 1_000L) - 5);
        binding.textSize.setProgress(settings.getTextSizeSp() - 14);
        Set<String> packs = settings.getEnabledPacks();
        binding.packObedience.setChecked(packs.contains(SubliminalSettingsRepository.PACK_OBEDIENCE));
        binding.packFocus.setChecked(packs.contains(SubliminalSettingsRepository.PACK_FOCUS));
        binding.packBeta.setChecked(packs.contains(SubliminalSettingsRepository.PACK_BETA));
        binding.packFindom.setChecked(packs.contains(SubliminalSettingsRepository.PACK_FINDOM));
        binding.packCustom.setChecked(packs.contains(SubliminalSettingsRepository.PACK_CUSTOM));
        renderCustomPhrases();
        if (!binding.customPhrases.getText().toString().equals(settings.getCustomPhrases())) {
            binding.customPhrases.setText(settings.getCustomPhrases());
        }
        updateAdvancedLabels();
        refreshAccess();
        loading = false;
    }

    private void saveAdvanced(boolean enabled) {
        if (!canEdit()) return;
        long minimum = (binding.minInterval.getProgress() + 5L) * 1_000L;
        long maximum = (binding.maxInterval.getProgress() + 5L) * 1_000L;
        if (maximum < minimum) maximum = minimum;
        repository.saveAdvanced(enabled, binding.opacity.getProgress() + 1,
                800L + binding.duration.getProgress() * 100L, minimum, maximum,
                binding.textSize.getProgress() + 14);
    }

    private void updateAdvancedLabels() {
        if (binding == null) return;
        binding.opacityLabel.setText(activity.getString(R.string.subliminal_opacity_value,
                binding.opacity.getProgress() + 1));
        binding.durationLabel.setText(activity.getString(R.string.subliminal_duration_value,
                (800L + binding.duration.getProgress() * 100L) / 1_000f));
        binding.minIntervalLabel.setText(activity.getString(R.string.subliminal_min_interval_value,
                binding.minInterval.getProgress() + 5));
        binding.maxIntervalLabel.setText(activity.getString(R.string.subliminal_max_interval_value,
                binding.maxInterval.getProgress() + 5));
        binding.textSizeLabel.setText(activity.getString(R.string.subliminal_text_size_value,
                binding.textSize.getProgress() + 14));
    }

    private void preview() {
        if (!canEdit()) return;
        binding.previewStage.setVisibility(View.VISIBLE);
        flush();
        SubliminalSettings settings = repository.load();
        List<String> phrases = repository.phrases(settings);
        if (phrases.isEmpty()) {
            binding.previewText.setText(R.string.subliminal_preview_empty);
        } else binding.previewText.setText(phrases.get(random.nextInt(phrases.size())));
        binding.previewText.animate().cancel();
        binding.previewText.setTextSize(settings.getTextSizeSp());
        binding.previewText.setAlpha(0f);
        float alpha = Math.max(0.18f, settings.getOpacityPercent() / 100f);
        binding.previewText.animate().alpha(alpha).setDuration(300L)
                .withEndAction(() -> binding.previewText.animate().alpha(0f)
                        .setStartDelay(Math.max(0L, settings.getVisibleMillis() - 600L))
                        .setDuration(300L).withEndAction(() -> {
                            binding.previewStage.setVisibility(View.GONE);
                        }).start()).start();
    }

    private Set<String> selectedPacks() {
        Set<String> packs = new LinkedHashSet<>();
        if (binding.packObedience.isChecked()) packs.add(SubliminalSettingsRepository.PACK_OBEDIENCE);
        if (binding.packFocus.isChecked()) packs.add(SubliminalSettingsRepository.PACK_FOCUS);
        if (binding.packBeta.isChecked()) packs.add(SubliminalSettingsRepository.PACK_BETA);
        if (binding.packFindom.isChecked()) packs.add(SubliminalSettingsRepository.PACK_FINDOM);
        if (binding.packCustom.isChecked()) packs.add(SubliminalSettingsRepository.PACK_CUSTOM);
        return packs;
    }

    private CheckBox[] packChecks() {
        return new CheckBox[] {binding.packObedience, binding.packFocus, binding.packBeta,
                binding.packFindom, binding.packCustom};
    }

    private String presetLabel(int step) {
        int[] labels = {R.string.subliminal_preset_gentle, R.string.subliminal_preset_normal,
                R.string.subliminal_preset_strict, R.string.subliminal_preset_ultra};
        return activity.getString(labels[step]);
    }

    public void stopPreview() {
        binding.previewText.animate().cancel();
        binding.previewStage.setVisibility(View.GONE);
    }

    @Override public void close() {
        main.removeCallbacksAndMessages(null);
        stopPreview();
    }
}
