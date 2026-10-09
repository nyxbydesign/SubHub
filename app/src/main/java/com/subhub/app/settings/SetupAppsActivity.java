package com.subhub.app.settings;

import android.os.Bundle;
import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.PreferencePage;

/** A secondary shared selector returns directly to its caller. */
public final class SetupAppsActivity extends PreferencePage {
    private AppSelectionPanel selection;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.string.settings_apps);
        selection = new AppSelectionPanel(this);
        card(page).addView(selection, new LinearLayout.LayoutParams(-1, -2));
        selection.bind(this, ControllerPinManager::isDomModeActive, () -> {});
        selection.load();
    }

    @Override protected void onResume() {
        super.onResume();
        if (selection != null) selection.reload();
    }
}
