package com.subhub.app.people;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.Lifecycle;
import com.subhub.app.R;
import com.subhub.app.remote.RemoteUi;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.SubHubNavigation;
import java.util.LinkedHashMap;
import java.util.Map;

/** Sub's contract exposes configuration details without editing or session mutation actions. */
public final class ContractActivity extends RolePage {
    private String expandedSection = "";
    private boolean expandedAbout;
    private String atmosphereSummary;
    private ContractSections sections;
    private java.util.List<Object> renderedContract;
    private AutoCloseable presenceObserver, configurationObserver;
    private boolean refreshQueued;
    private final Runnable refreshObservedContract = () -> {
        refreshQueued = false;
        if (canRefreshObservedContract()) refresh();
    };
    @Override public void showSectionHome() {
        expandedSection = "";
        expandedAbout = false;
        ProfilePreview preview = body.findViewById(R.id.profile_preview);
        if (preview != null) preview.collapse();
        if (sections != null) sections.updateDetails();
        super.showSectionHome();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            expandedSection = state.getString("contract_expanded_section", "");
            expandedAbout = state.getBoolean("contract_about_expanded");
        }
        page(R.string.role_contract, SubHubNavigation.Screen.CONTRACT, false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        presenceObserver = RemoteUi.observePresence(this::queueObservedRefresh);
        configurationObserver = RemoteUi.observeConfiguration(this::queueObservedRefresh);
    }

    @Override protected void onPause() {
        for (AutoCloseable observer : new AutoCloseable[]{presenceObserver, configurationObserver})
            if (observer != null) try { observer.close(); } catch (Exception ignored) { /* Memory-only observer. */ }
        presenceObserver = null;
        configurationObserver = null;
        body.removeCallbacks(refreshObservedContract);
        refreshQueued = false;
        super.onPause();
    }

    private void queueObservedRefresh() {
        if (canRefreshObservedContract() && !refreshQueued)
            refreshQueued = body.post(refreshObservedContract);
    }

    private boolean canRefreshObservedContract() {
        return body != null && !isFinishing() && !isDestroyed()
                && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("contract_expanded_section", expandedSection);
        state.putBoolean("contract_about_expanded", expandedAbout);
        super.onSaveInstanceState(state);
    }

    @Override protected void refresh() {
        data.cancel("contract-cover");
        loading();
        PersonRepository repository = People.repository(getApplicationContext());
        android.content.Context application = getApplicationContext();
        data.load("contract", () -> {
            PersonSnapshot person = repository.load(PersonId.LOCAL);
            String atmosphere = null;
            if (person.sync.contract == PersonSnapshot.Freshness.CURRENT) {
                atmosphere = AtmosphereContractDetails.local(application).summary;
            }
            return new ContractData(person, atmosphere);
        }, value -> {
            atmosphereSummary = value.atmosphere;
            java.util.List<Object> signature = PersonPresentation.contract(value.person);
            java.util.Collections.addAll(signature, value.person.contractDom, value.person.contractDomId,
                    value.person.contractDomContact, atmosphereSummary,
                    People.keyholder(), com.subhub.app.relationships.RelationshipUi.provider(),
                    ControllerPinManager.domWorkspaceRevision());
            if (signature.equals(renderedContract)) return;
            renderedContract = signature;
            renderPage(() -> render(value.person));
        }, failure -> { renderedContract = null; unavailable(); });
    }

    private void render(PersonSnapshot person) {
        body.removeAllViews();
        LinearLayout identity = OverviewUi.card(body);
        OverviewUi.heading(identity, R.string.overview_your_dom, 0, null);
        identity.setTag("person_contract_identity");
        ContractHeader.add(identity, person, expandedAbout, value -> expandedAbout = value);
        sections = new ContractSections(body, person, atmosphereSummary,
                () -> ServiceDurationSettings.label(this, !ControllerPinManager.isDomModeActive()),
                () -> expandedSection, value -> expandedSection = value);
    }

    private static final class ContractSections {
        private final android.content.Context context;
        private final boolean contractUnknown;
        private final String atmosphereSummary;
        private final java.util.function.Supplier<String> expanded;
        private final java.util.function.Consumer<String> expand;
        private final Map<String, View> details = new LinkedHashMap<>();
        private final Map<String, ImageView> indicators = new LinkedHashMap<>();
        private final Map<String, View> headers = new LinkedHashMap<>();

        ContractSections(LinearLayout body, PersonSnapshot person, String atmosphereSummary,
                java.util.function.Supplier<String> durationFallback,
                java.util.function.Supplier<String> expanded, java.util.function.Consumer<String> expand) {
            this.context = body.getContext();
            this.contractUnknown = person.sync.contract == PersonSnapshot.Freshness.UNKNOWN;
            this.atmosphereSummary = atmosphereSummary;
            this.expanded = expanded;
            this.expand = expand;
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
                    durationFallback.get(), "");
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

        private String getString(int id) { return context.getString(id); }

        private PersonSnapshot.Section find(PersonSnapshot person, String key) {
            for (PersonSnapshot.Section value : person.sections) if (key.equals(value.key)) return value;
            return null;
        }

        private void section(LinearLayout parent, PersonSnapshot.Section source, int icon, boolean separator) {
            PersonSnapshot.Section value = contractUnknown ? new PersonSnapshot.Section(source.key, source.title,
                    getString(R.string.overview_unknown), "") : source;
            if (separator) OverviewUi.divider(parent);
            String summary = "atmosphere".equals(value.key) && atmosphereSummary != null ? atmosphereSummary : value.summary;
            if ("apps".equals(value.key) && !contractUnknown) {
                int count = value.apps.size();
                if (count == 0 && !value.details.isEmpty())
                    for (String label : value.details.split("\n")) if (!label.trim().isEmpty()) count++;
                if (!value.summary.equals(getString(R.string.app_mode_always))
                        && (count > 0 || value.summary.equals(context.getResources().getQuantityString(R.plurals.apps_included_count, 0, 0))))
                    summary = context.getResources().getQuantityString(R.plurals.overview_apps_selected, count, count);
            }
            boolean hasDetails = !value.details.isEmpty() || !value.apps.isEmpty();
            Runnable toggle = !hasDetails ? null : () -> {
                expand.accept(value.key.equals(expanded.get()) ? "" : value.key);
                updateDetails();
            };
            String title = "limits".equals(value.key) ? getString(R.string.overview_app_limits)
                    : "wallet".equals(value.key) ? getString(R.string.overview_wallet)
                    : "duration".equals(value.key) ? getString(R.string.role_service_duration) : value.title;
            int actualIcon = "censor".equals(value.key) ? R.drawable.ic_overview_filter
                    : "duration".equals(value.key) || "limits".equals(value.key) ? R.drawable.ic_overview_clock : icon;
            LinearLayout header = OverviewUi.row(parent, actualIcon, title, summary, toggle, "atmosphere".equals(value.key));
            header.setTag("contract_section:" + value.key);
            if (toggle == null) return;
            ImageView indicator = (ImageView) header.getChildAt(header.getChildCount() - 1);
            LinearLayout detail = PersonUi.column(context);
            detail.setTag("contract_details:" + value.key);
            detail.setPadding(PersonUi.dp(context, 36), PersonUi.dp(context, 4), 0, PersonUi.dp(context, 8));
            if ("apps".equals(value.key)) ContractAppList.add(detail, value);
            else if (hasDetails) for (String block : value.details.split("\\n\\s*\\n")) {
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

        void updateDetails() {
            if (!details.containsKey(expanded.get())) expand.accept("");
            for (String key : details.keySet()) {
                boolean open = key.equals(expanded.get());
                details.get(key).setVisibility(open ? View.VISIBLE : View.GONE);
                indicators.get(key).setRotation(open ? -90 : 90);
                ViewCompat.setStateDescription(headers.get(key), getString(open
                        ? R.string.section_expanded : R.string.section_collapsed));
            }
        }
    }

    private static final class ContractData {
        final PersonSnapshot person;
        final String atmosphere;
        ContractData(PersonSnapshot person, String atmosphere) { this.person = person; this.atmosphere = atmosphere; }
    }
}
