package com.subhub.app.people;

import android.content.Intent;
import android.os.Bundle;
import com.subhub.app.R;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.util.InputFocusPolicy;
import com.subhub.app.util.SubHubNavigation;
import java.util.function.BooleanSupplier;

/** Keeps the Apps and Duration editors bound to their original contract throughout the page lifecycle. */
abstract class ContractEditorPage extends RolePage {
    private final String section;
    private final int title;
    private PersonId personId;

    ContractEditorPage(String section, int title) {
        this.section = section;
        this.title = title;
    }

    @Override protected final void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FeatureEditorSession.attach(this, section, state)) return;
        try { personId = PersonRoutes.requireId(getIntent()); }
        catch (IllegalArgumentException invalid) { finish(); return; }
        if (!mayView()) { finish(); return; }
        page(title, SubHubNavigation.Screen.PEOPLE, true);
        secondaryBack(this::finish);
        PersonUi.text(body, FeatureEditorSession.targetName(this), false).setId(R.id.person_editor_target);
        createEditor();
        InputFocusPolicy.openActivityForReading(this);
    }

    protected abstract void createEditor();

    protected final PersonId personId() {
        return personId;
    }

    protected final void bindEditor(BooleanSupplier flush) {
        FeatureEditorSession.bind(this, findViewById(android.R.id.content), section, flush);
    }

    protected final boolean mayView() {
        try {
            return personId != null && personId.equals(PersonRoutes.requireId(getIntent()))
                    && FeatureEditorSession.canView(this, section);
        } catch (IllegalArgumentException invalid) { return false; }
    }

    protected final boolean mayEdit() {
        try {
            return personId != null && personId.equals(PersonRoutes.requireId(getIntent()))
                    && FeatureEditorSession.canEdit(this, section);
        } catch (IllegalArgumentException invalid) { return false; }
    }

    @Override protected final void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refresh();
    }

    @Override protected final void onSaveInstanceState(Bundle state) {
        FeatureEditorSession.saveState(this, state);
        super.onSaveInstanceState(state);
    }

    @Override protected final void onRestoreInstanceState(Bundle state) {
        // Retain raw input only for the same current internal draft; stale widget bundles cannot write newer state.
        if ((!FeatureEditorSession.internal(this) || FeatureEditorSession.restoreInternalWidgets(this, state))
                && !FeatureEditorSession.preview(this)) super.onRestoreInstanceState(state);
    }
}
