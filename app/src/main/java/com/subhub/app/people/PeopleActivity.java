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
public class PeopleActivity extends androidx.appcompat.app.AppCompatActivity {
    private LinearLayout body;
    private android.widget.ScrollView rosterScroll;
    private com.subhub.app.util.AsyncUiScope data;
    private boolean resumed;
    private int rosterPosition;
    private final android.os.Handler authority = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable checkAuthority = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (!mayManagePeople()) {
                hideProtectedContent();
                com.subhub.app.security.ControllerEditMode.enterSubMode(PeopleActivity.this);
                return;
            }
            authority.postDelayed(this, 500);
        }
    };
    private FavoriteSubStore favorites;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        favorites = new FavoriteSubStore(this);
        setContentView(R.layout.activity_people);
        data = com.subhub.app.util.AsyncUiScope.forPage(this);
        body = findViewById(R.id.role_page_body);
        rosterScroll = findViewById(R.id.people_roster_scroll);
        rosterPosition = state == null ? 0 : state.getInt("people_roster_position");
        com.subhub.app.util.PrimaryHeader.bindPrimary(findViewById(android.R.id.content),
                R.drawable.ic_nav_people, R.string.role_people, 0);
    }

    protected void refresh() {
        if (!com.subhub.app.security.DeviceRolePreferences.supportsDom(this)) { finish(); return; }
        if (rosterScroll.getScrollY() > 0) rosterPosition = rosterScroll.getScrollY();
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
        if (com.subhub.app.activity.ActivitySources.repository().supports(null)) {
            LinearLayout activityCard = OverviewUi.compactCard(body);
            activityCard.setTag("people_activity_card");
            OverviewUi.row(activityCard, R.drawable.ic_shell_activity, getString(R.string.dom_activity_title), "", () -> {
                if (mayManagePeople()) startActivity(new android.content.Intent(this,
                        com.subhub.app.activity.DomActivityActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            }).setTag("people_activity_open");
        }
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
            if (!mayManagePeople() || !resumed) return;
            roster.removeAllViews();
            for (PersonSnapshot person : people) {
                LinearLayout card = ProfileViews.person(roster, person, () -> {
                    if (mayManagePeople()) PersonRoutes.open(this, person.id);
                });
                if (!FavoriteSubStore.eligible(person)) continue;
                boolean favorite = favorites.isFavorite(person);
                if (favorite) {
                    card.setBackgroundResource(R.drawable.bg_home_favorite);
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
            rosterScroll.post(() -> rosterScroll.scrollTo(0, rosterPosition));
        }, failure -> {
            if (!resumed || !mayManagePeople()) return;
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
            if (mayManagePeople() && resumed) refresh();
        }, failure -> {
            if (mayManagePeople()) android.widget.Toast.makeText(this,
                    R.string.role_person_unavailable, android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (!com.subhub.app.security.DeviceRolePreferences.supportsDom(this)) { finish(); return; }
        resumed = true;
        rosterScroll.setVisibility(android.view.View.VISIBLE);
        com.subhub.app.util.PrimaryHeader.bindPrimary(findViewById(android.R.id.content),
                R.drawable.ic_nav_people, R.string.role_people, 0);
        android.widget.TextView role = com.subhub.app.util.PrimaryHeader.editLockButton(findViewById(android.R.id.content));
        com.subhub.app.security.ControllerEditMode.renderButton(this, role);
        role.setOnClickListener(view -> com.subhub.app.security.ControllerEditMode.enterSubMode(this));
        SubHubNavigation.bind(this, findViewById(android.R.id.content), SubHubNavigation.Screen.PEOPLE);
        authority.removeCallbacks(checkAuthority);
        authority.postDelayed(checkAuthority, 500);
        if (mayManagePeople()) refresh();
        else {
            hideProtectedContent();
            com.subhub.app.security.ControllerEditMode.enterSubMode(this);
        }
    }

    private void hideProtectedContent() {
        rosterScroll.setVisibility(android.view.View.INVISIBLE);
    }

    @Override protected void onPause() {
        rosterPosition = rosterScroll.getScrollY();
        resumed = false;
        authority.removeCallbacks(checkAuthority);
        data.cancel("people");
        hideProtectedContent();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (rosterScroll.isShown()) rosterPosition = rosterScroll.getScrollY();
        state.putInt("people_roster_position", rosterPosition);
        super.onSaveInstanceState(state);
    }

    private boolean mayManagePeople() {
        return ControllerPinManager.isDomModeActive()
                && com.subhub.app.security.DeviceRolePreferences.supportsDom(this);
    }
}
