package com.subhub.app.subliminal;

import android.os.Bundle;

import com.subhub.app.R;
import com.subhub.app.configuration.FeatureEditorActivity;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.util.PrimaryHeader;

/** Standalone Whispers editor for this device and legacy entry points. */
public final class SubliminalSettingsActivity extends FeatureEditorActivity {
    private static final String ADVANCED = "whispers_advanced_expanded";
    private WhispersEditorPanel panel;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FeatureEditorSession.attach(this, "subliminal", state)) return;
        if (!FeatureEditorSession.canView(this, "subliminal")) { finish(); return; }
        setContentView(R.layout.activity_subliminal_settings);
        android.view.View root = findViewById(android.R.id.content);
        PrimaryHeader.bindSecondary(root, R.string.subliminal_title, false);
        PrimaryHeader.backButton(root).setOnClickListener(view -> finish());
        panel = new WhispersEditorPanel(this, findViewById(R.id.whispers_settings),
                state != null && state.getBoolean(ADVANCED));
        FeatureEditorSession.bind(this, root, "subliminal", panel::flush);
    }

    @Override protected void onResume() {
        super.onResume();
        if (panel == null) return;
        if (!FeatureEditorSession.canView(this, "subliminal")) { finish(); return; }
        panel.resume();
        FeatureEditorSession.decorate(this);
    }

    @Override protected void onPause() {
        if (panel != null) { panel.flush(); panel.stopPreview(); }
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
