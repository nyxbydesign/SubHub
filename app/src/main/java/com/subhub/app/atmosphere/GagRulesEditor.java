package com.subhub.app.atmosphere;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.subhub.app.R;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.util.StateToggle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Target-scoped Gagging controls, leaving the local keyboard setup on its own page. */
final class GagRulesEditor {
    private final AppCompatActivity activity;
    private final View content;
    private final StateToggle enabled;
    private final RadioGroup modes;
    private final EditText interval;
    private final EditText words;
    private final EditText replacements;
    private final LinearLayout pairRows;
    private final TextView addPair;
    private final Map<String, String> pairs = new LinkedHashMap<>();
    private boolean changed;
    private boolean showAllSettings;

    GagRulesEditor(AppCompatActivity activity, View content, Bundle state) throws Exception {
        this.activity = activity;
        this.content = content;
        enabled = content.findViewById(R.id.gag_enabled);
        modes = content.findViewById(R.id.gag_modes);
        interval = content.findViewById(R.id.gag_interval);
        words = content.findViewById(R.id.gag_words);
        replacements = content.findViewById(R.id.gag_replacements);
        pairRows = content.findViewById(R.id.gag_pair_rows);
        addPair = content.findViewById(R.id.gag_add_pair);
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
        pairs.putAll(settings.pairs());
        if (restoreDraft) {
            List<String> sources = state.getStringArrayList("pair_sources");
            List<String> values = state.getStringArrayList("pair_replacements");
            if (sources != null && values != null && sources.size() == values.size()) {
                pairs.clear();
                for (int i = 0; i < sources.size(); i++) pairs.put(sources.get(i), values.get(i));
            }
        }
        addPair.setOnClickListener(view -> openPairEditor(null));
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
        state.putStringArrayList("pair_sources", new ArrayList<>(pairs.keySet()));
        state.putStringArrayList("pair_replacements", new ArrayList<>(pairs.values()));
        return state;
    }

    void showAllSettings() {
        showAllSettings = true;
        content.findViewById(R.id.gag_rules_button).setVisibility(View.GONE);
        content.findViewById(R.id.gag_rules_heading).setVisibility(View.VISIBLE);
        content.findViewById(R.id.gag_rules_content).setVisibility(View.VISIBLE);
        updateFields();
    }

    private void updateFields() {
        int mode = modes.getCheckedRadioButtonId();
        boolean matching = mode == R.id.gag_specific;
        boolean pairsAllowed = FeatureEditorSession.canEditGagPairs(activity);
        boolean sharedWords = !GagSettings.items(words.getText().toString()).isEmpty();
        updateModeField(R.id.gag_interval_field, interval, mode == R.id.gag_every_n);
        content.findViewById(R.id.gag_pairs_field).setVisibility(matching || showAllSettings ? View.VISIBLE : View.GONE);
        content.findViewById(R.id.gag_pairs_update_required).setVisibility(pairsAllowed ? View.GONE : View.VISIBLE);
        renderPairs(matching && pairsAllowed);
        boolean legacyFields = sharedWords || !pairsAllowed;
        content.findViewById(R.id.gag_words_field).setVisibility(
                legacyFields && (matching || showAllSettings) ? View.VISIBLE : View.GONE);
        words.setEnabled(matching);
        ((TextView) content.findViewById(R.id.gag_words_label)).setText(
                pairsAllowed ? R.string.gag_legacy_shared_words : R.string.gag_words);
        content.findViewById(R.id.gag_shared_replacements_field)
                .setVisibility(!matching || legacyFields ? View.VISIBLE : View.GONE);
    }

    private void renderPairs(boolean editable) {
        pairRows.removeAllViews();
        pairs.forEach((source, replacement) -> {
            TextView row = (TextView) activity.getLayoutInflater().inflate(R.layout.view_ux_action, pairRows, false);
            row.setText(activity.getString(R.string.gag_pair_row, source, replacement));
            row.setContentDescription(activity.getString(R.string.gag_pair_row_accessibility, source, replacement));
            row.setEnabled(editable);
            row.setOnClickListener(view -> openPairEditor(source));
            pairRows.addView(row);
        });
        addPair.setEnabled(editable && pairs.size() < GagWordPairs.MAX_COUNT);
    }

    private void openPairEditor(String existing) {
        if (!FeatureEditorSession.canEditGagPairs(activity)) { updateFields(); return; }
        View form = activity.getLayoutInflater().inflate(R.layout.dialog_gag_word_pair, null);
        EditText source = form.findViewById(R.id.gag_pair_source);
        EditText replacement = form.findViewById(R.id.gag_pair_replacement);
        if (existing != null) {
            source.setText(existing);
            replacement.setText(pairs.get(existing));
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(existing == null ? R.string.gag_add_word_pair : R.string.gag_edit_word_pair)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.gag_save_word_pair, null);
        if (existing != null) builder.setNeutralButton(R.string.gag_remove_word_pair, (dialog, which) -> {
            if (FeatureEditorSession.canEditGagPairs(activity)) {
                pairs.remove(existing);
                rulesChanged();
            }
            updateFields();
        });
        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!FeatureEditorSession.canEditGagPairs(activity)) {
                dialog.dismiss();
                updateFields();
            } else if (savePair(existing, source, replacement)) {
                dialog.dismiss();
            }
        });
    }

    private boolean savePair(String existing, EditText source, EditText replacement) {
        String word = source.getText().toString().trim();
        String value = replacement.getText().toString().trim();
        if (!GagWordPairs.validSource(word)) return showRuleError(source, R.string.gag_pair_source_invalid);
        if (!GagWordPairs.validReplacement(value)) return showRuleError(replacement, R.string.gag_pair_replacement_invalid);
        String key = GagWordPairs.sourceKey(word);
        Map<String, String> updated = new LinkedHashMap<>(pairs);
        updated.remove(existing);
        if (updated.keySet().stream().map(GagWordPairs::sourceKey).anyMatch(key::equals))
            return showRuleError(source, R.string.gag_pair_duplicate);
        updated.put(word, value);
        try {
            GagWordPairs.read(new JSONObject(updated));
        } catch (JSONException | IllegalArgumentException tooLong) {
            return showRuleError(replacement, R.string.gag_pairs_too_long);
        }
        pairs.clear();
        pairs.putAll(updated);
        String oldWords = words.getText().toString();
        StringBuilder remaining = new StringBuilder(oldWords.length());
        for (String line : oldWords.split("(?<=\\n)|(?<=\\r)(?!\\n)")) {
            if (!key.equals(GagWordPairs.sourceKey(line))) remaining.append(line);
        }
        // Moving a word to its own pair removes it from the shared pool.
        if (oldWords.contentEquals(remaining)) rulesChanged();
        else words.setText(remaining);
        updateFields();
        return true;
    }

    private void updateModeField(int fieldId, EditText input, boolean active) {
        content.findViewById(fieldId).setVisibility(active || showAllSettings ? View.VISIBLE : View.GONE);
        input.setEnabled(active);
    }

    private void rulesChanged() {
        if (!FeatureEditorSession.canEditGag(activity)) return;
        changed = true;
        FeatureEditorSession.gagRulesChanged(activity);
    }

    boolean flush() {
        if (!changed || !FeatureEditorSession.canEditGag(activity)) return true;
        if (!pairs.isEmpty() && !FeatureEditorSession.canEditGagPairs(activity)) {
            updateFields();
            return false;
        }
        addPair.setError(null);
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
        boolean matching = mode == GagRules.Mode.MATCH;
        List<String> legacy = GagSettings.items(sourceWords);
        if (legacy.stream().map(GagWordPairs::sourceKey).anyMatch(key -> pairs.keySet().stream()
                .map(GagWordPairs::sourceKey).anyMatch(key::equals))) {
            return showRuleError(words, R.string.gag_pair_shared_conflict);
        }
        if (enabled.isChecked() && matching && legacy.isEmpty() && pairs.isEmpty()) {
            if (FeatureEditorSession.canEditGagPairs(activity))
                return showRuleError(addPair, R.string.gag_pairs_required);
            return showRuleError(words, R.string.gag_words_error);
        }
        if (enabled.isChecked() && (!matching || !legacy.isEmpty()) && GagSettings.items(replacementWords).isEmpty()) {
            replacements.setError(activity.getString(R.string.gag_replacements_error)); return false;
        }
        try {
            FeatureEditorSession.editGagRules(activity,
                    new GagSettings(enabled.isChecked(), mode, count, sourceWords, replacementWords, pairs).json());
            changed = false;
            return true;
        } catch (SecurityException unavailable) {
            updateFields();
            return false;
        } catch (JSONException | IllegalArgumentException invalid) { return false; }
    }
    private boolean showRuleError(TextView input, int message) {
        if (!showAllSettings) {
            TextView header = content.findViewById(R.id.gag_rules_button);
            AtmosphereDisclosure.show(header, content.findViewById(R.id.gag_rules_content), true);
        }
        input.setError(activity.getString(message));
        return false;
    }
}
