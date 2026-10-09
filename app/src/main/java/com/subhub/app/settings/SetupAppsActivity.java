package com.subhub.app.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.PreferencePage;

import java.util.*;

/** A secondary picker returns directly to setup instead of entering the Settings navigation. */
public final class SetupAppsActivity extends PreferencePage {
    private AppModeManager apps;
    private final Set<String> included = new LinkedHashSet<>();
    private IncludedAppsAdapter adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.string.settings_apps);
        apps = new AppModeManager(this);
        included.addAll(apps.getIncludedPackages());
        ProgressBar loading = new ProgressBar(this);
        page.addView(loading, new LinearLayout.LayoutParams(-1, dp(48)));
        ListView list = new ListView(this);
        list.setId(R.id.app_list);
        list.setNestedScrollingEnabled(true);
        list.setDivider(null);
        list.setDividerHeight(dp(6));
        page.addView(list, new LinearLayout.LayoutParams(-1, dp(480)));
        Context app = getApplicationContext();
        AsyncUiScope.forPage(this)
                .load(
                        "setup-apps",
                        () -> InstalledAppCatalog.load(app),
                        entries -> {
                            loading.setVisibility(View.GONE);
                            adapter =
                                    new IncludedAppsAdapter(
                                            this,
                                            entries,
                                            included,
                                            (packageName, selected) -> {
                                                if (!ControllerPinManager.isSessionUnlocked())
                                                    return;
                                                if (selected) included.add(packageName);
                                                else included.remove(packageName);
                                                save();
                                            });
                            list.setAdapter(adapter);
                            adapter.setEditing(ControllerPinManager.isSessionUnlocked());
                        },
                        error -> {
                            loading.setVisibility(View.GONE);
                            notice(getString(R.string.settings_apps_unavailable));
                        });
    }

    private void save() {
        apps.saveIncludedPackages(included);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) adapter.setEditing(ControllerPinManager.isSessionUnlocked());
    }
}
