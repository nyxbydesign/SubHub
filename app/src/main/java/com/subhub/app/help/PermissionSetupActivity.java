package com.subhub.app.help;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.databinding.ActivityPermissionSetupBinding;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.PrimaryHeader;

/** Android retains authority over restricted settings and Accessibility consent. */
public final class PermissionSetupActivity extends AppCompatActivity {
    private ActivityPermissionSetupBinding binding;
    private int availableStep = 1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null)
            availableStep = Math.max(1, Math.min(3, state.getInt("permission_guide_step", 1)));
        binding = ActivityPermissionSetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.permission_setup_title, true);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        PrimaryHeader.editLockButton(binding.getRoot())
                .setOnClickListener(
                        view -> {
                            if (ControllerPinManager.isDomModeActive()) {
                                ControllerPinManager.enterSubMode();
                                renderGuide();
                            } else ControllerPinGate.unlock(this, this::renderGuide, false);
                        });
        int[] titles = {
            R.id.permission_step_one_title,
            R.id.permission_step_two_title,
            R.id.permission_step_three_title
        };
        int[] labels = {
            R.string.permission_setup_step_one,
            R.string.permission_setup_step_two,
            R.string.permission_setup_step_three
        };
        for (int index = 0; index < titles.length; index++) {
            android.view.View title = findViewById(titles[index]);
            androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
            title.setContentDescription(
                    getString(
                            R.string.permission_setup_step_accessibility,
                            index + 1,
                            getString(labels[index])));
        }
        binding.permissionStepOneOpen.setOnClickListener(
                view -> openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 1));
        binding.permissionStepTwoOpen.setOnClickListener(
                view ->
                        openSettings(
                                new Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + getPackageName())),
                                2));
        binding.permissionStepThreeOpen.setOnClickListener(
                view -> openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 3));
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderGuide();
    }

    private void renderGuide() {
        if (binding == null) return;
        boolean editing = actionsAllowed();
        ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(binding.getRoot()));
        binding.permissionStepOneOpen.setEnabled(true);
        binding.permissionStepTwoOpen.setEnabled(availableStep >= 2);
        binding.permissionStepThreeOpen.setEnabled(availableStep >= 3);
        binding.permissionStepOneOpen.setAlpha(editing ? 1f : .45f);
        binding.permissionStepTwoOpen.setAlpha(editing ? 1f : .45f);
        binding.permissionStepThreeOpen.setAlpha(editing ? 1f : .45f);
        binding.permissionStepTwo.setAlpha(availableStep >= 2 ? 1f : .5f);
        binding.permissionStepThree.setAlpha(availableStep >= 3 ? 1f : .5f);
        binding.permissionSetupStatus.setText(
                new AppModeManager(this).isAccessibilityEnabled()
                        ? R.string.permission_setup_allowed
                        : R.string.permission_setup_not_allowed);
    }

    private boolean actionsAllowed() {
        // First-run setup already owns permission requests, before its Dom handoff is complete.
        return ControllerPinManager.isDomModeActive()
                || (com.subhub.app.onboarding.OnboardingState.inProgress(this)
                        && !com.subhub.app.onboarding.OnboardingState.completed(this));
    }

    private void openSettings(Intent intent, int step) {
        if (!actionsAllowed()) { ControllerPinGate.notifyLocked(this); return; }
        if (availableStep < step) return;
        try {
            startActivity(intent);
            availableStep = Math.max(availableStep, Math.min(3, step + 1));
            renderGuide();
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(this, R.string.permission_setup_settings_unavailable, Toast.LENGTH_LONG)
                    .show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("permission_guide_step", availableStep);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
