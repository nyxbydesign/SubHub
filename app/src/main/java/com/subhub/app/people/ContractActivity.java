package com.subhub.app.people;

import android.os.Bundle;
import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.SubHubNavigation;

/** Sub's active contract has no configuration inputs or mutation actions. */
public final class ContractActivity extends RolePage {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.string.role_contract, SubHubNavigation.Screen.CONTRACT, false);
    }

    @Override protected void refresh() {
        loading();
        PersonRepository repository = People.repository(getApplicationContext());
        data.load("contract", () -> repository.load(PersonId.LOCAL), person -> {
            body.removeAllViews();
            LinearLayout summary = PersonUi.card(body);
            PersonUi.text(summary, person.contractName, true);
            PersonUi.text(summary, getString(R.string.role_configured_by_dom), false);
            PersonUi.row(summary, R.drawable.ic_nav_limits, getString(R.string.role_service_duration),
                    ServiceDurationSettings.label(this, !ControllerPinManager.isDomModeActive()), null);
            for (PersonSnapshot.Section section : person.sections) {
                LinearLayout card = PersonUi.card(body);
                PersonUi.row(card, PersonUi.icon(section.key), section.title, section.summary, null);
                if (!section.details.isEmpty()) PersonUi.text(card, section.details, false);
            }
            PersonUi.allowances(body, person);
        }, failure -> unavailable());
    }
}
