package com.subhub.app.subliminal;

import com.subhub.app.util.PrimaryHeader;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.SeekBar;

import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.databinding.ActivitySubliminalSettingsBinding;
import com.subhub.app.security.ControllerPinManager;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Dom-only configuration for the local subliminal module. */
public final class SubliminalSettingsActivity extends AppCompatActivity {
    private ActivitySubliminalSettingsBinding binding;
    private SubliminalSettingsRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private boolean loading;
    private final Runnable saveCustom = () -> {
        if (binding != null && !loading) repository.saveCustomPhrases(
                binding.customPhrases.getText() == null ? ""
                        : binding.customPhrases.getText().toString());
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ControllerPinManager.isDomModeActive()) {
            finish();
            return;
        }
        binding = ActivitySubliminalSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.subliminal_title, false);
        repository = new SubliminalSettingsRepository(this);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        android.widget.LinearLayout page = (android.widget.LinearLayout) binding.intensityCard.getParent();
        page.removeView(binding.messagePacksCard);
        page.addView(binding.messagePacksCard, page.indexOfChild(binding.intensityCard));
        bindListeners();
        render(repository.load());
    }

    private void bindListeners() {
        binding.presetSlider.setLabelFormatter(value -> presetLabel(Math.round(value)));
        binding.presetSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (loading || !fromUser) return;
            main.removeCallbacks(saveCustom);
            saveCustom.run();
            repository.savePreset(SubliminalSettings.Preset.values()[Math.round(value)]);
            render(repository.load());
        });
        binding.advancedEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (loading) return;
            main.removeCallbacks(saveCustom);
            saveCustom.run();
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
                if (!loading) {
                    repository.savePacks(selectedPacks());
                    binding.customPhrasesSection.setVisibility(binding.packCustom.isChecked() ? View.VISIBLE : View.GONE);
                }
            });
        }
        binding.customPhrases.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (loading) return;
                main.removeCallbacks(saveCustom);
                main.postDelayed(saveCustom, 400L);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        binding.buttonPreview.setOnClickListener(view -> preview());
    }

    private void render(SubliminalSettings settings) {
        if (binding == null) return;
        loading = true;
        binding.presetSlider.setValue(settings.getPreset().ordinal());
        binding.presetValue.setText(presetLabel(settings.getPreset().ordinal()));
        binding.advancedEnabled.setChecked(settings.isAdvanced());
        binding.advancedPanel.setVisibility(settings.isAdvanced() ? View.VISIBLE : View.GONE);
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
        binding.customPhrasesSection.setVisibility(binding.packCustom.isChecked() ? View.VISIBLE : View.GONE);
        if (!binding.customPhrases.getText().toString().equals(settings.getCustomPhrases())) {
            binding.customPhrases.setText(settings.getCustomPhrases());
        }
        updateAdvancedLabels();
        loading = false;
    }

    private void saveAdvanced(boolean enabled) {
        long minimum = (binding.minInterval.getProgress() + 5L) * 1_000L;
        long maximum = (binding.maxInterval.getProgress() + 5L) * 1_000L;
        if (maximum < minimum) maximum = minimum;
        repository.saveAdvanced(enabled, binding.opacity.getProgress() + 1,
                800L + binding.duration.getProgress() * 100L, minimum, maximum,
                binding.textSize.getProgress() + 14);
    }

    private void updateAdvancedLabels() {
        if (binding == null) return;
        binding.opacityLabel.setText(getString(R.string.subliminal_opacity_value,
                binding.opacity.getProgress() + 1));
        binding.durationLabel.setText(getString(R.string.subliminal_duration_value,
                (800L + binding.duration.getProgress() * 100L) / 1_000f));
        binding.minIntervalLabel.setText(getString(R.string.subliminal_min_interval_value,
                binding.minInterval.getProgress() + 5));
        binding.maxIntervalLabel.setText(getString(R.string.subliminal_max_interval_value,
                binding.maxInterval.getProgress() + 5));
        binding.textSizeLabel.setText(getString(R.string.subliminal_text_size_value,
                binding.textSize.getProgress() + 14));
    }

    private void preview() {
        binding.previewStage.setVisibility(View.VISIBLE);
        main.removeCallbacks(saveCustom);
        saveCustom.run();
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
                            if (binding != null) binding.previewStage.setVisibility(View.GONE);
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
        return getString(labels[step]);
    }

    @Override protected void onPause() {
        main.removeCallbacks(saveCustom);
        saveCustom.run();
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        binding = null;
        super.onDestroy();
    }
}
