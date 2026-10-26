package com.subhub.app.people;

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
        try { personId = PersonRoutes.requireId(getIntent()); }
        catch (IllegalArgumentException invalid) { finish(); return; }
        if (!mayEdit()) { finish(); return; }
        page(R.string.person_duration_title, SubHubNavigation.Screen.PEOPLE, true);
        secondaryBack(this::finish);
        PersonUi.text(body, getString(R.string.role_this_device), false).setId(R.id.person_editor_target);
        duration = new ServiceDurationView(this, null);
        duration.setId(R.id.person_duration_control);
        duration.setTag("person_duration:" + personId.value());
        duration.setHeadingVisible(false);
        duration.setEditable(true);
        body.addView(duration, new LinearLayout.LayoutParams(-1, -2));
        InputFocusPolicy.openActivityForReading(this);
    }

    private boolean mayEdit() {
        try {
            return personId != null && personId.equals(PersonRoutes.requireId(getIntent()))
                    && PersonRoutes.canEditLocal(this, personId);
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
}
