package com.subhub.app.atmosphere;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.databinding.ActivityAtmosphereBinding;
import com.subhub.app.popup.IntensityPresets;
import com.subhub.app.popup.PopupStormActivity;
import com.subhub.app.popup.PopupStormActivationPolicy;
import com.subhub.app.popup.PopupStormManager;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.subliminal.SubliminalSettings;
import com.subhub.app.subliminal.SubliminalSettingsActivity;
import com.subhub.app.subliminal.SubliminalSettingsRepository;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

import java.util.Locale;

/** Focused editor for optional on-screen atmosphere effects. */
public final class AtmosphereActivity extends AppCompatActivity {
    private ActivityAtmosphereBinding binding;
    private boolean rendering;
    private final ActivityResultLauncher<Intent> overlayPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Settings.canDrawOverlays(this)) completePopupEnable();
                else render();
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAtmosphereBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bind(binding.getRoot(), R.drawable.ic_atmosphere,
                R.string.atmosphere_title, 0);
        if (SubHubNavigation.redirectIfDisabled(this, SubHubNavigation.Screen.ATMOSPHERE)) return;
        PrimaryHeader.editLockButton(binding.getRoot()).setOnClickListener(view -> toggleSpace());
        binding.buttonKeyholder.setOnClickListener(view -> startActivity(new Intent(this, com.subhub.app.security.AuthenticatorActivity.class)));
        binding.ritualsKeyholderCard.setOnClickListener(view -> startActivity(new Intent(this, com.subhub.app.security.AuthenticatorActivity.class)));
        binding.whispersCard.setOnClickListener(view -> openWhispers());
        binding.buttonWhispers.setOnClickListener(view -> openWhispers());
        binding.popupStormCard.setOnClickListener(view -> openPopupStorm());
        binding.buttonPopupStorm.setOnClickListener(view -> openPopupStorm());
        binding.switchWhispers.setOnCheckedChangeListener((button, checked) -> {
            if (!rendering) setWhispersEnabled(checked);
        });
        binding.switchPopupStorm.setOnCheckedChangeListener((button, checked) -> {
            if (!rendering) setPopupEnabled(checked);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void toggleSpace() {
        if (ControllerPinManager.isDomModeActive()) {
            ControllerEditMode.enterSubMode(this);
        } else {
            ControllerPinGate.require(this, this::render, false);
        }
    }

    private void openWhispers() {
        requireDom(() -> startActivity(new Intent(this, SubliminalSettingsActivity.class)));
    }

    private void openPopupStorm() {
        requireDom(() -> startActivity(new Intent(this, PopupStormActivity.class)));
    }

    private void requireDom(Runnable action) {
        if (ControllerPinManager.isDomModeActive()) action.run();
        else ControllerPinGate.require(this, () -> {
            render();
            action.run();
        }, false);
    }

    private void render() {
        boolean pin = ControllerPinManager.isConfigured(this), auth = new com.subhub.app.security.ControllerAuthenticator(this).isPaired();
        binding.ritualsKeyholderStatus.setText(pin && auth ? R.string.keyholder_status_both : pin ? R.string.keyholder_status_pin
                : auth ? R.string.keyholder_status_auth : R.string.keyholder_status_none);
        boolean dom = ControllerPinManager.isSessionUnlocked();
        FeatureModuleManager modules = new FeatureModuleManager(this);
        PopupStormSettings popup = PopupStormSettings.load(this);
        rendering = true;
        binding.switchWhispers.setChecked(modules.isSubliminalEnabled());
        binding.switchPopupStorm.setChecked(popup.isEnabled());
        rendering = false;
        binding.switchWhispers.setEnabled(dom);
        binding.switchPopupStorm.setEnabled(dom);
        ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(binding.getRoot()));
        binding.buttonWhispers.setText(dom
                ? R.string.atmosphere_shape_whispers
                : R.string.atmosphere_unlock_to_edit);
        binding.buttonPopupStorm.setText(dom
                ? R.string.atmosphere_open_popup_storm : R.string.atmosphere_unlock_to_edit);
        renderWhispers();
        renderPopupStorm();
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.ATMOSPHERE);
    }

    private void setWhispersEnabled(boolean enabled) {
        if (!ControllerPinManager.isSessionUnlocked()) {
            render();
            return;
        }
        FeatureModuleManager modules = new FeatureModuleManager(this);
        modules.setSubliminalEnabled(enabled);
        render();
    }

    private void setPopupEnabled(boolean enabled) {
        if (!ControllerPinManager.isSessionUnlocked()) {
            render();
            return;
        }
        if (!enabled) {
            PopupStormSettings.preferences(this).edit()
                    .putBoolean(PopupStormSettings.K_ENABLED, false).apply();
            PopupStormManager.get().stop();
            PopupStormManager.get().reloadSettings(this);
            render();
            return;
        }
        if (!PopupStormSettings.preferences(this).getBoolean(PopupStormSettings.K_ACK, false)) {
            rendering = true;
            binding.switchPopupStorm.setChecked(false);
            rendering = false;
            com.subhub.app.util.ThemedDialogs.builder(this)
                    .setTitle(R.string.popup_photosensitivity_title)
                    .setMessage(R.string.popup_photosensitivity_body)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> render())
                    .setPositiveButton(R.string.popup_acknowledge, (dialog, which) -> {
                        PopupStormSettings.preferences(this).edit()
                                .putBoolean(PopupStormSettings.K_ACK, true).apply();
                        requestPopupOverlayOrComplete();
                    }).show();
            return;
        }
        requestPopupOverlayOrComplete();
    }

    private void requestPopupOverlayOrComplete() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.popup_overlay_permission, Toast.LENGTH_LONG).show();
            overlayPermission.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } else {
            completePopupEnable();
        }
    }

    private void completePopupEnable() {
        PopupStormSettings.preferences(this).edit()
                .putBoolean(PopupStormSettings.K_ENABLED, true).apply();
        PopupStormManager.get().reloadSettings(this);
        if (PopupStormActivationPolicy.shouldStart(
                ScreenCaptureService.isRunning(),
                ScreenshotAccessibilityService.isRecognitionActive())) {
            PopupStormManager.get().start(this);
        }
        render();
    }

    private void renderWhispers() {
        FeatureModuleManager modules = new FeatureModuleManager(this);
        if (!modules.isSubliminalEnabled()) {
            binding.whispersStatus.setText(R.string.atmosphere_state_disabled);
            binding.whispersSummary.setText(R.string.sub_subliminal_idle);
            return;
        }
        long now = System.currentTimeMillis();
        AppModeManager appMode = new AppModeManager(this);
        int state = !appMode.isAccessibilityEnabled()
                ? R.string.atmosphere_state_setup
                : appMode.isEffectivelyArmed(now)
                ? R.string.atmosphere_state_active : R.string.atmosphere_state_ready;
        binding.whispersStatus.setText(state);
        SubliminalSettings settings = new SubliminalSettingsRepository(this).load();
        binding.whispersSummary.setText(appMode.getMode() == com.subhub.app.appmode.AppModePolicy.Mode.ALWAYS
                ? getString(R.string.atmosphere_whispers_all_apps, friendly(settings.getPreset().name()))
                : getString(R.string.atmosphere_whispers_summary,
                        friendly(settings.getPreset().name()), appMode.getSubliminalPackages().size()));
    }

    private void renderPopupStorm() {
        PopupStormSettings settings = PopupStormSettings.load(this);
        int state = PopupStormManager.get().isRunning()
                ? R.string.popup_status_running
                : !settings.isEnabled()
                ? R.string.popup_status_off
                : !settings.isAcknowledged()
                ? R.string.popup_status_ack
                : !Settings.canDrawOverlays(this)
                ? R.string.popup_status_permission : R.string.popup_status_ready;
        binding.popupStormStatus.setText(state);
        String presetName = PopupStormSettings.preferences(this).getString(
                PopupStormSettings.K_PRESET, IntensityPresets.MEDIUM.name());
        String preset;
        try {
            preset = IntensityPresets.valueOf(presetName).getDisplayName();
        } catch (IllegalArgumentException ignored) {
            preset = IntensityPresets.MEDIUM.getDisplayName();
        }
        int sources = settings.getFolders().size()
                + (settings.getPackImageDir().isEmpty() ? 0 : 1);
        binding.popupStormSummary.setText(getResources().getQuantityString(
                R.plurals.atmosphere_popup_summary, sources, preset, sources));
    }

    private static String friendly(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.isEmpty() ? lower
                : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
