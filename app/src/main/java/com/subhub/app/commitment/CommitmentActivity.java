package com.subhub.app.commitment;

import com.subhub.app.util.PrimaryHeader;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.databinding.ActivityCommitmentBinding;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsActivity;

import java.util.Locale;

/** Read-only pact countdown with a Dom recovery release. Pacts start from Sub Home. */
public final class CommitmentActivity extends AppCompatActivity {
    public static final String EXTRA_DURATION_MS = "commitment_duration_ms";
    private ActivityCommitmentBinding binding;
    private ControllerEditMode editMode;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            renderState();
            if (CommitmentManager.isActive(CommitmentActivity.this)) {
                timer.postDelayed(this, 1000L);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCommitmentBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.commitment_title, true);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        binding.buttonEmergencyRelease.setOnClickListener(view -> confirmEmergencyRelease());
        editMode = ControllerEditMode.bind(
                this, PrimaryHeader.editLockButton(binding.getRoot()), editing -> applyEditState());
        renderState();
    }

    @Override protected void onResume() {
        super.onResume();
        if (editMode != null) editMode.refresh();
        timer.removeCallbacks(tick);
        timer.post(tick);
    }

    @Override protected void onPause() {
        timer.removeCallbacks(tick);
        super.onPause();
    }

    private void confirmEmergencyRelease() {
        com.subhub.app.util.ThemedDialogs.builder(this)
                .setTitle(R.string.commitment_emergency_title)
                .setMessage(R.string.commitment_emergency_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.commitment_release_now, (dialog, which) -> {
                    CommitmentManager.emergencyRelease(this);
                    openSettings();
                })
                .show();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void renderState() {
        if (binding == null) return;
        boolean active = CommitmentManager.isActive(this);
        binding.activePanel.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) binding.countdown.setText(formatDuration(
                CommitmentManager.remainingMillis(this)));
        applyEditState();
    }

    private void applyEditState() {
        if (binding == null) return;
        boolean editing = ControllerPinManager.isSessionUnlocked();
        binding.buttonEmergencyRelease.setVisibility(editing ? View.VISIBLE : View.GONE);
    }

    public static String formatDuration(long milliseconds) {
        long seconds = Math.max(0L, (milliseconds + 999L) / 1000L);
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainder = seconds % 60L;
        return days > 0
                ? String.format(Locale.ROOT, "%dd %02d:%02d:%02d", days, hours, minutes, remainder)
                : String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainder);
    }

    @Override protected void onDestroy() {
        timer.removeCallbacks(tick);
        binding = null;
        super.onDestroy();
    }
}
