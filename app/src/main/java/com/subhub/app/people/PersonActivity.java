package com.subhub.app.people;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.activity.ActivityPreview;
import com.subhub.app.commitment.ServiceDurationView;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.stats.StatsActivity;
import com.subhub.app.util.SubHubNavigation;

/** A selected person's contract, statistics and connection are always identity-bound. */
public final class PersonActivity extends RolePage {
    private PersonId personId;
    private String section = "profile";
    private final android.os.Handler clock = new android.os.Handler(android.os.Looper.getMainLooper());
    private java.util.List<Object> rendered;
    private boolean observing;
    private AutoCloseable appIconObserver;
    private long appIconEpoch;
    private com.subhub.app.relationships.RelationshipUi.Provider appIconProvider;
    private com.subhub.app.security.KeyholderProvider appIconAuthority;
    private TextView currentCountdown;
    private final Runnable observe = new Runnable() {
        @Override public void run() {
            if (!observing) return;
            observePerson();
            if (observing) clock.postDelayed(this, 2000);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        if (!guardTarget()) return;
        observing = true;
        observeAppIcons();
        clock.postDelayed(observe, 2000);
    }

    @Override protected void onPause() {
        observing = false;
        stopAppIcons();
        clock.removeCallbacks(observe);
        if (data != null) { data.cancel("person"); data.cancel("person-observe"); }
        super.onPause();
    }

    @Override protected void onDestroy() { stopAppIcons(); super.onDestroy(); }

    private void observeAppIcons() {
        if (personId == null || personId.isLocal() || !observing || body == null || !body.isAttachedToWindow()) return;
        com.subhub.app.relationships.RelationshipUi.Provider provider = com.subhub.app.relationships.RelationshipUi.provider();
        com.subhub.app.security.KeyholderProvider authority = People.keyholder();
        if (appIconObserver != null && provider == appIconProvider && authority == appIconAuthority) return;
        stopAppIcons(); appIconProvider = provider; appIconAuthority = authority;
        long request = ++appIconEpoch;
        PersonId expected = personId;
        appIconObserver = com.subhub.app.remote.RemoteUi.observeAppIcons(() -> {
            if (request != appIconEpoch || !observing || !expected.equals(personId) || isFinishing() || isDestroyed()
                    || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                    || provider != com.subhub.app.relationships.RelationshipUi.provider() || authority != People.keyholder()
                    || !body.isAttachedToWindow() || !mayRead(this, expected)) return;
            refresh();
        });
    }

    private void stopAppIcons() {
        ++appIconEpoch;
        AutoCloseable old = appIconObserver; appIconObserver = null;
        if (old != null) try { old.close(); } catch (Exception ignored) { /* The epoch also fences queued callbacks. */ }
    }

    private void observePerson() {
        if (!guardTarget()) return;
        observeAppIcons();
        if (currentCountdown != null && personId != null && personId.isLocal()
                && com.subhub.app.commitment.CommitmentManager.isActive(this)) {
            currentCountdown.setText(com.subhub.app.commitment.CommitmentManager.countdownLabel(this));
        }
        if (data.isPending("person") || data.isPending("person-observe")) return;
        PersonId requested = personId;
        android.content.Context app = getApplicationContext();
        PersonRepository repository = People.repository(app);
        com.subhub.app.relationships.RelationshipUi.Provider provider = com.subhub.app.relationships.RelationshipUi.provider();
        com.subhub.app.security.KeyholderProvider authority = People.keyholder();
        data.load("person-observe", () -> {
            if (!mayRead(app, requested)) throw new SecurityException("Profile unavailable");
            return repository.load(requested);
        }, person -> {
            if (!guardTarget() || !observing || !requested.equals(personId)
                    || provider != com.subhub.app.relationships.RelationshipUi.provider() || authority != People.keyholder()) return;
            if (!requested.equals(person.id)) { rendered = null; unavailable(); return; }
            java.util.List<Object> next = signature(person);
            if (next.equals(rendered)) return;
            android.view.ViewParent ancestor = body.getParent();
            while (ancestor != null && !(ancestor instanceof android.widget.ScrollView)) ancestor = ancestor.getParent();
            android.widget.ScrollView scroll = ancestor instanceof android.widget.ScrollView
                    ? (android.widget.ScrollView) ancestor : null;
            int position = scroll == null ? 0 : scroll.getScrollY();
            String currentSection = section;
            rendered = next;
            render(person);
            if (scroll != null) scroll.post(() -> {
                if (guardTarget() && observing && currentSection.equals(section)) scroll.scrollTo(0, position);
            });
        }, failure -> {
            if (guardTarget() && observing && provider == com.subhub.app.relationships.RelationshipUi.provider()
                    && authority == People.keyholder()) { rendered = null; unavailable(); }
        });
    }

    private boolean guardTarget() {
        if (!mayRead(this, personId)) {
            observing = false;
            stopAppIcons();
            clock.removeCallbacks(observe);
            if (data != null) { data.cancel("person"); data.cancel("person-observe"); }
            currentCountdown = null;
            if (body != null) body.removeAllViews();
            finish();
            return false;
        }
        return true;
    }

    private static boolean mayRead(android.content.Context context, PersonId target) {
        return target != null && (target.isLocal()
                ? ControllerPinManager.isDomModeActive() && com.subhub.app.security.DeviceRolePreferences.supportsSub(context)
                : ControllerPinManager.isDomWorkspaceUnlocked() && com.subhub.app.security.DeviceRolePreferences.supportsDom(context));
    }

    private static java.util.List<Object> signature(PersonSnapshot person) {
        String contact = person.lastContactAt <= 0 || person.lastContactAt > System.currentTimeMillis() ? ""
                : ProfileViews.formatLastSeen(person.lastContactAt);
        java.util.List<Object> result = new java.util.ArrayList<>(java.util.Arrays.asList(
                person.id, person.name, person.profile, person.featureConsent, person.consentHardcoreOverride,
                person.device, person.contractName, person.serviceLabel,
                person.connection, person.freshness, person.lastSyncedAt, contact, person.capabilities,
                person.currency, person.protectedSeconds, person.activityEvents, person.tributeCents,
                person.sessions, person.serviceActive, person.sessionIdentity,
                person.sync.contract, person.sync.stats, person.sync.runtime,
                person.sync.desiredRevision, person.sync.appliedRevision));
        for (PersonSnapshot.Section section : person.sections) {
            java.util.Collections.addAll(result, section.key, section.title, section.summary, section.details);
            for (PersonSnapshot.App app : section.apps)
                java.util.Collections.addAll(result, app.packageName, app.label, app.appIcon);
        }
        for (PersonSnapshot.Allowance allowance : person.allowances)
            java.util.Collections.addAll(result, allowance.packageName, allowance.label,
                    allowance.usedMillis, allowance.dailyMinutes, allowance.appIcon);
        return result;
    }


    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { personId = PersonRoutes.requireId(getIntent()); }
        catch (IllegalArgumentException invalid) { finish(); return; }
        section = state == null ? getIntent().getStringExtra("person_section")
                : state.getString("person_section", "profile");
        if (section == null || !java.util.List.of("profile", "contracts", "stats", "connection").contains(section)) section = "profile";
        page(R.string.role_profile, SubHubNavigation.Screen.PEOPLE, true);
        body.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View view) { observeAppIcons(); }
            @Override public void onViewDetachedFromWindow(android.view.View view) { stopAppIcons(); }
        });
        secondaryBack(this::backToProfile);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { backToProfile(); }
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("person_section", section);
        super.onSaveInstanceState(state);
    }

    @Override protected void refresh() {
        if (!guardTarget()) return;
        observeAppIcons();
        data.cancel("person-observe");
        data.cancel("contract-cover");
        loading();
        PersonId requested = personId;
        android.content.Context app = getApplicationContext();
        PersonRepository repository = People.repository(app);
        com.subhub.app.relationships.RelationshipUi.Provider provider = com.subhub.app.relationships.RelationshipUi.provider();
        com.subhub.app.security.KeyholderProvider authority = People.keyholder();
        data.load("person", () -> {
            if (!mayRead(app, requested)) throw new SecurityException("Profile unavailable");
            return repository.load(requested);
        }, person -> {
            if (!guardTarget() || !requested.equals(personId)
                    || provider != com.subhub.app.relationships.RelationshipUi.provider() || authority != People.keyholder()) return;
            if (!requested.equals(person.id)) { rendered = null; unavailable(); return; }
            rendered = signature(person);
            render(person);
        }, failure -> { if (guardTarget() && provider == com.subhub.app.relationships.RelationshipUi.provider()
                && authority == People.keyholder()) unavailable(); });
    }

    private void render(PersonSnapshot person) {
        if (!guardTarget() || !personId.equals(person.id)) return;
        if ("stats".equals(section)) {
            section = "profile";
            render(person);
            openStats(person);
            return;
        }
        currentCountdown = null;
        body.removeAllViews();
        if ("profile".equals(section)) {
            title(R.string.role_profile);
            ProfileViews.identity(body, person);
            session(person);
            LinearLayout destinations = PersonUi.card(body);
            destinations.setTag("person_destinations");
            destination(destinations, person, "contracts", R.drawable.ic_nav_contract, R.string.role_contract);
            PersonUi.row(destinations, R.drawable.ic_nav_tasks, getString(R.string.tasks_title), "", () -> {
                if (guardTarget()) startActivity(new Intent(this, com.subhub.app.tasks.TasksActivity.class)
                        .putExtra(PersonRoutes.EXTRA_PERSON_ID, person.id.value()));
            }).setTag("person_tab:tasks");
            if (person.can(PersonSnapshot.Capability.VIEW_STATS))
                destination(destinations, person, "stats", R.drawable.ic_nav_stats, R.string.role_stats);
            destination(destinations, person, "connection", R.drawable.ic_nav_people, R.string.role_connection);
            ActivityPreview.add(body, person.id, 3, data, "person-recent-activity");
        } else {
            title("connection".equals(section) ? R.string.role_connection : R.string.role_contract);
            PersonUi.text(body, person.name, true);
            if ("connection".equals(section)) {
                PersonUi.text(body, ProfileViews.targetDiscriminator(this, person), false)
                        .setTag("person_target:" + person.id.value());
                connection(person);
            } else {
                ProfileViews.allowedFeatures(body, person);
                contracts(person);
            }
        }
    }

    private void destination(LinearLayout parent, PersonSnapshot person, String key, int icon, int label) {
        PersonUi.row(parent, icon, getString(label), "", () -> {
            if (!guardTarget()) return;
            if ("stats".equals(key)) openStats(person);
            else { section = key; refresh(); }
        }).setTag("person_tab:" + key);
    }

    private void backToProfile() {
        if (!guardTarget()) { finish(); return; }
        if ("profile".equals(section)) finish();
        else { section = "profile"; refresh(); }
    }

    private void session(PersonSnapshot person) {
        if (!person.id.isLocal()) {
            LinearLayout session = PersonUi.card(body);
            PersonUi.text(session, getString(R.string.role_session), true);
            PersonUi.text(session, person.sync.runtime == PersonSnapshot.Freshness.UNKNOWN
                    ? getString(R.string.role_unknown_data) : person.serviceLabel, false);
            if (person.sync.runtime == PersonSnapshot.Freshness.STALE) freshness(session, person.sync.runtime);
            ConnectionUi remote = People.connectionUi();
            if (remote == null) return;
            remote.bindSessionDuration(this, session, person);
            if (remote.bindSessionControls(this, session, person)) return;
            if (person.serviceActive && person.sync.runtime == PersonSnapshot.Freshness.CURRENT
                    && person.can(PersonSnapshot.Capability.END_SESSION) && !person.sessionIdentity.isEmpty()) {
                TextView end = PersonUi.action(session, R.string.remote_session_stop, () -> {
                    if (!guardTarget() || remote != People.connectionUi()) return;
                    remote.endSession(this, person.id, person.sessionIdentity);
                }, false);
                end.setId(R.id.person_session_action);
                end.setTag("person_session:" + person.id.value());
            }
            return;
        }
        if (person.id.isLocal()) {
            LinearLayout session = PersonUi.card(body);
            PersonUi.text(session, getString(R.string.role_session), true);
            PersonUi.text(session, person.serviceLabel, false);
            boolean active = LocalSessionControls.isActive(this);
            if (com.subhub.app.commitment.CommitmentManager.isActive(this)) {
                currentCountdown = PersonUi.text(session,
                        com.subhub.app.commitment.CommitmentManager.countdownLabel(this), true);
                currentCountdown.setTag("person_current_countdown");
            }
            TextView manageSession = PersonUi.action(session,
                    active ? R.string.role_end_session : R.string.role_start_session,
                    () -> {
                        if (!guardTarget()) return;
                        if (active) { LocalSessionControls.end(this, person.id); refresh(); }
                        else PersonRoutes.openLocalSession(this, person.id);
                    }, !active);
            manageSession.setId(R.id.person_session_action);
            manageSession.setTag("person_session:local");
            if (PersonRoutes.canEditLocal(this, person.id)) {
                PersonUi.text(session, getString(active ? R.string.profile_next_session_duration
                        : R.string.role_service_duration), true);
                ServiceDurationView duration = new ServiceDurationView(this, null);
                duration.setHeadingVisible(false);
                duration.setTag("person_duration:local");
                duration.setEditable(true);
                session.addView(duration, new LinearLayout.LayoutParams(-1, -2));
            }
        }
    }

    private void contracts(PersonSnapshot person) {
        if (!person.can(PersonSnapshot.Capability.VIEW_CONTRACT)) {
            PersonUi.text(body, getString(R.string.role_contract_unavailable), false);
            return;
        }

        boolean editable = person.can(PersonSnapshot.Capability.EDIT_CONTRACT)
                && (person.id.isLocal() ? PersonRoutes.canEditLocal(this, person.id)
                        : ControllerPinManager.isDomWorkspaceUnlocked() && People.connectionUi() != null);
        LinearLayout scope = PersonUi.card(body);
        scope.setTag("person_contract_scope");
        PersonUi.text(scope, getString(R.string.profile_contract_scope), true);
        if (person.sync.hasPendingConfiguration()) PersonUi.text(scope,
                getString(R.string.role_changes_pending), false);
        freshness(scope, person.sync.contract);
        PersonSnapshot.Section apps = contractSection(person, "apps");
        TextView appsTitle = PersonUi.text(scope, apps == null ? getString(R.string.person_apps_title) : apps.title, true);
        if (!editable) appsTitle.setTag("person_apps");
        PersonUi.text(scope, apps == null ? getString(R.string.role_unknown_data) : apps.summary, false);
        if (apps != null && (!apps.details.isEmpty() || !apps.apps.isEmpty())) ContractAppList.add(scope, apps);
        if (editable) PersonUi.action(scope, R.string.person_add_more_apps,
                () -> editSection(person, "apps"), false).setTag("person_apps");
        LinearLayout features = null;
        for (PersonSnapshot.Section value : person.sections) {
            if ("apps".equals(value.key) || "duration".equals(value.key)) continue;
            if (features == null) {
                features = PersonUi.card(body);
                features.setTag("person_contract_features");
                PersonUi.text(features, getString(R.string.profile_contract_features), true);
            }
            contractSection(features, person, value, editable);
        }
    }

    private PersonSnapshot.Section contractSection(PersonSnapshot person, String key) {
        for (PersonSnapshot.Section value : person.sections) if (key.equals(value.key)) return value;
        return null;
    }

    private void contractSection(LinearLayout parent, PersonSnapshot person,
            PersonSnapshot.Section value, boolean editable) {
        int icon = "apps".equals(value.key) ? R.drawable.ic_settings_apps
                : "duration".equals(value.key) ? R.drawable.ic_nav_limits : PersonUi.icon(value.key);
        boolean allowed = person.id.isLocal()
                ? !com.subhub.app.security.SubFeaturePolicy.FEATURES.contains(value.key) || com.subhub.app.security.SubFeaturePolicy.allowed(this, value.key)
                : People.connectionUi() != null && People.connectionUi().canEditSection(person.id, value.key);
        LinearLayout row = PersonUi.row(parent, icon, value.title,
                allowed ? value.summary : getString(R.string.sub_feature_disabled),
                editable && allowed ? () -> editSection(person, value.key) : null);
        row.setTag("apps".equals(value.key) ? "person_apps"
                : "duration".equals(value.key) && editable ? "person_duration" : "person_section:" + value.key);
        if (!editable && !value.details.isEmpty()) PersonUi.text(parent, value.details, false);
    }

    private void editSection(PersonSnapshot person, String section) {
        if (!guardTarget() || !personId.equals(person.id)) return;
        if (person.id.isLocal()) {
            PersonRoutes.editSection(this, person.id, section);
            return;
        }
        ConnectionUi ui = People.connectionUi();
        if (!person.id.isLocal() && person.can(PersonSnapshot.Capability.EDIT_CONTRACT)
                && ControllerPinManager.isDomWorkspaceUnlocked() && ui != null)
            ui.editSection(this, person.id, section);
    }

    private void openStats(PersonSnapshot person) {
        if (!guardTarget() || !personId.equals(person.id) || !person.can(PersonSnapshot.Capability.VIEW_STATS)) return;
        if (person.id.isLocal()) startActivity(new Intent(this, StatsActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, person.id.value()));
        else {
            ConnectionUi ui = People.connectionUi();
            if (ui != null) ui.openStats(this, person.id);
        }
    }

    private void connection(PersonSnapshot person) {
        LinearLayout card = PersonUi.card(body);
        PersonUi.text(card, PersonUi.connection(this, person), true);
        PersonUi.row(card, R.drawable.ic_nav_people, getString(R.string.role_device), person.device, null);
        if (!person.id.isLocal()) {
            PersonUi.text(card, person.lastSyncedAt > 0 ? getString(R.string.role_last_synced,
                    android.text.format.DateUtils.getRelativeTimeSpanString(person.lastSyncedAt,
                            System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS))
                    : getString(R.string.role_never_synced), false);
        }
        if (person.id.isLocal() && People.connectionUi() != null) PersonUi.action(body, R.string.role_manage_connection,
                () -> {
                    ConnectionUi ui = People.connectionUi();
                    if (guardTarget() && ui != null) ui.openConnection(this, person.id);
                }, false);
        else if (!person.id.isLocal() && person.can(PersonSnapshot.Capability.MANAGE_CONNECTION) && People.connectionUi() != null) {
            PersonUi.action(body, R.string.role_manage_connection, () -> {
                ConnectionUi ui = People.connectionUi();
                if (guardTarget() && ui != null) ui.openConnection(this, person.id);
            }, false);
        }
    }

    private void freshness(LinearLayout parent, PersonSnapshot.Freshness freshness) {
        if (freshness != PersonSnapshot.Freshness.CURRENT) PersonUi.text(parent,
                getString(freshness == PersonSnapshot.Freshness.STALE
                        ? R.string.role_stale_data : R.string.role_unknown_data), false);
    }
}
