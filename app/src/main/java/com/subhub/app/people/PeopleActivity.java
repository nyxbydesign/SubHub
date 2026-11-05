package com.subhub.app.people;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.SubHubNavigation;
import java.util.List;

/** The Dom's people list, backed by actual local or paired identities. */
public class PeopleActivity extends androidx.appcompat.app.AppCompatActivity implements SubHubNavigation.SectionHome {
    private LinearLayout body;
    private android.widget.ScrollView rosterScroll;
    private com.subhub.app.util.AsyncUiScope data;
    private boolean resumed;
    private int rosterPosition;
    private boolean restoreRosterPosition = true;
    private LinearLayout roster;
    private TextView favoriteGuidance;
    private LinearLayout activityCard;
    private com.subhub.app.relationships.RelationshipPanel relationships;
    private boolean snapshotUnavailable;
    private final java.util.Map<PersonId, RenderedPerson> renderedPeople = new java.util.LinkedHashMap<>();
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

    @Override public void showSectionHome() {
        rosterPosition = 0;
        restoreRosterPosition = false;
        SubHubNavigation.scrollToTop(findViewById(android.R.id.content));
    }

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
        ensureContent();
        refreshActivityPreview();
        if (snapshotUnavailable) { snapshotUnavailable = false; relationships.refresh(); }
        PersonRepository repository = People.repository(getApplicationContext());
        android.content.Context application = getApplicationContext();
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        data.load("people", () -> {
            if (!ControllerPinManager.isDomWorkspaceUnlocked()
                    || !com.subhub.app.security.DeviceRolePreferences.supportsDom(application))
                throw new IllegalStateException("People context changed");
            com.subhub.app.relationships.RelationshipPresentation.Access access;
            try { access = com.subhub.app.relationships.RelationshipPanel.readAccess(application); }
            catch (Exception unavailable) { return new Roster(null, java.util.Collections.emptyList()); }
            return new Roster(access, com.subhub.app.relationships.RelationshipPanel.roster(
                    application, repository, access, locale));
        }, loaded -> {
            if (!mayManagePeople() || !resumed) return;
            if (loaded.access == null || !loaded.access.current(com.subhub.app.relationships.RelationshipUi::provider)) {
                renderedPeople.clear(); roster.removeAllViews();
                favoriteGuidance.setVisibility(android.view.View.GONE);
                snapshotUnavailable = true; relationships.snapshotUnavailable(); return;
            }
            renderPeople(loaded);
            if (restoreRosterPosition) {
                restoreRosterPosition = false;
                if (rosterPosition > 0) {
                    int restored = rosterPosition;
                    rosterScroll.post(() -> {
                        if (resumed && rosterPosition == restored) rosterScroll.scrollTo(0, restored);
                    });
                }
            }
        }, failure -> {
            if (!resumed || !mayManagePeople()) return;
            showRosterUnavailable();
        });
    }

    private void ensureContent() {
        if (roster != null) return;
        PersonUi.action(body, R.string.role_add_sub, () -> {
            if (!mayManagePeople()) return;
            ConnectionUi ui = People.connectionUi();
            if (ui != null) ui.openPairing(this, true);
            else android.widget.Toast.makeText(this, R.string.role_pairing_unavailable,
                    android.widget.Toast.LENGTH_SHORT).show();
        }, true).setId(R.id.people_add_sub);
        relationships =
                new com.subhub.app.relationships.RelationshipPanel(this, "people",
                        () -> resumed && mayManagePeople(), () -> true, this::refresh);
        body.addView(relationships, new LinearLayout.LayoutParams(-1, -2));
        favoriteGuidance = PersonUi.text(body, getString(R.string.home_favorite_gesture), false);
        roster = PersonUi.column(this);
        body.addView(roster, new LinearLayout.LayoutParams(-1, -2));
        PersonUi.text(roster, getString(R.string.role_loading), false);
    }

    private void refreshActivityPreview() {
        boolean supported = com.subhub.app.activity.ActivitySources.repository().supports(null);
        if (!supported && activityCard != null) { body.removeView(activityCard); activityCard = null; }
        if (supported && activityCard == null) {
            activityCard = PersonUi.column(this); activityCard.setTag("people_activity_card");
            body.addView(activityCard, new LinearLayout.LayoutParams(-1, -2));
            com.subhub.app.activity.ActivityPreview.addSubs(activityCard, data, "people-activity-preview");
        }
    }

    private void showRosterUnavailable() {
        renderedPeople.clear(); roster.removeAllViews();
        favoriteGuidance.setVisibility(android.view.View.GONE);
        PersonUi.text(roster, getString(R.string.role_person_unavailable), false);
        PersonUi.action(roster, R.string.role_retry, this::refresh, false);
    }

    /** Reconcile only changed profiles; a sync must not detach the page or interrupt its scroll gesture. */
    private void renderPeople(Roster loaded) {
        java.util.Set<PersonId> present = new java.util.HashSet<>();
        for (PersonSnapshot person : loaded.people) present.add(person.id);
        java.util.Iterator<java.util.Map.Entry<PersonId, RenderedPerson>> old = renderedPeople.entrySet().iterator();
        while (old.hasNext()) {
            java.util.Map.Entry<PersonId, RenderedPerson> entry = old.next();
            if (!present.contains(entry.getKey())) { roster.removeView(entry.getValue().view); old.remove(); }
        }
        // Initial loading and empty/error notices are separate from retained person cards.
        java.util.Set<android.view.View> cards = new java.util.HashSet<>();
        for (RenderedPerson value : renderedPeople.values()) cards.add(value.view);
        for (int index = roster.getChildCount() - 1; index >= 0; index--)
            if (!cards.contains(roster.getChildAt(index))) roster.removeViewAt(index);
        favoriteGuidance.setVisibility(loaded.people.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        if (!loaded.access.legacy && loaded.people.isEmpty())
            PersonUi.text(roster, getString(R.string.relationships_no_subs), false);
        for (int index = 0; index < loaded.people.size(); index++) {
            PersonSnapshot person = loaded.people.get(index);
            boolean favorite = favorites.isFavorite(person);
            long contact = person.lastOnlineAt;
            String time = contact <= 0 || contact > System.currentTimeMillis() ? getString(R.string.remote_unknown)
                    : ProfileViews.formatLastSeen(contact);
            List<Object> signature = java.util.Arrays.asList(person.profile, person.connection,
                    PersonUi.connection(this, person), person.lastOnlineAt, time, favorite);
            RenderedPerson previous = renderedPeople.get(person.id);
            LinearLayout card;
            if (previous != null && previous.signature.equals(signature)) card = previous.view;
            else {
                LinearLayout staging = PersonUi.column(this);
                card = ProfileViews.person(staging, person, () -> { }); staging.removeView(card);
                if (previous != null) roster.removeView(previous.view);
                if (favorite) {
                    card.setBackgroundResource(R.drawable.bg_home_favorite);
                    TextView mark = PersonUi.text(card, getString(R.string.home_favorite_marked), false);
                    mark.setTextColor(getColor(R.color.accent_text));
                    mark.setTag("people_favorite:" + person.id.value());
                }
                renderedPeople.put(person.id, new RenderedPerson(card, signature));
            }
            ProfileViews.presence(card, "roster:" + person.id.value(), person.id, person.connection);
            if (roster.getChildAt(index) != card) {
                if (card.getParent() == roster) roster.removeView(card);
                roster.addView(card, index);
            }
            card.setOnClickListener(view -> {
                if (mayManagePeople() && loaded.access.current(com.subhub.app.relationships.RelationshipUi::provider))
                    PersonRoutes.open(this, person.id);
            });
            if (!FavoriteSubStore.eligible(person)) continue;
            String action = getString(favorite ? R.string.home_favorite_remove : R.string.home_favorite_set);
            card.setOnLongClickListener(view -> { changeFavorite(person); return true; });
            androidx.appcompat.widget.TooltipCompat.setTooltipText(card, action);
            androidx.core.view.ViewCompat.setStateDescription(card,
                    favorite ? getString(R.string.home_favorite_marked) : null);
            androidx.core.view.ViewCompat.replaceAccessibilityAction(card,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                    action, (view, args) -> { changeFavorite(person); return true; });
        }
    }

    private static final class RenderedPerson {
        final LinearLayout view; final List<Object> signature;
        RenderedPerson(LinearLayout view, List<Object> signature) { this.view = view; this.signature = signature; }
    }

    private void changeFavorite(PersonSnapshot person) {
        if (!mayManagePeople() || data.isPending("favorite-change")) return;
        PersonRepository repository = People.repository(getApplicationContext());
        FavoriteSubStore store = favorites;
        android.content.Context application = getApplicationContext();
        data.load("favorite-change", () -> {
            if (!ControllerPinManager.isDomWorkspaceUnlocked()
                    || !com.subhub.app.security.DeviceRolePreferences.supportsDom(application)) return false;
            com.subhub.app.relationships.RelationshipPresentation.Access access =
                    com.subhub.app.relationships.RelationshipPanel.readAccess(application);
            if (!access.legacy && !com.subhub.app.relationships.RelationshipPresentation.rosterIds(
                    access.snapshot).contains(person.id)) return false;
            PersonSnapshot current = repository.load(person.id);
            if (!ControllerPinManager.isDomWorkspaceUnlocked()
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
        return ControllerPinManager.isDomWorkspaceUnlocked()
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
