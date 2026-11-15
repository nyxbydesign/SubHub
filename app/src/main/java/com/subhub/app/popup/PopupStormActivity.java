package com.subhub.app.popup;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.subhub.app.R;
import com.subhub.app.configuration.FeatureEditorActivity;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.util.PrimaryHeader;

/** Standalone Popup Storm editor and this device's preview and media permissions. */
public final class PopupStormActivity extends FeatureEditorActivity {
    private static final String ADVANCED = "popup_advanced_expanded";
    private PopupStormEditorPanel panel;
    private ControllerEditMode editMode;
    private final ActivityResultLauncher<Intent> enableOverlayPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (panel != null) panel.overlayResult(true);
            });
    private final ActivityResultLauncher<Uri> folderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), result -> {
                if (panel != null) panel.onFolderPicked(result);
            });
    private final ActivityResultLauncher<Intent> previewOverlayPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (panel != null) panel.overlayResult(false);
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FeatureEditorSession.attach(this, "popup", state)) return;
        setContentView(R.layout.activity_popup_storm);
        View root = findViewById(android.R.id.content);
        PrimaryHeader.bindSecondary(root, R.string.popup_title, true);
        PrimaryHeader.backButton(root).setOnClickListener(view -> finish());
        panel = new PopupStormEditorPanel(this, findViewById(R.id.popup_storm_settings),
                state != null && state.getBoolean(ADVANCED), null);
        if (!FeatureEditorSession.remote(this)) {
            editMode = ControllerEditMode.bind(this, PrimaryHeader.editLockButton(root),
                    editing -> panel.applyEditState());
        }
        FeatureEditorSession.bind(this, root, "popup", panel::flush);
    }

    @Override protected void onResume() {
        super.onResume();
        if (panel == null) return;
        if (editMode != null) editMode.refresh();
        panel.resume();
        FeatureEditorSession.decorate(this);
    }

    @Override protected void onPause() {
        if (panel != null) panel.pause();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (panel != null) state.putBoolean(ADVANCED, panel.advancedExpanded());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (panel != null) panel.close();
        super.onDestroy();
    }

    void chooseFolder() { folderPicker.launch(null); }
    void requestEnableOverlay(Intent request) { enableOverlayPermission.launch(request); }
    void requestPreviewOverlay(Intent request) { previewOverlayPermission.launch(request); }
}
