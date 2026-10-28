package com.subhub.app.people;

import com.subhub.app.configuration.FeatureEditorSession;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.settings.AppSelectionPanel;
import com.subhub.app.util.InputFocusPolicy;
import com.subhub.app.util.SubHubNavigation;

/** The selected local contract owns its shared app scope. */
public final class LocalAppsActivity extends RolePage {
    private PersonId personId;
    private AppSelectionPanel selection;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FeatureEditorSession.attach(this, "apps", state)) return;
        try { personId = PersonRoutes.requireId(getIntent()); }
        catch (IllegalArgumentException invalid) { finish(); return; }
        if (!mayEdit()) { finish(); return; }
        page(R.string.person_apps_title, SubHubNavigation.Screen.PEOPLE, true);
        secondaryBack(this::finish);
        PersonUi.text(body, FeatureEditorSession.targetName(this), false).setId(R.id.person_editor_target);
        selection = new AppSelectionPanel(this);
        selection.setId(R.id.person_apps_panel);
        selection.setTag("person_apps:" + personId.value());
        body.addView(selection, new LinearLayout.LayoutParams(-1, -2));
        selection.bind(this, FeatureEditorSession.context(this), this::mayEdit, () -> {});
        FeatureEditorSession.bind(this, findViewById(android.R.id.content), "apps", () -> true);
        InputFocusPolicy.openActivityForReading(this);
    }

    private boolean mayEdit() {
        try {
            return personId != null && personId.equals(PersonRoutes.requireId(getIntent()))
                    && FeatureEditorSession.canEdit(this, "apps");
        } catch (IllegalArgumentException invalid) { return false; }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refresh();
    }

    @Override protected void refresh() {
        if (selection == null) return;
        if (!mayEdit()) { selection.refresh(); finish(); return; }
        selection.reload();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        FeatureEditorSession.saveState(this, state);
        super.onSaveInstanceState(state);
    }

}
