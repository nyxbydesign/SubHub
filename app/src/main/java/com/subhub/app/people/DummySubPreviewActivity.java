package com.subhub.app.people;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import com.subhub.app.configuration.ConfigurationSettingCatalog.Field;
import com.subhub.app.util.PrimaryHeader;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Isolated read-only catalog browser. Never constructs a person, session or settings manager. */
public final class DummySubPreviewActivity extends AppCompatActivity {
    private static final String[] SECTIONS = { "modules", "censor", "limits", "wallet", "subliminal", "popup", "apps", "duration" };
    private static final int[] TITLES = { R.string.dummy_sub_modules, R.string.dummy_sub_censor,
            R.string.dummy_sub_limits, R.string.dummy_sub_wallet, R.string.dummy_sub_subliminal,
            R.string.dummy_sub_popup, R.string.dummy_sub_apps, R.string.dummy_sub_duration };
    private final Handler authority = new Handler(Looper.getMainLooper());
    private LinearLayout root, body;
    private ScrollView scroll;
    private String section = "";
    private boolean resumed;
    private final Runnable checkAuthority = new Runnable() {
        @Override public void run() {
            if (resumed && guard()) authority.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!DummySubPreview.isEnabled(this)) { finish(); return; }
        root = PersonUi.column(this);
        root.setBackgroundResource(R.drawable.bg_main);
        int margin = getResources().getDimensionPixelSize(R.dimen.page_margin);
        root.setPadding(margin, getResources().getDimensionPixelSize(R.dimen.primary_header_top), margin, 0);
        getLayoutInflater().inflate(R.layout.view_secondary_header, root, true);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = PersonUi.column(this);
        body.setPadding(0, PersonUi.dp(this, 12), 0, PersonUi.dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        PrimaryHeader.bindSecondary(root, R.string.dummy_sub_title, false);
        PrimaryHeader.backButton(root).setOnClickListener(view -> back());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        String restored = state == null ? "" : state.getString("dummy_preview_section", "");
        if (java.util.Arrays.asList(SECTIONS).contains(restored)) section = restored;
        render();
    }

    private boolean guard() {
        if (DummySubPreview.isEnabled(this)) return true;
        if (root != null) root.setVisibility(View.INVISIBLE);
        finish();
        return false;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (root == null) { finish(); return; }
        if (guard()) { root.setVisibility(View.VISIBLE); authority.post(checkAuthority); }
    }

    @Override protected void onPause() {
        resumed = false;
        authority.removeCallbacks(checkAuthority);
        if (root != null) root.setVisibility(View.INVISIBLE);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("dummy_preview_section", section);
        super.onSaveInstanceState(state);
    }

    private void back() {
        if (!guard()) return;
        if (section.isEmpty()) finish();
        else { section = ""; render(); }
    }

    private void render() {
        if (!guard()) return;
        body.removeAllViews();
        body.setTag(section.isEmpty() ? "dummy_preview_home" : "dummy_preview_page:" + section);
        PersonUi.text(body, getString(R.string.dummy_sub_view_only), true);
        PersonUi.text(body, getString(R.string.dummy_sub_description), false);
        if (section.isEmpty()) {
            for (int i = 0; i < SECTIONS.length; i++) {
                String next = SECTIONS[i];
                PersonUi.row(PersonUi.card(body), PersonUi.icon(next), getString(TITLES[i]),
                        getString(R.string.dummy_sub_defaults), () -> {
                            if (guard()) { section = next; render(); }
                        }).setTag("dummy_preview_section:" + next);
            }
            PersonUi.text(body, getString(R.string.dummy_sub_remove_help), false);
        } else {
            int index = java.util.Arrays.asList(SECTIONS).indexOf(section);
            PersonUi.text(body, getString(TITLES[index]), true);
            if (section.equals("apps")) {
                skeleton("apps_scope", R.string.dummy_sub_apps_scope, R.string.dummy_sub_apps_scope_value);
                skeleton("apps_selection", R.string.dummy_sub_apps_selection, R.string.dummy_sub_apps_selection_value);
            } else if (section.equals("duration")) {
                skeleton("duration_length", R.string.dummy_sub_duration_length, R.string.dummy_sub_duration_length_value);
                skeleton("duration_status", R.string.dummy_sub_duration_status, R.string.dummy_sub_duration_status_value);
            } else {
                int group = 0;
                LinearLayout card = null;
                for (Field field : ConfigurationSettingCatalog.fields(section)) {
                    if (group != field.group) {
                        group = field.group;
                        card = PersonUi.card(body);
                        PersonUi.text(card, getString(group), true);
                    }
                    LinearLayout row = PersonUi.column(this);
                    card.addView(row, new LinearLayout.LayoutParams(-1, -2));
                    row.setTag("dummy_preview_field:" + field.key);
                    PersonUi.text(row, getString(field.label), true);
                    PersonUi.text(row, getString(R.string.dummy_sub_default_value, display(field)), false);
                    if (!field.choices.isEmpty()) {
                        List<String> choices = new ArrayList<>();
                        for (String choice : field.choices)
                            choices.add(getString(ConfigurationSettingCatalog.choiceLabel(field, choice)));
                        PersonUi.text(row, getString(R.string.dummy_sub_choices, String.join(", ", choices)), false);
                    } else if (field.maximum > field.minimum && field.kind != ConfigurationSettingCatalog.Kind.SELECTION) {
                        PersonUi.text(row, getString(R.string.dummy_sub_range,
                                field.displayText(field.minimum), field.displayText(field.maximum)), false);
                    }
                }
            }
        }
        scroll.scrollTo(0, 0);
    }

    private void skeleton(String key, int label, int value) {
        LinearLayout card = PersonUi.card(body);
        card.setTag("dummy_preview_field:" + key);
        PersonUi.text(card, getString(label), true);
        PersonUi.text(card, getString(value), false);
    }

    private String display(Field field) {
        Object value = field.defaultValue();
        if (value instanceof Boolean) return getString((Boolean) value ? R.string.dummy_sub_on : R.string.dummy_sub_off);
        if (!field.choices.isEmpty() && !(value instanceof JSONArray))
            return getString(ConfigurationSettingCatalog.choiceLabel(field, String.valueOf(value)));
        if (value instanceof JSONArray) {
            JSONArray values = (JSONArray) value;
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) {
                String item = values.optString(i);
                labels.add(field.choices.isEmpty() ? item : getString(ConfigurationSettingCatalog.choiceLabel(field, item)));
            }
            return labels.isEmpty() ? getString(R.string.dummy_sub_empty) : String.join(", ", labels);
        }
        String text = field.displayText(value);
        return text.isEmpty() ? getString(R.string.dummy_sub_empty) : text;
    }
}
