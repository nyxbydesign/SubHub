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
    private LinearLayout body, sections;
    private android.widget.ScrollView rosterScroll;
    private com.subhub.app.util.AsyncUiScope data;
    private com.subhub.app.activity.DomActivityPane feed;
    private boolean activitySelected, resumed, authenticating, rosterLoaded;
    private int rosterPosition;
    private final android.os.Handler authority = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable checkAuthority = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (!mayManagePeople() && !authenticating) {
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
        sections = findViewById(R.id.people_section_container);
        rosterPosition = state == null ? 0 : state.getInt("people_roster_position");
        activitySelected = state == null ? initiallyShowActivity()
                : state.getBoolean("people_activity_selected", initiallyShowActivity());
        PeopleSections.add(this, sections, activitySelected);
        feed = new com.subhub.app.activity.DomActivityPane(this, state);
        ((android.widget.FrameLayout) findViewById(R.id.people_panes)).addView(feed.view(),
                new android.widget.FrameLayout.LayoutParams(-1, -1));
        renderSection();
    }

    protected void refresh() {
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
            if (!mayManagePeople() || activitySelected || !resumed) return;
            rosterLoaded = true;
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
            if (!resumed || activitySelected || !mayManagePeople()) return;
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
            if (mayManagePeople() && resumed && !activitySelected) refresh();
        }, failure -> {
            if (mayManagePeople()) android.widget.Toast.makeText(this,
                    R.string.role_person_unavailable, android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    protected boolean initiallyShowActivity() { return false; }

    public final void showActivity(boolean selected) {
        if (selected == activitySelected || !resumed || !mayManagePeople()) return;
        if (!activitySelected) rosterPosition = rosterScroll.getScrollY();
        feed.pause();
        data.cancel("people");
        activitySelected = selected;
        renderSection();
        if (selected) feed.resume();
        else if (!rosterLoaded) refresh();
    }

    private void renderSection() {
        rosterScroll.setVisibility(activitySelected ? android.view.View.GONE : android.view.View.VISIBLE);
        feed.view().setVisibility(activitySelected ? android.view.View.VISIBLE : android.view.View.GONE);
        PeopleSections.select(sections, activitySelected);
        com.subhub.app.util.PrimaryHeader.bindPrimary(findViewById(android.R.id.content),
                activitySelected ? R.drawable.ic_shell_activity : R.drawable.ic_nav_people,
                activitySelected ? feed.title() : R.string.role_people, 0);
        if (!activitySelected) rosterScroll.post(() -> rosterScroll.scrollTo(0, rosterPosition));
    }

    @Override protected void onResume() {
        super.onResume();
        if (!com.subhub.app.security.DeviceRolePreferences.supportsDom(this)) { finish(); return; }
        resumed = true;
        renderSection();
        android.widget.TextView role = com.subhub.app.util.PrimaryHeader.editLockButton(findViewById(android.R.id.content));
        com.subhub.app.security.ControllerEditMode.renderButton(this, role);
        role.setOnClickListener(view -> com.subhub.app.security.ControllerEditMode.enterSubMode(this));
        SubHubNavigation.bind(this, findViewById(android.R.id.content), SubHubNavigation.Screen.PEOPLE);
        authority.removeCallbacks(checkAuthority);
        authority.postDelayed(checkAuthority, 500);
        if (mayManagePeople()) activateSelected();
        else if (activitySelected && !authenticating) {
            authenticating = true;
            hideProtectedContent();
            com.subhub.app.security.ControllerPinGate.unlock(this, () -> {
                authenticating = false;
                if (resumed && mayManagePeople()) {
                    com.subhub.app.security.ControllerEditMode.renderButton(this, role);
                    SubHubNavigation.bind(this, findViewById(android.R.id.content), SubHubNavigation.Screen.PEOPLE);
                    activateSelected();
                }
            }, true);
        } else if (!authenticating) {
            hideProtectedContent();
            com.subhub.app.security.ControllerEditMode.enterSubMode(this);
        }
    }

    private void activateSelected() {
        authenticating = false;
        renderSection();
        if (activitySelected) feed.resume();
        else refresh();
    }

    private void hideProtectedContent() {
        if (feed != null) feed.pause();
        rosterScroll.setVisibility(android.view.View.INVISIBLE);
        if (feed != null) feed.view().setVisibility(android.view.View.INVISIBLE);
    }

    @Override protected void onPause() {
        if (!activitySelected) rosterPosition = rosterScroll.getScrollY();
        resumed = false;
        authority.removeCallbacks(checkAuthority);
        data.cancel("people");
        hideProtectedContent();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (!activitySelected && rosterScroll.isShown()) rosterPosition = rosterScroll.getScrollY();
        state.putBoolean("people_activity_selected", activitySelected);
        state.putInt("people_roster_position", rosterPosition);
        feed.save(state);
        super.onSaveInstanceState(state);
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (initiallyShowActivity()
                || intent.hasExtra(com.subhub.app.activity.DomActivityActivity.EXTRA_ENTRY_KEY)
                || intent.getBooleanExtra(com.subhub.app.activity.DomActivityActivity.EXTRA_LOCAL_HISTORY, false)) {
            feed.pause();
            data.cancel("people");
            feed.acceptIntent(intent);
            activitySelected = true;
            renderSection();
            if (resumed && mayManagePeople()) feed.resume();
        }
    }

    private boolean mayManagePeople() {
        return ControllerPinManager.isDomModeActive()
                && com.subhub.app.security.DeviceRolePreferences.supportsDom(this);
    }
}
