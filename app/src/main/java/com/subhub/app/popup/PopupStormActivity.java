package com.subhub.app.popup;

import android.os.Bundle;
import android.view.View;

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
    private final PopupStormEditorPanel.LocalRequests localRequests =
            new PopupStormEditorPanel.LocalRequests(this, () -> panel);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FeatureEditorSession.attach(this, "popup", state)) return;
        setContentView(R.layout.activity_popup_storm);
        View root = findViewById(android.R.id.content);
        PrimaryHeader.bindSecondary(root, R.string.popup_title, true);
        PrimaryHeader.backButton(root).setOnClickListener(view -> finish());
        panel = new PopupStormEditorPanel(this, findViewById(R.id.popup_storm_settings),
                state != null && state.getBoolean(ADVANCED), null, localRequests);
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

}
