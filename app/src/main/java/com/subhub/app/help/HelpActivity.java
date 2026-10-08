package com.subhub.app.help;

import com.subhub.app.util.PrimaryHeader;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.subhub.app.R;
import com.subhub.app.BuildConfig;
import com.subhub.app.databinding.ActivityHelpBinding;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.LocaleHelper;
import com.subhub.app.update.UpdateCandidate;
import com.subhub.app.update.UpdateStateStore;
import com.subhub.app.update.UpdatesActivity;

import java.util.ArrayList;
import java.util.List;

/** Source-native help, permission repair, and per-app language selection. */
public final class HelpActivity extends AppCompatActivity {
    private ActivityHelpBinding binding;
    private ControllerEditMode editMode;
    private final ActivityResultLauncher<Intent> overlaySettings = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> renderPermissions());
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> renderPermissions());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHelpBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.help_title, true);
        binding.getRoot().setFocusableInTouchMode(true);
        binding.getRoot().requestFocus();
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        binding.buttonFixPermissions.setOnClickListener(view -> repairNextPermission());
        binding.buttonAccessibility.setOnClickListener(view ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        binding.buttonLanguage.setOnClickListener(view -> showLanguageChooser());
        binding.buttonUpdates.setOnClickListener(view ->
                startActivity(new Intent(this, UpdatesActivity.class)));
        addSections();
        if (ControllerPinManager.isDomModeActive()) {
            editMode = ControllerEditMode.bind(this, PrimaryHeader.editLockButton(binding.getRoot()), editing -> {
                int actionVisibility = editing ? View.VISIBLE : View.GONE;
                binding.buttonFixPermissions.setVisibility(actionVisibility);
                binding.buttonAccessibility.setVisibility(actionVisibility);
                binding.buttonLanguage.setVisibility(actionVisibility);
            });
        } else {
            PrimaryHeader.editLockButton(binding.getRoot()).setVisibility(View.GONE);
            binding.buttonFixPermissions.setVisibility(View.GONE);
            binding.buttonAccessibility.setVisibility(View.GONE);
            binding.buttonLanguage.setVisibility(View.GONE);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (editMode != null) editMode.refresh();
        renderPermissions();
        renderLanguage();
        renderUpdates();
    }

    private void addSections() {
        int[][] sections = {
                {R.string.help_getting_started, R.string.help_body_started},
                {R.string.help_permissions, R.string.help_body_perms},
                {R.string.help_dom_sub, R.string.help_body_dom_sub},
                {R.string.help_service_timers, R.string.help_body_service_timers},
                {R.string.help_app_assignment, R.string.help_body_app_assignment},
                {R.string.help_subliminal, R.string.help_body_subliminal},
                {R.string.help_censoring, R.string.help_body_censoring},
                {R.string.help_limits, R.string.help_body_limits},
                {R.string.help_wallet, R.string.help_body_wallet},
                {R.string.help_paypal_setup, R.string.help_body_paypal_setup},
                {R.string.help_hardcore, R.string.help_body_hardcore},
                {R.string.help_troubleshooting, R.string.help_body_troubleshooting},
                {R.string.help_privacy, R.string.help_body_privacy},
                {R.string.help_studio, R.string.help_body_studio},
                {R.string.help_updates, R.string.help_body_updates}
        };
        for (int[] section : sections) addSection(section[0], section[1]);
    }

    private void addSection(int title, int body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(14), dp(4), dp(14), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (binding.helpSections.getChildCount() > 0) params.topMargin = dp(5);
        card.setLayoutParams(params);
        TextView header = new TextView(this);
        header.setText(getString(title) + "  +");
        header.setTextColor(getColor(R.color.text_primary));
        header.setTextSize(13);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinHeight(dp(48));
        TextView content = new TextView(this);
        content.setText(body);
        content.setTextColor(getColor(R.color.text_secondary));
        content.setTextSize(12);
        content.setLineSpacing(dp(2), 1f);
        content.setPadding(0, 0, 0, dp(6));
        content.setVisibility(View.GONE);
        header.setOnClickListener(view -> {
            boolean opening = content.getVisibility() != View.VISIBLE;
            content.setVisibility(opening ? View.VISIBLE : View.GONE);
            header.setText(getString(title) + (opening ? "  −" : "  +"));
        });
        card.addView(header);
        card.addView(content);
        binding.helpSections.addView(card);
    }

    private void repairNextPermission() {
        if (!Settings.canDrawOverlays(this)) {
            overlaySettings.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        Toast.makeText(this, R.string.permission_all_granted, Toast.LENGTH_SHORT).show();
    }

    private void renderPermissions() {
        List<String> missing = new ArrayList<>();
        if (!Settings.canDrawOverlays(this)) missing.add(getString(R.string.permission_overlay_name));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(getString(R.string.permission_notifications_name));
        }
        binding.permissionStatus.setText(missing.isEmpty()
                ? getString(R.string.permission_all_granted)
                : getString(R.string.permission_missing_fmt, String.join(", ", missing)));
    }

    private void showLanguageChooser() {
        List<String> codes = LocaleHelper.SUPPORTED;
        String[] labels = new String[]{
                getString(R.string.language_system_default), getString(R.string.language_english),
                getString(R.string.language_french), getString(R.string.language_spanish),
                getString(R.string.language_portuguese), getString(R.string.language_german),
                getString(R.string.language_japanese), getString(R.string.language_chinese_simplified),
                getString(R.string.language_chinese_traditional), getString(R.string.language_korean),
                getString(R.string.language_russian)};
        int selected = Math.max(0, codes.indexOf(LocaleHelper.getLanguage(this)));
        com.subhub.app.util.ThemedDialogs.builder(this)
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    LocaleHelper.setLanguage(this, codes.get(which));
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void renderLanguage() {
        String code = LocaleHelper.getLanguage(this);
        int index = LocaleHelper.SUPPORTED.indexOf(code);
        int[] labels = {R.string.language_system_default, R.string.language_english,
                R.string.language_french, R.string.language_spanish, R.string.language_portuguese,
                R.string.language_german, R.string.language_japanese,
                R.string.language_chinese_simplified, R.string.language_chinese_traditional,
                R.string.language_korean, R.string.language_russian};
        binding.languageStatus.setText(labels[Math.max(0, index)]);
    }

    private void renderUpdates() {
        UpdateCandidate candidate = new UpdateStateStore(this).candidate();
        binding.updateSummary.setText(candidate == null
                ? getString(R.string.update_help_current, BuildConfig.VERSION_NAME)
                : getString(R.string.update_help_available, candidate.manifest.versionName));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
