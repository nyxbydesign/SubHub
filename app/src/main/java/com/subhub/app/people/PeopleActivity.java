package com.subhub.app.people;

import android.os.Bundle;
import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.SubHubNavigation;
import java.util.ArrayList;
import java.util.List;

/** The Dom's people list, backed by actual local or paired identities. */
public final class PeopleActivity extends RolePage {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.string.role_people, SubHubNavigation.Screen.PEOPLE, true);
    }

    @Override protected void refresh() {
        body.removeAllViews();
        PersonUi.action(body, R.string.role_add_sub, () -> {
            if (!ControllerPinManager.isDomModeActive()) return;
            ConnectionUi ui = People.connectionUi();
            if (ui != null) ui.openPairing(this, true);
            else android.widget.Toast.makeText(this, R.string.role_pairing_unavailable,
                    android.widget.Toast.LENGTH_SHORT).show();
        }, true).setId(R.id.people_add_sub);
        LinearLayout roster = PersonUi.column(this);
        body.addView(roster, new LinearLayout.LayoutParams(-1, -2));
        PersonUi.text(roster, getString(R.string.role_loading), false);
        PersonRepository repository = People.repository(getApplicationContext());
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        data.load("people", () -> {
            List<PersonSnapshot> values = new ArrayList<>();
            for (PersonId id : repository.people()) values.add(repository.load(id));
            return PersonDisplayOrder.sorted(values, locale);
        }, people -> {
            if (!ControllerPinManager.isDomModeActive()) return;
            roster.removeAllViews();
            for (PersonSnapshot person : people) {
                ProfileViews.person(roster, person, () -> PersonRoutes.open(this, person.id));
            }
        }, failure -> {
            roster.removeAllViews();
            PersonUi.text(roster, getString(R.string.role_person_unavailable), false);
            PersonUi.action(roster, R.string.role_retry, this::refresh, false);
        });
    }
}
