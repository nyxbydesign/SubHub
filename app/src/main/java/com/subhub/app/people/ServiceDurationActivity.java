package com.subhub.app.people;

import com.subhub.app.configuration.FeatureEditorSession;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.commitment.ServiceDurationView;
import com.subhub.app.util.InputFocusPolicy;
import com.subhub.app.util.SubHubNavigation;

/** Configures the selected local contract's duration without starting a session. */
public final class ServiceDurationActivity extends RolePage {
    private PersonId personId;
    private ServiceDurationView duration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FeatureEditorSession.attach(this, "duration", state)) return;
        try { personId = PersonRoutes.requireId(getIntent()); }
        catch (IllegalArgumentException invalid) { finish(); return; }
        if (!mayEdit()) { finish(); return; }
        page(R.string.person_duration_title, SubHubNavigation.Screen.PEOPLE, true);
        secondaryBack(this::finish);
        PersonUi.text(body, FeatureEditorSession.targetName(this), false).setId(R.id.person_editor_target);
        duration = new ServiceDurationView(this, null, FeatureEditorSession.context(this), this::mayEdit);
        duration.setId(R.id.person_duration_control);
        duration.setTag("person_duration:" + personId.value());
        duration.setHeadingVisible(false);
        duration.setEditable(true);
        body.addView(duration, new LinearLayout.LayoutParams(-1, -2));
        FeatureEditorSession.bind(this, findViewById(android.R.id.content), "duration", () -> duration.validateSelection());
        InputFocusPolicy.openActivityForReading(this);
    }

    private boolean mayEdit() {
        try {
            return personId != null && personId.equals(PersonRoutes.requireId(getIntent()))
                    && FeatureEditorSession.canEdit(this, "duration");
        } catch (IllegalArgumentException invalid) { return false; }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refresh();
    }

    @Override protected void refresh() {
        if (duration == null) return;
        boolean editable = mayEdit();
        duration.setEditable(editable);
        if (!editable) { finish(); return; }
        duration.reload();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        FeatureEditorSession.saveState(this, state);
        super.onSaveInstanceState(state);
    }

}
