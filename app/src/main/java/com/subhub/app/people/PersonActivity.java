package com.subhub.app.people;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.commitment.ServiceDurationView;
import com.subhub.app.penance.WalletCurrency;
import com.subhub.app.security.AuthenticatorActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.stats.StatsActivity;
import com.subhub.app.stats.StatsSnapshot;
import com.subhub.app.studio.StudioActivity;
import com.subhub.app.util.SubHubNavigation;

/** A selected person's contract, statistics and connection are always identity-bound. */
public final class PersonActivity extends RolePage {
    private PersonId personId;
    private String section = "contracts";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { personId = PersonRoutes.requireId(getIntent()); }
        catch (IllegalArgumentException invalid) { finish(); return; }
        if (state != null) section = state.getString("person_section", "contracts");
        page(R.string.role_profile, SubHubNavigation.Screen.PEOPLE, true);
        secondaryBack(this::finish);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("person_section", section);
        super.onSaveInstanceState(state);
    }

    @Override protected void refresh() {
        if (personId == null) return;
        loading();
        PersonId requested = personId;
        PersonRepository repository = People.repository(getApplicationContext());
        data.load("person", () -> repository.load(requested), person -> {
            if (!ControllerPinManager.isDomModeActive() || !requested.equals(personId)) return;
            render(person);
        }, failure -> unavailable());
    }

    private void render(PersonSnapshot person) {
        body.removeAllViews();
        ProfileViews.identity(body, person);
        session(person);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(getResources().getConfiguration().fontScale >= 1.3f
                || getResources().getConfiguration().screenWidthDp < 360
                ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        body.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        tab(tabs, "contracts", R.string.role_contracts);
        tab(tabs, "stats", R.string.role_stats);
        tab(tabs, "connection", R.string.role_connection);
        if ("stats".equals(section)) stats(person);
        else if ("connection".equals(section)) connection(person);
        else contracts(person);
    }

    private void tab(LinearLayout parent, String key, int label) {
        TextView value = PersonUi.action(parent, label, () -> { section = key; refresh(); }, section.equals(key));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                parent.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1, -2,
                parent.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0);
        params.setMargins(PersonUi.dp(this, 3), 0, PersonUi.dp(this, 3), PersonUi.dp(this, 8));
        value.setLayoutParams(params);
        value.setTag("person_tab:" + key);
        value.setSelected(section.equals(key));
        androidx.core.view.ViewCompat.setStateDescription(value,
                section.equals(key) ? getString(R.string.role_enabled) : null);
    }

    private void session(PersonSnapshot person) {
        if (!person.id.isLocal()) {
            LinearLayout session = PersonUi.card(body);
            PersonUi.text(session, getString(R.string.role_session), true);
            PersonUi.text(session, person.sync.runtime == PersonSnapshot.Freshness.UNKNOWN
                    ? getString(R.string.role_unknown_data) : person.serviceLabel, false);
            if (person.sync.runtime == PersonSnapshot.Freshness.STALE) freshness(session, person.sync.runtime);
            if (person.serviceActive && person.sync.runtime == PersonSnapshot.Freshness.CURRENT
                    && person.can(PersonSnapshot.Capability.END_SESSION)
                    && !person.sessionIdentity.isEmpty() && People.connectionUi() != null) {
                TextView end = PersonUi.action(session, R.string.role_end_session, () -> {
                    if (!ControllerPinManager.isDomModeActive()) return;
                    ConnectionUi ui = People.connectionUi();
                    if (ui != null) ui.endSession(this, person.id, person.sessionIdentity);
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
                PersonUi.text(session, com.subhub.app.commitment.CommitmentManager.countdownLabel(this), true);
            }
            TextView manageSession = PersonUi.action(session,
                    active ? R.string.role_end_session : R.string.role_start_session,
                    () -> {
                        if (!ControllerPinManager.isDomModeActive()) return;
                        if (active) { LocalSessionControls.end(this, person.id); refresh(); }
                        else PersonRoutes.openLocalSession(this, person.id);
                    }, !active);
            manageSession.setId(R.id.person_session_action);
            manageSession.setTag("person_session:local");
        }
    }

    private void contracts(PersonSnapshot person) {
        if (!person.can(PersonSnapshot.Capability.VIEW_CONTRACT)) {
            PersonUi.text(body, getString(R.string.role_contract_unavailable), false);
            return;
        }
        LinearLayout active = PersonUi.card(body);
        ContractHeader.add(active, person, getString(R.string.role_active_contract), data);
        if (person.sync.hasPendingConfiguration()) PersonUi.text(active,
                getString(R.string.role_changes_pending), false);
        freshness(active, person.sync.contract);
        boolean editable = person.can(PersonSnapshot.Capability.EDIT_CONTRACT)
                && (person.id.isLocal() ? PersonRoutes.canEditLocal(this, person.id)
                        : ControllerPinManager.isDomModeActive() && People.connectionUi() != null);
        for (PersonSnapshot.Section value : person.sections) {
            LinearLayout row = PersonUi.row(active, PersonUi.icon(value.key), value.title, value.summary,
                    editable ? () -> {
                        if (person.id.isLocal()) PersonRoutes.editSection(this, person.id, value.key);
                        else if (ControllerPinManager.isDomModeActive() && People.connectionUi() != null)
                            People.connectionUi().editSection(this, person.id, value.key);
                    } : null);
            row.setTag("person_section:" + value.key);
            if (!editable && !value.details.isEmpty()) PersonUi.text(active, value.details, false);
        }
        if (editable && person.id.isLocal()) {
            LinearLayout duration = PersonUi.card(body);
            PersonUi.text(duration, getString(R.string.role_service_duration), true);
            ServiceDurationView control = new ServiceDurationView(this, null);
            control.setHeadingVisible(false);
            control.setEditable(true);
            control.setTag("person_duration:" + person.id.value());
            duration.addView(control, new LinearLayout.LayoutParams(-1, -2));
            PersonUi.action(body, R.string.role_saved_contracts,
                    () -> {
                        if (PersonRoutes.canEditLocal(this, person.id)) startActivity(
                                new Intent(this, StudioActivity.class)
                                        .putExtra(PersonRoutes.EXTRA_PERSON_ID, person.id.value()));
                    }, false).setTag("person_saved_contracts");
        } else if (person.id.isLocal()) {
            PersonUi.row(active, R.drawable.ic_nav_limits, getString(R.string.role_service_duration),
                    ServiceDurationSettings.label(this, true), null);
        }
        if (!person.id.isLocal() && editable) PersonUi.action(body, R.string.role_saved_contracts, () -> {
            ConnectionUi ui = People.connectionUi();
            if (ui != null && ControllerPinManager.isDomModeActive()) ui.openContracts(this, person.id);
        }, false).setTag("person_saved_contracts");
        PersonUi.allowances(body, person);
    }

    private void stats(PersonSnapshot person) {
        if (!person.can(PersonSnapshot.Capability.VIEW_STATS)) {
            PersonUi.text(body, getString(R.string.role_person_unavailable), false);
            return;
        }
        LinearLayout card = PersonUi.card(body);
        freshness(card, person.sync.stats);
        if (person.sync.stats == PersonSnapshot.Freshness.UNKNOWN) return;
        PersonUi.row(card, R.drawable.ic_nav_stats, getString(R.string.role_stat_service_time),
                StatsSnapshot.formatDuration(person.protectedSeconds), null);
        PersonUi.row(card, 0, getString(R.string.role_stat_activity), Long.toString(person.activityEvents), null);
        PersonUi.row(card, 0, getString(R.string.role_stat_sessions), Integer.toString(person.sessions), null);
        if (WalletCurrency.isSupported(person.currency)) PersonUi.row(card, 0,
                getString(R.string.role_stat_tribute), WalletCurrency.format(person.currency, person.tributeCents), null);
        PersonUi.allowances(body, person);
        if (person.id.isLocal()) PersonUi.action(body, R.string.role_view_stats,
                () -> startActivity(new Intent(this, StatsActivity.class)), false).setTag("person_full_stats");
        else if (People.connectionUi() != null) PersonUi.action(body, R.string.role_view_stats, () -> {
            ConnectionUi ui = People.connectionUi();
            if (ui != null && ControllerPinManager.isDomModeActive()) ui.openStats(this, person.id);
        }, false).setTag("person_full_stats");
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
        if (person.id.isLocal()) PersonUi.action(body, R.string.role_keyholder,
                () -> startActivity(new Intent(this, AuthenticatorActivity.class)), false);
        else if (person.can(PersonSnapshot.Capability.MANAGE_CONNECTION) && People.connectionUi() != null) {
            PersonUi.action(body, R.string.role_manage_connection, () -> {
                ConnectionUi ui = People.connectionUi();
                if (ui != null && ControllerPinManager.isDomModeActive()) ui.openConnection(this, person.id);
            }, false);
        }
    }

    private void freshness(LinearLayout parent, PersonSnapshot.Freshness freshness) {
        if (freshness != PersonSnapshot.Freshness.CURRENT) PersonUi.text(parent,
                getString(freshness == PersonSnapshot.Freshness.STALE
                        ? R.string.role_stale_data : R.string.role_unknown_data), false);
    }
}
