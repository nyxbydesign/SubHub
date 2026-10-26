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
    private final Map<String, View> details = new LinkedHashMap<>();
    private final Map<String, TextView> indicators = new LinkedHashMap<>();
    private final Map<String, View> headers = new LinkedHashMap<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) expandedSection = state.getString("contract_expanded_section", "");
        page(R.string.role_contract, SubHubNavigation.Screen.CONTRACT, false);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("contract_expanded_section", expandedSection);
        super.onSaveInstanceState(state);
    }

    @Override protected void refresh() {
        data.cancel("contract-cover");
        loading();
        PersonRepository repository = People.repository(getApplicationContext());
        data.load("contract", () -> repository.load(PersonId.LOCAL), this::render, failure -> unavailable());
    }

    private void render(PersonSnapshot person) {
        body.removeAllViews();
        details.clear();
        indicators.clear();
        headers.clear();
        LinearLayout identity = PersonUi.card(body);
        identity.setBackgroundResource(R.drawable.bg_home_composition_person);
        identity.setTag("person_contract_identity");
        ContractHeader.add(identity, person, getString(R.string.role_configured_by_dom), data);
        PersonUi.text(identity, ProfileViews.targetDiscriminator(this, person), false);
        if (person.sync.hasPendingConfiguration()) PersonUi.text(identity, getString(R.string.role_changes_pending), false);
        if (person.sync.contract != PersonSnapshot.Freshness.CURRENT) PersonUi.text(identity,
                getString(person.sync.contract == PersonSnapshot.Freshness.STALE
                        ? R.string.role_stale_data : R.string.role_unknown_data), false);
        LinearLayout scope = PersonUi.card(body);
        scope.setTag("person_contract_scope");
        PersonUi.text(scope, getString(R.string.profile_contract_scope), true);
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
            if ("apps".equals(value.key) || "duration".equals(value.key)) continue;
            if (features == null) {
                features = PersonUi.card(body);
                features.setTag("person_contract_features");
                PersonUi.text(features, getString(R.string.profile_contract_features), true);
            }
            section(features, value, PersonUi.icon(value.key), features.getChildCount() > 1);
        }
        updateDetails();
    }

    private PersonSnapshot.Section find(PersonSnapshot person, String key) {
        for (PersonSnapshot.Section value : person.sections) if (key.equals(value.key)) return value;
        return null;
    }

    private void section(LinearLayout parent, PersonSnapshot.Section value, int icon, boolean separator) {
        if (separator) {
            View line = new View(this);
            line.setBackgroundColor(getColor(R.color.outline_subtle));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, PersonUi.dp(this, 1));
            size.setMarginStart(PersonUi.dp(this, 48));
            parent.addView(line, size);
        }
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(PersonUi.dp(this, 64));
        header.setPadding(0, PersonUi.dp(this, 12), 0, PersonUi.dp(this, 12));
        header.setTag("contract_section:" + value.key);
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent_text)));
        image.setBackgroundResource(R.drawable.bg_profile_avatar);
        image.setPadding(PersonUi.dp(this, 7), PersonUi.dp(this, 7), PersonUi.dp(this, 7), PersonUi.dp(this, 7));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(image, new LinearLayout.LayoutParams(PersonUi.dp(this, 36), PersonUi.dp(this, 36)));
        LinearLayout copy = PersonUi.column(this);
        LinearLayout.LayoutParams copySize = new LinearLayout.LayoutParams(0, -2, 1);
        copySize.setMarginStart(PersonUi.dp(this, 12));
        header.addView(copy, copySize);
        PersonUi.text(copy, value.title, true);
        if (!value.summary.isEmpty()) PersonUi.text(copy, value.summary, false);
        parent.addView(header, new LinearLayout.LayoutParams(-1, -2));
        if (value.details.isEmpty()) return;
        TextView indicator = new TextView(this);
        indicator.setTextSize(16);
        indicator.setGravity(Gravity.CENTER);
        indicator.setTextColor(getColor(R.color.accent_text));
        indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(indicator, new LinearLayout.LayoutParams(PersonUi.dp(this, 32), -2));
        LinearLayout detail = PersonUi.column(this);
        detail.setTag("contract_details:" + value.key);
        detail.setBackgroundResource(R.drawable.bg_home_composition_stat);
        detail.setPadding(PersonUi.dp(this, 12), PersonUi.dp(this, 4), PersonUi.dp(this, 12), PersonUi.dp(this, 8));
        for (String block : value.details.split("\\n\\s*\\n")) {
            SpannableString copyText = new SpannableString(block);
            int firstLine = "apps".equals(value.key) ? -1 : block.indexOf('\n');
            if (firstLine > 0) copyText.setSpan(new StyleSpan(Typeface.BOLD), 0, firstLine, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            PersonUi.text(detail, copyText, false);
        }
        LinearLayout.LayoutParams detailSize = new LinearLayout.LayoutParams(-1, -2);
        detailSize.bottomMargin = PersonUi.dp(this, 10);
        parent.addView(detail, detailSize);
        details.put(value.key, detail);
        indicators.put(value.key, indicator);
        headers.put(value.key, header);
        header.setFocusable(true);
        header.setClickable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        header.setForeground(getDrawable(ripple.resourceId));
        ViewCompat.setScreenReaderFocusable(header, true);
        header.setOnClickListener(ignored -> {
            expandedSection = value.key.equals(expandedSection) ? "" : value.key;
            updateDetails();
        });
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
}
