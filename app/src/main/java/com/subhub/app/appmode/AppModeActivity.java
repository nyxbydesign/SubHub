package com.subhub.app.appmode;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.databinding.ActivityAppModeBinding;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Styled launcher-app picker and lifecycle controls for accessibility app mode. */
public final class AppModeActivity extends AppCompatActivity {
    private ActivityAppModeBinding binding;
    private AppModeManager manager;
    private AppTimerManager timers;
    private boolean editingUnlocked;
    private boolean populatingTimers;
    private final Map<String, EditText> allowanceInputs = new LinkedHashMap<>();
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private final Runnable persistTimers = () -> save(false);
    private final TextWatcher autoSaveWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable value) { scheduleAutoSave(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAppModeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bind(binding.getRoot(), R.drawable.ic_nav_limits,
                R.string.app_mode_title, 0);
        if (SubHubNavigation.redirectIfDisabled(this, SubHubNavigation.Screen.LIMITS)) return;
        manager = new AppModeManager(this);
        timers = new AppTimerManager(this);
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.LIMITS);
        AppTimerManager.Settings timerSettings = timers.loadSettings();
        binding.perAppLimitEnabled.setChecked(timerSettings.perAppEnabled);
        binding.totalLimitEnabled.setChecked(timerSettings.totalEnabled);
        binding.totalLimitMinutes.setText(String.valueOf(timerSettings.totalMinutes));
        binding.defaultLimitMinutes.setText(String.valueOf(timerSettings.perAppMinutes));
        binding.perAppLimitEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (checked && rejectUnassignedLimit()) return;
            renderTimerControls();
            scheduleAutoSave();
            if (checked) promptForAccessibility();
        });
        binding.totalLimitEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (checked && rejectUnassignedLimit()) return;
            renderTimerControls();
            scheduleAutoSave();
            if (checked) promptForAccessibility();
        });
        binding.totalLimitMinutes.addTextChangedListener(autoSaveWatcher);
        binding.defaultLimitMinutes.addTextChangedListener(autoSaveWatcher);
        binding.defaultLimitMinutes.setOnFocusChangeListener((view, focused) -> {
            if (!focused) commitTimers(true);
        });
        binding.totalLimitMinutes.setOnFocusChangeListener((view, focused) -> {
            if (!focused) commitTimers(true);
        });
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        PrimaryHeader.editLockButton(binding.getRoot())
                .setOnClickListener(view -> toggleEditSession());
        renderPerAppAllowances();
        renderTimerControls();
        renderTimerUsage();
        applyEditState();
    }

    @Override protected void onResume() {
        super.onResume();
        renderPerAppAllowances();
        applyEditState();
    }

    @Override protected void onPause() {
        autoSaveHandler.removeCallbacks(persistTimers);
        if (editingUnlocked) save(false);
        super.onPause();
    }

    private void toggleEditSession() {
        if (ControllerPinManager.isSessionUnlocked()) {
            ControllerEditMode.enterSubMode(this);
        } else ControllerPinGate.require(this, this::applyEditState, false);
    }

    private void applyEditState() {
        if (binding == null) return;
        editingUnlocked = ControllerPinManager.isSessionUnlocked();
        ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(binding.getRoot()));
        View[] editable = {binding.perAppLimitEnabled, binding.totalLimitEnabled};
        for (View view : editable) view.setEnabled(editingUnlocked);
        renderTimerControls();
    }

    private boolean rejectUnassignedLimit() {
        if (populatingTimers || !editingUnlocked
                || manager.getMode() != AppModePolicy.Mode.SELECTED_APPS
                || !manager.getTimerPackages().isEmpty()) return false;
        restoreTimerValues();
        return true;
    }

    private boolean save(boolean showInvalid) {
        boolean watchedAppsRequired = binding.perAppLimitEnabled.isChecked()
                || binding.totalLimitEnabled.isChecked();
        if (watchedAppsRequired && manager.getMode() == AppModePolicy.Mode.SELECTED_APPS
                && manager.getTimerPackages().isEmpty()) {
            if (showInvalid) Toast.makeText(this, R.string.app_mode_select_one,
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        Integer totalMinutes = readMinutes(binding.totalLimitMinutes,
                binding.totalLimitEnabled.isChecked());
        AppTimerManager.Settings existing = timers.loadSettings();
        Integer defaultMinutes = manager.getMode() == AppModePolicy.Mode.ALWAYS
                ? readMinutes(binding.defaultLimitMinutes, binding.perAppLimitEnabled.isChecked())
                : existing.perAppMinutes;
        if (defaultMinutes == null) {
            if (showInvalid) Toast.makeText(this, R.string.app_timer_invalid_minutes, Toast.LENGTH_SHORT).show();
            return false;
        }
        Map<String, Integer> allowances = new LinkedHashMap<>();
        if (binding.perAppLimitEnabled.isChecked()) {
            for (Map.Entry<String, EditText> entry : allowanceInputs.entrySet()) {
                Integer minutes = readMinutes(entry.getValue(), true);
                if (minutes == null) {
                    if (showInvalid) Toast.makeText(this, R.string.app_timer_invalid_minutes,
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                allowances.put(entry.getKey(), minutes);
            }
        }
        if (totalMinutes == null) {
            if (showInvalid) Toast.makeText(this, R.string.app_timer_invalid_minutes,
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        timers.saveSettings(binding.perAppLimitEnabled.isChecked(), defaultMinutes,
                binding.totalLimitEnabled.isChecked(), totalMinutes);
        if (binding.perAppLimitEnabled.isChecked()) {
            timers.saveAllowances(manager.getTimerPackages(), allowances);
        }
        return true;
    }

    private void scheduleAutoSave() {
        if (populatingTimers || !editingUnlocked) return;
        autoSaveHandler.removeCallbacks(persistTimers);
        autoSaveHandler.postDelayed(persistTimers, 450L);
    }

    private void commitTimers(boolean restoreIfInvalid) {
        if (populatingTimers || !editingUnlocked) return;
        autoSaveHandler.removeCallbacks(persistTimers);
        if (!save(restoreIfInvalid) && restoreIfInvalid) restoreTimerValues();
    }

    private void restoreTimerValues() {
        populatingTimers = true;
        AppTimerManager.Settings saved = timers.loadSettings();
        binding.perAppLimitEnabled.setChecked(saved.perAppEnabled);
        binding.totalLimitEnabled.setChecked(saved.totalEnabled);
        binding.totalLimitMinutes.setText(String.valueOf(saved.totalMinutes));
        binding.defaultLimitMinutes.setText(String.valueOf(saved.perAppMinutes));
        renderPerAppAllowances();
        populatingTimers = false;
        renderTimerControls();
    }

    private void promptForAccessibility() {
        if (populatingTimers || !editingUnlocked || accessibilityEnabled()) return;
        Toast.makeText(this, R.string.app_mode_enable_prompt, Toast.LENGTH_LONG).show();
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void renderTimerControls() {
        boolean perAppEnabled = binding.perAppLimitEnabled.isChecked();
        boolean allApps = manager.getMode() == AppModePolicy.Mode.ALWAYS;
        binding.defaultAllowanceGroup.setVisibility(allApps ? View.VISIBLE : View.GONE);
        binding.defaultLimitMinutes.setEnabled(editingUnlocked && perAppEnabled && allApps);
        for (EditText input : allowanceInputs.values()) {
            input.setEnabled(editingUnlocked && perAppEnabled);
            input.setAlpha(perAppEnabled ? 1f : 0.5f);
        }
        binding.totalLimitMinutes.setEnabled(
                editingUnlocked && binding.totalLimitEnabled.isChecked());
        binding.totalLimitMinutes.setAlpha(binding.totalLimitEnabled.isChecked() ? 1f : 0.5f);
    }

    private void renderPerAppAllowances() {
        if (binding == null || manager == null || timers == null) return;
        Set<String> packages = manager.getTimerPackages();
        Map<String, Integer> saved = timers.loadAllowances(packages);
        List<String> ordered = new ArrayList<>(packages);
        Collator collator = Collator.getInstance(Locale.getDefault());
        ordered.sort((left, right) -> collator.compare(appLabel(left), appLabel(right)));
        binding.perAppAllowancesList.removeAllViews();
        allowanceInputs.clear();
        if (ordered.isEmpty()) {
            if (manager.getMode() == AppModePolicy.Mode.ALWAYS) return;
            TextView empty = new TextView(this);
            empty.setText(R.string.app_timer_no_limited_apps);
            empty.setTextColor(getColor(R.color.text_muted));
            empty.setTextSize(11f);
            empty.setPadding(0, dp(8), 0, dp(5));
            binding.perAppAllowancesList.addView(empty);
            TextView chooseApps = new TextView(this, null, 0,
                    R.style.Widget_SubHub_OutlineAction);
            chooseApps.setText(R.string.app_timer_choose_apps);
            chooseApps.setTag("limits_choose_apps");
            chooseApps.setMinHeight(dp(48));
            chooseApps.setGravity(Gravity.CENTER);
            chooseApps.setOnClickListener(view -> startActivity(new Intent(this,
                    com.subhub.app.settings.GlobalSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("show_app_assignments", true)));
            binding.perAppAllowancesList.addView(chooseApps,
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (String packageName : ordered) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));
            TextView label = new TextView(this);
            label.setText(appLabel(packageName));
            label.setTextColor(getColor(R.color.text_primary));
            label.setTextSize(12f);
            label.setMaxLines(2);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            EditText input = new EditText(this);
            input.setId(View.generateViewId());
            label.setLabelFor(input.getId());
            input.setContentDescription(getString(R.string.app_timer_allowance_accessibility,
                    appLabel(packageName)));
            input.setHint(R.string.app_timer_allowance_minutes);
            input.setText(String.valueOf(saved.get(packageName)));
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setSingleLine(true);
            input.setTextColor(getColor(R.color.text_primary));
            input.setHintTextColor(getColor(R.color.text_muted));
            input.setTextSize(12f);
            input.setGravity(Gravity.CENTER);
            input.addTextChangedListener(autoSaveWatcher);
            input.setOnFocusChangeListener((view, focused) -> {
                if (!focused) commitTimers(true);
            });
            row.addView(input, new LinearLayout.LayoutParams(dp(104), dp(46)));
            allowanceInputs.put(packageName, input);
            binding.perAppAllowancesList.addView(row);
        }
    }

    private String appLabel(String packageName) {
        try {
            PackageManager packages = getPackageManager();
            ApplicationInfo info = packages.getApplicationInfo(packageName, 0);
            CharSequence label = packages.getApplicationLabel(info);
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void renderTimerUsage() {
        AppTimerManager.UsageSnapshot usage = timers.snapshot("", System.currentTimeMillis());
        if (usage.totalUsedMillis <= 0L) {
            binding.timerUsageStatus.setText(R.string.app_timer_usage_none);
        } else {
            binding.timerUsageStatus.setText(getString(R.string.app_timer_usage_total,
                    formatUsage(usage.totalUsedMillis)));
        }
    }

    private Integer readMinutes(EditText input, boolean required) {
        String value = input.getText() == null ? "" : input.getText().toString().trim();
        if (!required) {
            try {
                return AppTimerManager.sanitizeMinutes(Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        try {
            int minutes = Integer.parseInt(value);
            return minutes >= 1 && minutes <= 1440 ? minutes : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String formatUsage(long millis) {
        long totalMinutes = Math.max(0L, millis) / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return hours > 0L ? hours + "h " + minutes + "m" : minutes + "m";
    }

    private boolean accessibilityEnabled() {
        return manager.isAccessibilityEnabled();
    }

    @Override protected void onDestroy() {
        autoSaveHandler.removeCallbacksAndMessages(null);
        binding = null;
        super.onDestroy();
    }
}
