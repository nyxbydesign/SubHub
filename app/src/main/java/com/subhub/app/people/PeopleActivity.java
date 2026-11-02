package com.subhub.app.people;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.SubHubNavigation;
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
        com.subhub.app.relationships.RelationshipPanel relationships =
                new com.subhub.app.relationships.RelationshipPanel(this, "people",
                        () -> resumed && mayManagePeople(), () -> true, this::refresh);
        body.addView(relationships, new LinearLayout.LayoutParams(-1, -2));
        TextView favoriteGuidance = PersonUi.text(body, getString(R.string.home_favorite_gesture), false);
        LinearLayout roster = PersonUi.column(this);
        body.addView(roster, new LinearLayout.LayoutParams(-1, -2));
        PersonUi.text(roster, getString(R.string.role_loading), false);
        if (com.subhub.app.activity.ActivitySources.repository().supports(null)) {
            LinearLayout activityCard = PersonUi.column(this);
            activityCard.setTag("people_activity_card");
            body.addView(activityCard, new LinearLayout.LayoutParams(-1, -2));
            com.subhub.app.activity.ActivityPreview.addSubs(activityCard, data, "people-activity-preview");
        }
        PersonRepository repository = People.repository(getApplicationContext());
        android.content.Context application = getApplicationContext();
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        data.load("people", () -> {
            if (!ControllerPinManager.isDomModeActive()
                    || !com.subhub.app.security.DeviceRolePreferences.supportsDom(application))
                throw new IllegalStateException("People context changed");
            com.subhub.app.relationships.RelationshipPresentation.Access access;
            try { access = com.subhub.app.relationships.RelationshipPanel.readAccess(application); }
            catch (Exception unavailable) { return new Roster(null, java.util.Collections.emptyList()); }
            return new Roster(access, com.subhub.app.relationships.RelationshipPanel.roster(
                    application, repository, access, locale));
        }, loaded -> {
            if (!mayManagePeople() || !resumed) return;
            roster.removeAllViews();
            if (loaded.access == null || !loaded.access.current(com.subhub.app.relationships.RelationshipUi::provider)) {
                favoriteGuidance.setVisibility(android.view.View.GONE);
                relationships.snapshotUnavailable(); return;
            }
            favoriteGuidance.setVisibility(loaded.people.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
            if (!loaded.access.legacy && loaded.people.isEmpty())
                PersonUi.text(roster, getString(R.string.relationships_no_subs), false);
            for (PersonSnapshot person : loaded.people) {
                LinearLayout card = ProfileViews.person(roster, person, () -> {
                    if (mayManagePeople() && loaded.access.current(com.subhub.app.relationships.RelationshipUi::provider))
                        PersonRoutes.open(this, person.id);
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
            favoriteGuidance.setVisibility(android.view.View.GONE);
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
            com.subhub.app.relationships.RelationshipPresentation.Access access =
                    com.subhub.app.relationships.RelationshipPanel.readAccess(application);
            if (!access.legacy && !com.subhub.app.relationships.RelationshipPresentation.rosterIds(
                    access.snapshot).contains(person.id)) return false;
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

    private static final class Roster {
        final com.subhub.app.relationships.RelationshipPresentation.Access access;
        final List<PersonSnapshot> people;
        Roster(com.subhub.app.relationships.RelationshipPresentation.Access access, List<PersonSnapshot> people) {
            this.access = access; this.people = people;
        }
    }
}
