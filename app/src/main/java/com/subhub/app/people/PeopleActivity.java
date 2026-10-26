package com.subhub.app.people;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.SubHubNavigation;
import java.util.ArrayList;
import java.util.List;

/** The Dom's people list, backed by actual local or paired identities. */
public final class PeopleActivity extends RolePage {
    private FavoriteSubStore favorites;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        favorites = new FavoriteSubStore(this);
        page(R.string.role_people, SubHubNavigation.Screen.PEOPLE, true);
    }

    @Override protected void refresh() {
        if (!com.subhub.app.security.DeviceRolePreferences.supportsDom(this)) { finish(); return; }
        body.removeAllViews();
        PersonUi.action(body, R.string.role_add_sub, () -> {
            if (!mayManagePeople()) return;
            ConnectionUi ui = People.connectionUi();
            if (ui != null) ui.openPairing(this, true);
            else android.widget.Toast.makeText(this, R.string.role_pairing_unavailable,
                    android.widget.Toast.LENGTH_SHORT).show();
        }, true).setId(R.id.people_add_sub);
        PersonUi.text(body, getString(R.string.home_favorite_gesture), false);
        LinearLayout roster = PersonUi.column(this);
        body.addView(roster, new LinearLayout.LayoutParams(-1, -2));
        PersonUi.text(roster, getString(R.string.role_loading), false);
        PersonRepository repository = People.repository(getApplicationContext());
        android.content.Context application = getApplicationContext();
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        data.load("people", () -> {
            List<PersonSnapshot> values = new ArrayList<>();
            if (!ControllerPinManager.isDomModeActive()
                    || !com.subhub.app.security.DeviceRolePreferences.supportsDom(application)) return values;
            for (PersonId id : repository.people()) {
                if (!ControllerPinManager.isDomModeActive()
                        || !com.subhub.app.security.DeviceRolePreferences.supportsDom(application)) break;
                values.add(repository.load(id));
            }
            return PersonDisplayOrder.sorted(values, locale);
        }, people -> {
            if (!mayManagePeople()) return;
            roster.removeAllViews();
            for (PersonSnapshot person : people) {
                LinearLayout card = ProfileViews.person(roster, person, () -> {
                    if (mayManagePeople()) PersonRoutes.open(this, person.id);
                });
                if (!FavoriteSubStore.eligible(person)) continue;
                boolean favorite = favorites.isFavorite(person);
                if (favorite) {
                    TextView mark = PersonUi.text(card, getString(R.string.home_favorite_marked), false);
                    mark.setTextColor(getColor(R.color.accent_text));
                    mark.setTag("people_favorite:" + person.id.value());
                }
                String action = getString(favorite ? R.string.home_favorite_remove : R.string.home_favorite_set);
                card.setOnLongClickListener(view -> { changeFavorite(person); return true; });
                androidx.appcompat.widget.TooltipCompat.setTooltipText(card, action);
                androidx.core.view.ViewCompat.setStateDescription(card,
                        favorite ? getString(R.string.home_favorite_marked) : null);
                androidx.core.view.ViewCompat.replaceAccessibilityAction(card,
                        androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                        action, (view, args) -> { changeFavorite(person); return true; });
            }
        }, failure -> {
            roster.removeAllViews();
            PersonUi.text(roster, getString(R.string.role_person_unavailable), false);
            PersonUi.action(roster, R.string.role_retry, this::refresh, false);
        });
    }

    private void changeFavorite(PersonSnapshot person) {
        if (!mayManagePeople() || data.isPending("favorite-change")) return;
        PersonRepository repository = People.repository(getApplicationContext());
        FavoriteSubStore store = favorites;
        android.content.Context application = getApplicationContext();
        data.load("favorite-change", () -> {
            if (!ControllerPinManager.isDomModeActive()
                    || !com.subhub.app.security.DeviceRolePreferences.supportsDom(application)) return false;
            PersonSnapshot current = repository.load(person.id);
            if (!ControllerPinManager.isDomModeActive()
                    || !com.subhub.app.security.DeviceRolePreferences.supportsDom(application)
                    || !FavoriteSubStore.eligible(current)) return false;
            return store.toggle(current);
        }, changed -> {
            if (mayManagePeople()) refresh();
        }, failure -> {
            if (mayManagePeople()) android.widget.Toast.makeText(this,
                    R.string.role_person_unavailable, android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private boolean mayManagePeople() {
        return ControllerPinManager.isDomModeActive()
                && com.subhub.app.security.DeviceRolePreferences.supportsDom(this);
    }
}
