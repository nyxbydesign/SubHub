package com.subhub.app.atmosphere;

import com.subhub.app.configuration.FeatureEditorSession;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.databinding.ActivityAtmosphereBinding;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.popup.PopupStormActivity;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.subliminal.SubliminalSettingsActivity;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

/** The selected local contract's optional on-screen effects. */
public final class AtmosphereActivity extends AppCompatActivity {
    private ActivityAtmosphereBinding binding;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FeatureEditorSession.attach(this, "atmosphere", state)) return;
        binding = ActivityAtmosphereBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bind(binding.getRoot(), R.drawable.ic_atmosphere,
                R.string.atmosphere_title, R.string.role_this_device);
        PrimaryHeader.backButton(binding.getRoot()).setVisibility(android.view.View.VISIBLE);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        PrimaryHeader.editLockButton(binding.getRoot()).setOnClickListener(view -> FeatureEditorSession.leaveEditor(this));
        binding.whispersCard.setOnClickListener(view -> open(SubliminalSettingsActivity.class));
        binding.buttonWhispers.setOnClickListener(view -> open(SubliminalSettingsActivity.class));
        binding.popupStormCard.setOnClickListener(view -> open(PopupStormActivity.class));
        binding.buttonPopupStorm.setOnClickListener(view -> open(PopupStormActivity.class));
        binding.gagCard.setOnClickListener(view -> openGag());
        binding.buttonGag.setOnClickListener(view -> openGag());
    }

    @Override protected void onResume() {
        super.onResume();
        if (binding == null || isFinishing()) return;
        if (!FeatureEditorSession.canView(this, "atmosphere")) {
            FeatureEditorSession.leaveEditor(this);
            return;
        }
        ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(binding.getRoot()));
        binding.whispersStatus.setText(new FeatureModuleManager(FeatureEditorSession.context(this)).isSubliminalEnabled()
                ? R.string.ritual_effect_enabled : R.string.ritual_effect_disabled);
        binding.popupStormStatus.setText(PopupStormSettings.load(FeatureEditorSession.context(this)).isEnabled()
                ? R.string.ritual_effect_enabled : R.string.ritual_effect_disabled);
        binding.whispersCard.setEnabled(FeatureEditorSession.canView(this, "subliminal"));
        binding.buttonWhispers.setEnabled(FeatureEditorSession.canView(this, "subliminal"));
        binding.popupStormCard.setEnabled(FeatureEditorSession.canView(this, "popup"));
        binding.buttonPopupStorm.setEnabled(FeatureEditorSession.canView(this, "popup"));
        boolean gagVisible = FeatureEditorSession.canEditGag(this);
        binding.gagCard.setVisibility(gagVisible ? android.view.View.VISIBLE : android.view.View.GONE);
        if (gagVisible) binding.gagStatus.setText(R.string.gag_dom_description);
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.ATMOSPHERE);
        FeatureEditorSession.decorate(this);
    }

    private void openGag() {
        if (FeatureEditorSession.canEditGag(this)) {
            startActivity(GagActivity.editorIntent(this));
        }
    }

    private void open(Class<? extends android.app.Activity> destination) {
        if (!FeatureEditorSession.canView(this, "atmosphere")) return;
        String section = destination == SubliminalSettingsActivity.class ? "subliminal" : "popup";
        if (!FeatureEditorSession.canView(this, section)) return;
        startActivity(FeatureEditorSession.child(this, destination));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        FeatureEditorSession.saveState(this, state);
        super.onSaveInstanceState(state);
    }

    @Override protected void onRestoreInstanceState(Bundle state) {
        // Retain raw input only for the same current internal draft; stale widget bundles cannot write newer state.
        if ((!FeatureEditorSession.internal(this) || FeatureEditorSession.restoreInternalWidgets(this, state))
                && !FeatureEditorSession.preview(this)) super.onRestoreInstanceState(state);
    }

}
