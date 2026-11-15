package com.subhub.app.atmosphere;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.util.StateToggle;

import org.json.JSONException;

/** Target-scoped Gagging controls, leaving the local keyboard setup on its own page. */
final class GagRulesEditor {
    private final AppCompatActivity activity;
    private final View content;
    private final StateToggle enabled;
    private final RadioGroup modes;
    private final EditText interval;
    private final EditText words;
    private final EditText replacements;
    private boolean changed;

    GagRulesEditor(AppCompatActivity activity, View content, Bundle state) throws Exception {
        this.activity = activity;
        this.content = content;
        enabled = content.findViewById(R.id.gag_enabled);
        modes = content.findViewById(R.id.gag_modes);
        interval = content.findViewById(R.id.gag_interval);
        words = content.findViewById(R.id.gag_words);
        replacements = content.findViewById(R.id.gag_replacements);
        GagSettings settings = GagSettings.from(FeatureEditorSession.gagRules(activity));
        enabled.setText(R.string.gag_enable);
        boolean restoreDraft = state != null && state.getBoolean("changed");
        enabled.setChecked(restoreDraft ? state.getBoolean("enabled") : settings.enabled());
        modes.check(!restoreDraft ? switch (settings.mode()) {
            case ALL -> R.id.gag_all;
            case INTERVAL -> R.id.gag_every_n;
            case MATCH -> R.id.gag_specific;
        } : state.getInt("mode"));
        interval.setText(!restoreDraft ? Integer.toString(settings.interval()) : state.getString("interval"));
        words.setText(!restoreDraft ? settings.words() : state.getString("words"));
        replacements.setText(!restoreDraft ? settings.replacements() : state.getString("replacements"));
        changed = state != null && state.getBoolean("changed");
        enabled.setOnCheckedChangeListener((view, checked) -> rulesChanged());
        modes.setOnCheckedChangeListener((group, id) -> { updateFields(); rulesChanged(); });
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                rulesChanged();
            }
            @Override public void afterTextChanged(Editable value) {}
        };
        for (EditText input : new EditText[] {interval, words, replacements}) input.addTextChangedListener(watcher);
        AtmosphereDisclosure.bind(content.findViewById(R.id.gag_rules_button),
                content.findViewById(R.id.gag_rules_content), state != null && state.getBoolean("expanded"));
        updateFields();
    }

    Bundle saveState() {
        Bundle state = new Bundle();
        state.putBoolean("expanded", content.findViewById(R.id.gag_rules_content).getVisibility() == View.VISIBLE);
        state.putBoolean("changed", changed);
        if (!changed) return state;
        state.putBoolean("enabled", enabled.isChecked());
        state.putInt("mode", modes.getCheckedRadioButtonId());
        state.putString("interval", interval.getText().toString());
        state.putString("words", words.getText().toString());
        state.putString("replacements", replacements.getText().toString());
        return state;
    }

    private void updateFields() {
        int mode = modes.getCheckedRadioButtonId();
        content.findViewById(R.id.gag_interval_field).setVisibility(mode == R.id.gag_every_n ? View.VISIBLE : View.GONE);
        content.findViewById(R.id.gag_words_field).setVisibility(mode == R.id.gag_specific ? View.VISIBLE : View.GONE);
    }

    private void rulesChanged() {
        if (!FeatureEditorSession.canEditGag(activity)) return;
        changed = true;
        FeatureEditorSession.gagRulesChanged(activity);
    }

    boolean flush() {
        if (!changed || !FeatureEditorSession.canEditGag(activity)) return true;
        for (EditText input : new EditText[] {interval, words, replacements}) input.setError(null);
        int id = modes.getCheckedRadioButtonId();
        GagRules.Mode mode = id == R.id.gag_every_n ? GagRules.Mode.INTERVAL
                : id == R.id.gag_specific ? GagRules.Mode.MATCH : GagRules.Mode.ALL;
        int count = 3;
        if (mode == GagRules.Mode.INTERVAL) {
            try { count = Integer.parseInt(interval.getText().toString().trim()); }
            catch (NumberFormatException invalid) { count = 0; }
            if (count < 1 || count > 999) { return showRuleError(interval, R.string.gag_interval_error); }
        }
        String sourceWords = words.getText().toString();
        String replacementWords = replacements.getText().toString();
        if (enabled.isChecked() && mode == GagRules.Mode.MATCH && GagSettings.items(sourceWords).isEmpty()) {
            return showRuleError(words, R.string.gag_words_error);
        }
        if (enabled.isChecked() && GagSettings.items(replacementWords).isEmpty()) {
            replacements.setError(activity.getString(R.string.gag_replacements_error)); return false;
        }
        try {
            FeatureEditorSession.editGagRules(activity,
                    new GagSettings(enabled.isChecked(), mode, count, sourceWords, replacementWords).json());
            changed = false;
            return true;
        } catch (JSONException | IllegalArgumentException invalid) { return false; }
    }
    private boolean showRuleError(EditText input, int message) {
        TextView header = content.findViewById(R.id.gag_rules_button);
        AtmosphereDisclosure.show(header, content.findViewById(R.id.gag_rules_content), true);
        input.setError(activity.getString(message));
        return false;
    }
}
