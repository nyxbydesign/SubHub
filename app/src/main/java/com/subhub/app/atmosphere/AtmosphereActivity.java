package com.subhub.app.atmosphere;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.databinding.ActivityAtmosphereBinding;
import com.subhub.app.popup.PopupStormActivity;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.stats.AchievementBadgeView;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.subliminal.SubliminalSettingsActivity;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

import java.util.LinkedHashSet;
import java.util.Set;

/** Focused editor for optional on-screen atmosphere effects. */
public final class AtmosphereActivity extends AppCompatActivity {
    private ActivityAtmosphereBinding binding;
    private AsyncUiScope overviewData;
    private String achievementsFingerprint = "";
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAtmosphereBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bind(binding.getRoot(), R.drawable.ic_atmosphere,
                R.string.atmosphere_title, 0);
        PrimaryHeader.editLockButton(binding.getRoot()).setOnClickListener(view -> toggleSpace());
        overviewData = AsyncUiScope.forPage(this);
        binding.ritualsImportPack.setOnClickListener(view -> openPacks(true));
        binding.ritualsPackLibrary.setOnClickListener(view -> openPacks(false));
        binding.ritualsGalleryCard.setOnClickListener(
                view ->
                        startActivity(new Intent(this, com.subhub.app.capture.ExportActivity.class)));
        binding.achievementsHomeCard.setOnClickListener(
                view ->
                        startActivity(
                                new Intent(this, com.subhub.app.stats.AchievementsActivity.class)));
        binding.buttonKeyholder.setOnClickListener(view -> openKeyholder());
        binding.ritualsKeyholderCard.setOnClickListener(view -> openKeyholder());
        binding.whispersCard.setOnClickListener(view -> openWhispers());
        binding.buttonWhispers.setOnClickListener(view -> openWhispers());
        binding.popupStormCard.setOnClickListener(view -> openPopupStorm());
        binding.buttonPopupStorm.setOnClickListener(view -> openPopupStorm());

    }

    @Override protected void onResume() {
        super.onResume();
        render();
        loadOverview();
    }

    private void openPacks(boolean importing) {
        startActivity(
                new Intent(this, com.subhub.app.studio.StudioActivity.class)
                        .putExtra(com.subhub.app.studio.StudioActivity.EXTRA_FROM_RITUALS, true)
                        .putExtra(
                                com.subhub.app.studio.StudioActivity.EXTRA_IMPORT_PACK, importing));
    }

    private void loadOverview() {
        android.content.Context app = getApplicationContext();
        overviewData.load(
                "ritual-packs",
                () -> new com.subhub.app.pack.SubHubPackManager(app).listLibrary(),
                records -> {
                    binding.ritualsPackCount.setText(
                            getString(R.string.rituals_pack_count, records.size()));
                    com.subhub.app.pack.SubHubPackManager.Record current =
                            records.stream()
                                    .filter(record -> record.active)
                                    .findFirst()
                                    .orElse(records.isEmpty() ? null : records.get(0));
                    binding.ritualsPackName.setText(
                            current == null
                                    ? getString(R.string.rituals_no_packs)
                                    : getString(
                                            current.active
                                                    ? R.string.rituals_pack_active
                                                    : R.string.rituals_pack_available,
                                            current.pack.getName()));
                },
                failure -> binding.ritualsPackName.setText(R.string.studio_load_failed));
        overviewData.load(
                "ritual-achievements",
                () ->
                        com.subhub.app.stats.HomeAchievementPreview.load(
                                app, new com.subhub.app.stats.StatsRepository(app).load()),
                this::applyAchievementsPreview,
                failure -> {});
    }

    private void applyAchievementsPreview(com.subhub.app.stats.HomeAchievementPreview preview) {
        if (preview.fingerprint.equals(achievementsFingerprint)) return;
        achievementsFingerprint = preview.fingerprint;
        AchievementManager achievements = preview.achievements;
        binding.achievementsHomeCount.setText(
                getString(
                        R.string.achievements_progress_compact,
                        achievements.getUnlockedCount(),
                        achievements.getTotalCount()));
        binding.achievementsHomeBadges.removeAllViews();
        Set<Integer> artwork = new LinkedHashSet<>();
        for (AchievementManager.Achievement value : achievements.all()) {
            if (!artwork.add(value.getBadgeArtRes())) continue;
            boolean unlocked = achievements.isUnlocked(value.getId());
            boolean concealed = value.isHidden() && !unlocked;
            AchievementBadgeView badge = new AchievementBadgeView(this);
            badge.setBackgroundResource(R.drawable.bg_achievement_preview_cell);
            badge.bind(
                    value.getBadgeArtRes(),
                    unlocked,
                    concealed,
                    getString(concealed ? R.string.achievement_hidden_name : value.getName()));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(68), dp(68));
            if (binding.achievementsHomeBadges.getChildCount() > 0) params.setMarginStart(dp(8));
            binding.achievementsHomeBadges.addView(badge, params);
            if (binding.achievementsHomeBadges.getChildCount() == 5) break;
        }
        int percent = preview.progress == null ? 100 : preview.progress.percent();
        binding.achievementsHomeNext.setText(
                preview.next == null
                        ? getString(R.string.achievements_all_complete)
                        : getString(
                                R.string.achievements_next_fmt,
                                getString(preview.next.getName()),
                                preview.progress.getCurrent()
                                        + " / "
                                        + preview.progress.getTarget()));
        binding.achievementsHomeProgress.setProgress(percent);
        binding.achievementsHomeProgressPercent.setText(
                getString(R.string.achievements_home_progress_percent, percent));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toggleSpace() {
        if (ControllerPinManager.isDomModeActive()) {
            ControllerEditMode.enterSubMode(this);
        } else {
            ControllerPinGate.unlock(this, this::render, false);
        }
    }

    private void openWhispers() {
        requireDom(() -> startActivity(new Intent(this, SubliminalSettingsActivity.class)));
    }

    private void openKeyholder() {
        requireDom(() -> startActivity(new Intent(this, com.subhub.app.security.AuthenticatorActivity.class)));
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
        ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(binding.getRoot()));
        binding.buttonWhispers.setContentDescription(
                getString(dom
                ? R.string.atmosphere_shape_whispers
                : R.string.atmosphere_unlock_to_edit));
        binding.buttonPopupStorm.setContentDescription(
                getString(dom
                ? R.string.atmosphere_open_popup_storm : R.string.atmosphere_unlock_to_edit));
        ControllerPinGate.markLocked(binding.whispersCard);
        ControllerPinGate.markLocked(binding.popupStormCard);
        ControllerPinGate.markLocked(binding.ritualsKeyholderCard);
        renderWhispers();
        renderPopupStorm();
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.ATMOSPHERE);
    }

    private void renderWhispers() {
        binding.whispersStatus.setText(new FeatureModuleManager(this).isSubliminalEnabled()
                ? R.string.ritual_effect_enabled : R.string.ritual_effect_disabled);
    }

    private void renderPopupStorm() {
        binding.popupStormStatus.setText(PopupStormSettings.load(this).isEnabled()
                ? R.string.ritual_effect_enabled : R.string.ritual_effect_disabled);
    }
}
