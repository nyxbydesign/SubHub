package com.subhub.app.people;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.SubHubNavigation;
import java.util.LinkedHashMap;
import java.util.Map;

/** Sub's contract exposes configuration details without editing or session mutation actions. */
public final class ContractActivity extends RolePage {
    private String expandedSection = "";
    private boolean expandedAbout;
    private String atmosphereSummary;
    private boolean contractUnknown;
    private final Map<String, View> details = new LinkedHashMap<>();
    private final Map<String, TextView> indicators = new LinkedHashMap<>();
    private final Map<String, View> headers = new LinkedHashMap<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            expandedSection = state.getString("contract_expanded_section", "");
            expandedAbout = state.getBoolean("contract_about_expanded");
        }
        page(R.string.role_contract, SubHubNavigation.Screen.CONTRACT, false);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("contract_expanded_section", expandedSection);
        state.putBoolean("contract_about_expanded", expandedAbout);
        super.onSaveInstanceState(state);
    }

    @Override protected void refresh() {
        OverviewUi.header(this, true);
        data.cancel("contract-cover");
        loading();
        PersonRepository repository = People.repository(getApplicationContext());
        android.content.Context application = getApplicationContext();
        data.load("contract", () -> {
            PersonSnapshot person = repository.load(PersonId.LOCAL);
            String atmosphere = null;
            if (person.sync.contract == PersonSnapshot.Freshness.CURRENT) {
                boolean whispers = new com.subhub.app.settings.FeatureModuleManager(application).isSubliminalEnabled();
                boolean popup = com.subhub.app.popup.PopupStormSettings.load(application).isEnabled();
                atmosphere = application.getString(R.string.overview_atmosphere_states,
                        application.getString(whispers ? R.string.role_enabled : R.string.role_disabled),
                        application.getString(popup ? R.string.role_enabled : R.string.role_disabled));
            }
            return new ContractData(person, atmosphere);
        }, value -> { atmosphereSummary = value.atmosphere; render(value.person); }, failure -> unavailable());
    }

    private void render(PersonSnapshot person) {
        contractUnknown = person.sync.contract == PersonSnapshot.Freshness.UNKNOWN;
        body.removeAllViews();
        details.clear();
        indicators.clear();
        headers.clear();
        OverviewUi.header(this, true);
        TextView readOnly = OverviewUi.text(this, getString(R.string.overview_read_only), 12, false);
        readOnly.setGravity(Gravity.END); readOnly.setPadding(0, 0, 0, PersonUi.dp(this, 6));
        body.addView(readOnly, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout identity = OverviewUi.card(body);
        OverviewUi.heading(identity, R.string.overview_profile, 0, null);
        identity.setTag("person_contract_identity");
        ContractHeader.add(identity, person, expandedAbout, value -> expandedAbout = value);
        LinearLayout scope = OverviewUi.card(body);
        scope.setTag("person_contract_scope");
        OverviewUi.heading(scope, R.string.overview_apps_duration, 0, null);
        if (person.sync.hasPendingConfiguration()) PersonUi.text(scope, getString(R.string.role_changes_pending), false);
        if (person.sync.contract != PersonSnapshot.Freshness.CURRENT) PersonUi.text(scope,
                getString(person.sync.contract == PersonSnapshot.Freshness.STALE
                        ? R.string.role_stale_data : R.string.role_unknown_data), false);
        PersonSnapshot.Section apps = find(person, "apps");
        if (apps == null) apps = new PersonSnapshot.Section("apps", getString(R.string.person_apps_title),
                getString(R.string.role_unknown_data), "");
        section(scope, apps, R.drawable.ic_settings_apps, false);
        PersonSnapshot.Section duration = find(person, "duration");
        if (duration == null) duration = new PersonSnapshot.Section("duration", getString(R.string.person_duration_title),
                ServiceDurationSettings.label(this, !ControllerPinManager.isDomModeActive()), "");
        section(scope, duration, R.drawable.ic_nav_limits, true);
        LinearLayout features = null;
        for (PersonSnapshot.Section value : person.sections) {
            if (!"censor".equals(value.key) && !"limits".equals(value.key)
                    && !"wallet".equals(value.key) && !"atmosphere".equals(value.key)) continue;
            if (features == null) {
                features = OverviewUi.card(body);
                features.setTag("person_contract_features");
                OverviewUi.heading(features, R.string.overview_features, 0, null);
            }
            section(features, value, PersonUi.icon(value.key), features.getChildCount() > 1);
        }
        updateDetails();
    }

    private PersonSnapshot.Section find(PersonSnapshot person, String key) {
        for (PersonSnapshot.Section value : person.sections) if (key.equals(value.key)) return value;
        return null;
    }

    private void section(LinearLayout parent, PersonSnapshot.Section source, int icon, boolean separator) {
        PersonSnapshot.Section value = contractUnknown ? new PersonSnapshot.Section(source.key, source.title,
                getString(R.string.overview_unknown), "") : source;
        if (separator) OverviewUi.divider(parent);
        String summary = "atmosphere".equals(value.key) && atmosphereSummary != null ? atmosphereSummary : value.summary;
        if ("apps".equals(value.key) && !value.apps.isEmpty()) {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (PersonSnapshot.App app : value.apps) names.add(app.label);
            summary = String.join(", ", names);
        }
        Runnable expand = value.details.isEmpty() ? null : () -> {
            expandedSection = value.key.equals(expandedSection) ? "" : value.key;
            updateDetails();
        };
        String title = "limits".equals(value.key) ? getString(R.string.overview_app_limits)
                : "wallet".equals(value.key) ? getString(R.string.overview_wallet)
                : "duration".equals(value.key) ? getString(R.string.role_service_duration) : value.title;
        int actualIcon = "censor".equals(value.key) ? R.drawable.ic_overview_filter
                : "duration".equals(value.key) || "limits".equals(value.key) ? R.drawable.ic_overview_clock : icon;
        LinearLayout header = OverviewUi.row(parent, actualIcon, title, summary, expand);
        header.setTag("contract_section:" + value.key);
        if (value.details.isEmpty()) return;
        TextView indicator = (TextView) header.getChildAt(header.getChildCount() - 1);
        LinearLayout detail = PersonUi.column(this);
        detail.setTag("contract_details:" + value.key);
        detail.setPadding(PersonUi.dp(this, 36), PersonUi.dp(this, 4), 0, PersonUi.dp(this, 8));
        if ("apps".equals(value.key)) ContractAppList.add(detail, value);
        else for (String block : value.details.split("\\n\\s*\\n")) {
            if ("atmosphere".equals(value.key) && block.startsWith(getString(R.string.atmosphere_whispers_title) + "\n"))
                block = getString(R.string.overview_whispers) + block.substring(block.indexOf('\n'));
            SpannableString copyText = new SpannableString(block);
            int firstLine = block.indexOf('\n');
            if (firstLine > 0) copyText.setSpan(new StyleSpan(Typeface.BOLD), 0, firstLine, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            PersonUi.text(detail, copyText, false);
        }
        parent.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        details.put(value.key, detail); indicators.put(value.key, indicator); headers.put(value.key, header);
    }

    private void updateDetails() {
        if (!details.containsKey(expandedSection)) expandedSection = "";
        for (String key : details.keySet()) {
            boolean expanded = key.equals(expandedSection);
            details.get(key).setVisibility(expanded ? View.VISIBLE : View.GONE);
            indicators.get(key).setText(expanded ? "⌃" : "⌄");
            ViewCompat.setStateDescription(headers.get(key), getString(expanded
                    ? R.string.section_expanded : R.string.section_collapsed));
        }
    }

    private static final class ContractData {
        final PersonSnapshot person;
        final String atmosphere;
        ContractData(PersonSnapshot person, String atmosphere) { this.person = person; this.atmosphere = atmosphere; }
    }
}
