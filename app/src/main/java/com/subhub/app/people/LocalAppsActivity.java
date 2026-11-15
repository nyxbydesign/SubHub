package com.subhub.app.people;

import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.settings.AppSelectionPanel;

/** The selected local contract owns its shared app scope. */
public final class LocalAppsActivity extends ContractEditorPage {
    private AppSelectionPanel selection;

    public LocalAppsActivity() {
        super("apps", R.string.person_apps_title);
    }

    @Override protected void createEditor() {
        selection = new AppSelectionPanel(this);
        selection.setId(R.id.person_apps_panel);
        selection.setTag("person_apps:" + personId().value());
        body.addView(selection, new LinearLayout.LayoutParams(-1, PersonUi.dp(this, 360)));
        selection.fillAvailableHeight();
        selection.bind(this, FeatureEditorSession.context(this), this::mayEdit, () -> {});
        bindEditor(() -> true);
        body.getViewTreeObserver().addOnGlobalLayoutListener(this::sizeAppViewport);
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

    @Override protected void refresh() {
        if (selection == null) return;
        if (!mayView()) { selection.refresh(); finish(); return; }
        selection.reload();
    }
}
