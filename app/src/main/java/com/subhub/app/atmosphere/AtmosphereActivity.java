package com.subhub.app.atmosphere;

import android.os.Bundle;
import android.view.View;

import com.subhub.app.R;
import com.subhub.app.capture.CensorImageEditor;
import com.subhub.app.configuration.FeatureEditorActivity;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.databinding.ActivityAtmosphereBinding;
import com.subhub.app.popup.PopupStormEditorPanel;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.InputFocusPolicy;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

/** The selected Contract's on-screen effects, all edited on one page. */
public final class AtmosphereActivity extends FeatureEditorActivity {
    private static final String PANEL_STATE = "atmosphere_panel_state";
    private static final String WORKSPACE = "atmosphere_workspace";
    private ActivityAtmosphereBinding binding;
    private AtmosphereEditorPanels panels;
    private CensorImageEditor.Picker images;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FeatureEditorSession.attach(this, "atmosphere", state)) return;
        if (!FeatureEditorSession.canView(this, "atmosphere")) {
            FeatureEditorSession.leaveEditor(this);
            return;
        }
        PopupStormEditorPanel.LocalRequests localRequests = null;
        if (FeatureEditorSession.remote(this)) {
            images = new CensorImageEditor.Picker(this, "atmosphere-popup-images", state);
        } else {
            localRequests = new PopupStormEditorPanel.LocalRequests(this,
                    () -> panels == null ? null : panels.popup());
        }
        binding = ActivityAtmosphereBinding.inflate(getLayoutInflater());
        View root = binding.getRoot();
        setContentView(root);
        PrimaryHeader.bind(root, R.drawable.ic_atmosphere, R.string.atmosphere_title, R.string.role_this_device);
        PrimaryHeader.backButton(root).setVisibility(View.VISIBLE);
        PrimaryHeader.backButton(root).setOnClickListener(view -> finish());
        PrimaryHeader.editLockButton(root).setOnClickListener(view -> FeatureEditorSession.leaveEditor(this));
        Bundle panelState = state != null
                && state.getLong(WORKSPACE, -1) == ControllerPinManager.domWorkspaceRevision()
                && (!FeatureEditorSession.internal(this) || FeatureEditorSession.restoreInternalWidgets(this, state))
                ? state.getBundle(PANEL_STATE) : null;
        panels = new AtmosphereEditorPanels(this, binding.atmospherePanels, images, localRequests, panelState);
        FeatureEditorSession.bindPanels(this, binding.atmosphereSave, "atmosphere", panels::flush);
        InputFocusPolicy.openActivityForReading(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (panels == null || isFinishing()) return;
        if (!FeatureEditorSession.canView(this, "atmosphere")) {
            FeatureEditorSession.leaveEditor(this);
            return;
        }
        View root = binding.getRoot();
        ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(root));
        SubHubNavigation.bind(this, root, SubHubNavigation.Screen.ATMOSPHERE);
        FeatureEditorSession.decorate(this);
        panels.resume();
    }

    @Override protected void onPause() {
        if (panels != null) panels.pause();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (panels != null) panels.flushDeferredInputs();
        if (images != null) images.saveState(state);
        super.onSaveInstanceState(state);
        if (panels != null) {
            state.putBundle(PANEL_STATE, panels.saveState());
            state.putLong(WORKSPACE, ControllerPinManager.domWorkspaceRevision());
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (panels != null) panels.close();
    }
}
