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
        body.addView(selection, new LinearLayout.LayoutParams(-1, PersonUi.dp(this, 360)));
        selection.fillAvailableHeight();
        selection.bind(this, FeatureEditorSession.context(this), this::mayEdit, () -> {});
        FeatureEditorSession.bind(this, findViewById(android.R.id.content), "apps", () -> true);
        body.getViewTreeObserver().addOnGlobalLayoutListener(this::sizeAppViewport);
        InputFocusPolicy.openActivityForReading(this);
    }

    private void sizeAppViewport() {
        LinearLayout content = findViewById(R.id.role_page_content);
        if (!(content.getParent() instanceof android.widget.ScrollView)) return;
        android.widget.ScrollView scroll = (android.widget.ScrollView) content.getParent();
        int height = scroll.getHeight() - content.getPaddingTop() - content.getPaddingBottom()
                - body.getPaddingTop() - body.getPaddingBottom();
        for (int i = 0; i < content.getChildCount(); i++)
            if (content.getChildAt(i) != body) height -= occupiedHeight(content.getChildAt(i));
        for (int i = 0; i < body.getChildCount(); i++)
            if (body.getChildAt(i) != selection) height -= occupiedHeight(body.getChildAt(i));
        android.view.View listContent = selection.findViewById(R.id.app_list_content);
        // Keep a scrollable list even when enlarged header/footer text needs the outer scroll view.
        int minimum = listContent.getTop() + PersonUi.dp(this, 96);
        int desired = Math.max(minimum, height);
        if (selection.getLayoutParams().height != desired) {
            selection.getLayoutParams().height = desired;
            selection.requestLayout();
        }
    }

    private static int occupiedHeight(android.view.View view) {
        if (view.getVisibility() == android.view.View.GONE) return 0;
        android.view.ViewGroup.MarginLayoutParams params = (android.view.ViewGroup.MarginLayoutParams) view.getLayoutParams();
        return view.getHeight() + params.topMargin + params.bottomMargin;
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
