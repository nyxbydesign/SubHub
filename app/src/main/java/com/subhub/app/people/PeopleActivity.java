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
        loading();
        PersonRepository repository = People.repository(getApplicationContext());
        data.load("people", () -> {
            List<PersonSnapshot> values = new ArrayList<>();
            for (PersonId id : repository.people()) values.add(repository.load(id));
            return values;
        }, people -> {
            if (!ControllerPinManager.isDomModeActive()) return;
            body.removeAllViews();
            for (PersonSnapshot person : people) {
                LinearLayout card = PersonUi.card(body);
                PersonUi.row(card, R.drawable.ic_nav_people, person.name,
                        PersonUi.connection(this, person) + "\n" + person.contractName,
                        () -> PersonRoutes.open(this, person.id));
            }
        }, failure -> unavailable());
    }
}
