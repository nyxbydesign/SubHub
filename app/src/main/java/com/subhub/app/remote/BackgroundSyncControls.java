package com.subhub.app.remote;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import com.subhub.app.util.StateToggle;

/** A local device choice, available in either workspace without changing peer authority. */
public final class BackgroundSyncControls {
    private BackgroundSyncControls() { }
    public static void bind(Context context, LinearLayout container) {
        container.removeAllViews();
        StateToggle control = new StateToggle(context);
        control.setSaveEnabled(false);
        control.setTag("background_sync");
        control.setText(R.string.background_sync_title);
        com.subhub.app.util.UiIdentity.textSize(control, R.dimen.ui_text_body);
        control.setChecked(BackgroundSyncPreferences.enabled(context));
        control.setOnCheckedChangeListener((button, checked) -> {
            if (!RemoteFeature.get(context).setBackgroundSyncEnabled(checked)) {
                Toast.makeText(context, R.string.privacy_save_failed, Toast.LENGTH_LONG).show();
                bind(context, container);
            }
        });
        container.addView(control, new LinearLayout.LayoutParams(-1, -2));
        PersonUi.text(container, context.getString(R.string.background_sync_summary), false);
    }
}
