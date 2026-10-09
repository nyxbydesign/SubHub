package com.subhub.app.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppModePolicy;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.PreferencePage;

import java.util.*;

/** A secondary picker returns directly to setup instead of entering the Settings navigation. */
public final class SetupAppsActivity extends PreferencePage {
    private AppModeManager apps;
    private AppModePolicy.Mode mode;
    private final Set<String> censor = new LinkedHashSet<>(), limits = new LinkedHashSet<>();
    private final List<Button> scopeButtons = new ArrayList<>();
    private AppAssignmentAdapter adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.string.settings_apps);
        apps = new AppModeManager(this);
        mode = apps.getMode();
        censor.addAll(apps.getSelectedPackages());
        limits.addAll(apps.getTimerPackages());
        LinearLayout scope = new LinearLayout(this);
        page.addView(scope, new LinearLayout.LayoutParams(-1, -2));
        for (AppModePolicy.Mode choice :
                new AppModePolicy.Mode[] {
                    AppModePolicy.Mode.ALWAYS, AppModePolicy.Mode.SELECTED_APPS
                }) {
            Button action =
                    button(
                            scope,
                            getString(
                                    choice == AppModePolicy.Mode.ALWAYS
                                            ? R.string.app_mode_always
                                            : R.string.app_mode_selected),
                            () -> {
                                if (!ControllerPinManager.isSessionUnlocked()) return;
                                mode = choice;
                                save();
                                renderScope();
                            });
            action.setTag(choice);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(dp(3), dp(6), dp(3), dp(6));
            action.setLayoutParams(params);
            scopeButtons.add(action);
        }
        ProgressBar loading = new ProgressBar(this);
        page.addView(loading, new LinearLayout.LayoutParams(-1, dp(48)));
        ListView list = new ListView(this);
        list.setId(R.id.app_list);
        list.setNestedScrollingEnabled(true);
        list.setDivider(null);
        list.setDividerHeight(dp(6));
        page.addView(list, new LinearLayout.LayoutParams(-1, dp(480)));
        LinearLayout legend = new LinearLayout(this);
        text(legend, getString(R.string.app_assignment_app), 12, true)
                .setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        for (int label : new int[] {R.string.app_selection_censor, R.string.app_selection_limit}) {
            TextView column = text(legend, getString(label), 12, true);
            column.setGravity(android.view.Gravity.CENTER);
            column.setLayoutParams(new LinearLayout.LayoutParams(dp(56), -2));
        }
        if (getResources().getConfiguration().fontScale <= 1.4f) {
            list.addHeaderView(legend, null, false);
        }
        Context app = getApplicationContext();
        List<Set<String>> assignments = Arrays.asList(censor, limits);
        AsyncUiScope.forPage(this)
                .load(
                        "setup-apps",
                        () -> InstalledAppCatalog.load(app),
                        entries -> {
                            loading.setVisibility(View.GONE);
                            adapter =
                                    new AppAssignmentAdapter(
                                            this,
                                            entries,
                                            assignments,
                                            (packageName, module, selected) -> {
                                                if (!ControllerPinManager.isSessionUnlocked())
                                                    return;
                                                Set<String> packages = assignments.get(module);
                                                if (selected) packages.add(packageName);
                                                else packages.remove(packageName);
                                                save();
                                            });
                            list.setAdapter(adapter);
                            adapter.setEditing(ControllerPinManager.isSessionUnlocked());
                        },
                        error -> {
                            loading.setVisibility(View.GONE);
                            notice(getString(R.string.settings_apps_unavailable));
                        });
        renderScope();
    }

    private void save() {
        apps.saveUiSelections(mode, censor, limits, apps.getSubliminalPackages());
    }

    private void renderScope() {
        for (Button button : scopeButtons) {
            boolean selected = button.getTag() == mode;
            button.setSelected(selected);
            button.setTextColor(getColor(selected ? R.color.text_primary : R.color.accent_hot));
            button.setBackgroundResource(
                    selected ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
            button.setEnabled(ControllerPinManager.isSessionUnlocked());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderScope();
        if (adapter != null) adapter.setEditing(ControllerPinManager.isSessionUnlocked());
    }
}
