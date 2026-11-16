package com.subhub.app.atmosphere;

import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.subhub.app.R;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.remote.GagContractFields.Capability;
import com.subhub.app.util.StateToggle;

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
    private final TextView formError;
    private final Map<String, GagWordPairs.Rule> pairs = new LinkedHashMap<>();
    private AlertDialog pairDialog;
    private String editedPair;
    private int retainedInterval;
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
        formError = content.findViewById(R.id.gag_rules_error);
        GagSettings settings = GagSettings.from(FeatureEditorSession.gagRules(activity));
        retainedInterval = settings.interval();
        enabled.setText(R.string.gag_enable);
        boolean restoreDraft = state != null && state.getBoolean("changed");
        enabled.setChecked(restoreDraft ? state.getBoolean("enabled") : settings.enabled());
        modes.check(!restoreDraft ? switch (settings.mode()) {
            case ALL -> R.id.gag_all;
            case INTERVAL -> R.id.gag_every_n;
            case MATCH -> R.id.gag_specific;
        } : state.getInt("mode"));
        interval.setText(state == null ? Integer.toString(settings.interval())
                : state.getString("interval", Integer.toString(settings.interval())));
        words.setText(!restoreDraft ? settings.words() : state.getString("words"));
        replacements.setText(!restoreDraft ? settings.replacements() : state.getString("replacements"));
        pairs.putAll(settings.pairs());
        if (restoreDraft && state.containsKey("pairs")) {
            JSONObject restored = new JSONObject(state.getString("pairs"));
            pairs.clear();
            if (restored.length() > 0) pairs.putAll(GagWordPairs.read(restored));
        }
        addPair.setOnClickListener(view -> openPairEditor(null, null));
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
        words.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                updateSharedFields(FeatureEditorSession.gagCapability(activity));
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        words.setOnFocusChangeListener((view, focused) -> updateSharedFields(FeatureEditorSession.gagCapability(activity)));
        AtmosphereDisclosure.bind(content.findViewById(R.id.gag_rules_button),
                content.findViewById(R.id.gag_rules_content), state != null && state.getBoolean("expanded"));
        updateFields();
        Bundle dialog = state == null ? null : state.getBundle("pair_dialog");
        if (dialog != null) content.post(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) openPairEditor(dialog.getString("existing"), dialog);
        });
    }

    Bundle saveState() {
        Bundle state = new Bundle();
        state.putBoolean("expanded", content.findViewById(R.id.gag_rules_content).getVisibility() == View.VISIBLE);
        state.putBoolean("changed", changed);
        state.putString("interval", interval.getText().toString());
        if (pairDialog != null && pairDialog.isShowing()) {
            Bundle dialog = new Bundle();
            dialog.putString("existing", editedPair);
            dialog.putString("source", ((EditText) pairDialog.findViewById(R.id.gag_pair_source)).getText().toString());
            dialog.putString("replacement", ((EditText) pairDialog.findViewById(R.id.gag_pair_replacement)).getText().toString());
            dialog.putBoolean("case_sensitive", ((StateToggle) pairDialog.findViewById(R.id.gag_pair_case_sensitive)).isChecked());
            state.putBundle("pair_dialog", dialog);
        }
        if (!changed) return state;
        state.putBoolean("enabled", enabled.isChecked());
        state.putInt("mode", modes.getCheckedRadioButtonId());
        state.putString("words", words.getText().toString());
        state.putString("replacements", replacements.getText().toString());
        try { state.putString("pairs", GagWordPairs.json(pairs).toString()); }
        catch (JSONException invalidDraft) { throw new IllegalStateException("Invalid Gag pair draft", invalidDraft); }
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
        Capability capability = FeatureEditorSession.gagCapability(activity);
        boolean pairsAllowed = capability.supports(Capability.PAIRS);
        content.findViewById(R.id.gag_interval_field).setVisibility(mode == R.id.gag_every_n ? View.VISIBLE : View.GONE);
        content.findViewById(R.id.gag_pairs_field).setVisibility(matching ? View.VISIBLE : View.GONE);
        TextView update = content.findViewById(R.id.gag_pairs_update_required);
        update.setText(pairsAllowed ? R.string.gag_pair_options_update_required : R.string.gag_pairs_update_required);
        update.setVisibility(capability.supports(Capability.PAIR_OPTIONS) ? View.GONE : View.VISIBLE);
        renderPairs(capability);
        updateSharedFields(capability);
    }

    private void updateSharedFields(Capability capability) {
        boolean matching = modes.getCheckedRadioButtonId() == R.id.gag_specific;
        boolean pairsAllowed = capability.supports(Capability.PAIRS);
        boolean legacyFields = !GagSettings.items(words.getText().toString()).isEmpty() || !pairsAllowed;
        content.findViewById(R.id.gag_words_field).setVisibility(
                matching && (legacyFields || words.hasFocus()) ? View.VISIBLE : View.GONE);
        ((TextView) content.findViewById(R.id.gag_words_label)).setText(
                pairsAllowed ? R.string.gag_legacy_shared_words : R.string.gag_words);
        content.findViewById(R.id.gag_shared_replacements_field)
                .setVisibility(!matching || legacyFields ? View.VISIBLE : View.GONE);
    }

    private void renderPairs(Capability capability) {
        pairRows.removeAllViews();
        pairs.forEach((source, rule) -> {
            TextView row = (TextView) activity.getLayoutInflater().inflate(R.layout.view_ux_action, pairRows, false);
            String shortSource = preview(source, 22);
            String first = rule.options().get(0);
            String summary = activity.getString(R.string.gag_pair_row, shortSource, preview(first, 26));
            String description = activity.getString(R.string.gag_pair_row_accessibility, source, first);
            if (rule.options().size() > 1) {
                String count = activity.getResources().getQuantityString(R.plurals.gag_more_options,
                        rule.options().size() - 1, rule.options().size() - 1);
                summary = activity.getString(R.string.gag_pair_row_summary, summary, count);
                description = activity.getString(R.string.gag_pair_row_summary, description, count);
            }
            if (rule.caseSensitive()) {
                String sensitive = activity.getString(R.string.gag_pair_case_sensitive);
                summary = activity.getString(R.string.gag_pair_row_summary, summary, sensitive);
                description = activity.getString(R.string.gag_pair_row_summary, description, sensitive);
            }
            SpannableString label = new SpannableString(summary);
            int start = summary.indexOf(shortSource);
            if (start >= 0) label.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start,
                    start + shortSource.length(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setMaxLines(2);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setText(label);
            row.setContentDescription(description);
            row.setEnabled(canEditPair(capability, rule));
            row.setOnClickListener(view -> openPairEditor(source, null));
            pairRows.addView(row);
        });
        addPair.setVisibility(capability.supports(Capability.PAIRS) ? View.VISIBLE : View.GONE);
        addPair.setEnabled(pairs.size() < GagWordPairs.MAX_COUNT);
    }

    private static String preview(String value, int limit) {
        return value.codePointCount(0, value.length()) <= limit ? value
                : value.substring(0, value.offsetByCodePoints(0, limit)) + "…";
    }

    private static boolean canEditPair(Capability capability, GagWordPairs.Rule rule) {
        return capability.supports(rule != null && rule.needsOptions() ? Capability.PAIR_OPTIONS : Capability.PAIRS);
    }

    private void openPairEditor(String existing, Bundle restored) {
        Capability capability = FeatureEditorSession.gagCapability(activity);
        GagWordPairs.Rule rule = pairs.get(existing);
        if (!canEditPair(capability, rule) || existing != null && rule == null) { updateFields(); return; }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_SubHub_GagPairDialog);
        View form = android.view.LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_gag_word_pair, null);
        EditText source = form.findViewById(R.id.gag_pair_source);
        EditText replacement = form.findViewById(R.id.gag_pair_replacement);
        StateToggle sensitive = form.findViewById(R.id.gag_pair_case_sensitive);
        boolean optionsAllowed = capability.supports(Capability.PAIR_OPTIONS);
        form.findViewById(R.id.gag_pair_case_row).setVisibility(optionsAllowed ? View.VISIBLE : View.GONE);
        form.findViewById(R.id.gag_pair_options_hint).setVisibility(optionsAllowed ? View.VISIBLE : View.GONE);
        if (restored != null) {
            source.setText(restored.getString("source"));
            replacement.setText(restored.getString("replacement"));
            sensitive.setChecked(restored.getBoolean("case_sensitive"));
        } else if (rule != null) {
            source.setText(existing);
            replacement.setText(String.join("\n", rule.options()));
            sensitive.setChecked(rule.caseSensitive());
        }
        builder.setTitle(existing == null ? R.string.gag_add_word_pair : R.string.gag_edit_word_pair)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.gag_save_word_pair, null);
        if (existing != null) builder.setNeutralButton(R.string.gag_remove_word_pair, (dialog, which) -> {
            if (canEditPair(FeatureEditorSession.gagCapability(activity), rule)) {
                pairs.remove(existing);
                rulesChanged();
            }
            updateFields();
        });
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        editedPair = existing;
        pairDialog = dialog;
        dialog.setOnDismissListener(ignored -> pairDialog = null);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            Capability current = FeatureEditorSession.gagCapability(activity);
            if (!canEditPair(current, rule)) {
                dialog.dismiss();
                updateFields();
            } else if (savePair(existing, source, replacement, sensitive.isChecked(), current)) {
                dialog.dismiss();
            }
        });
    }

    private boolean savePair(String existing, EditText source, EditText replacement, boolean sensitive, Capability capability) {
        String word = source.getText().toString().trim();
        List<String> options = GagSettings.items(replacement.getText().toString());
        if (!GagWordPairs.validSource(word)) return showRuleError(source, R.string.gag_pair_source_invalid);
        if (options.isEmpty() || options.stream().anyMatch(value -> !GagWordPairs.validReplacement(value)))
            return showRuleError(replacement, R.string.gag_pair_replacement_invalid);
        GagWordPairs.Rule rule = new GagWordPairs.Rule(options, sensitive);
        if (!canEditPair(capability, rule)) return showRuleError(replacement, R.string.gag_pair_options_update_required);
        String key = GagWordPairs.sourceKey(word);
        Map<String, GagWordPairs.Rule> updated = new LinkedHashMap<>(pairs);
        updated.remove(existing);
        if (updated.containsKey(word) || !sensitive && updated.entrySet().stream().anyMatch(entry ->
                !entry.getValue().caseSensitive() && key.equals(GagWordPairs.sourceKey(entry.getKey()))))
            return showRuleError(source, R.string.gag_pair_duplicate);
        updated.put(word, rule);
        try {
            GagWordPairs.read(GagWordPairs.json(updated));
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

    private void rulesChanged() {
        if (!FeatureEditorSession.canEditGag(activity)) return;
        formError.setVisibility(View.GONE);
        changed = true;
        FeatureEditorSession.gagRulesChanged(activity);
    }

    boolean flush() {
        if (!changed || !FeatureEditorSession.canEditGag(activity)) return true;
        Capability capability = FeatureEditorSession.gagCapability(activity);
        if (pairs.values().stream().anyMatch(rule -> !canEditPair(capability, rule))) {
            updateFields();
            return showFormError(capability.supports(Capability.PAIRS) ? R.string.gag_pair_options_update_required
                    : R.string.gag_pairs_update_required);
        }
        addPair.setError(null);
        for (EditText input : new EditText[] {interval, words, replacements}) input.setError(null);
        int id = modes.getCheckedRadioButtonId();
        GagRules.Mode mode = id == R.id.gag_every_n ? GagRules.Mode.INTERVAL
                : id == R.id.gag_specific ? GagRules.Mode.MATCH : GagRules.Mode.ALL;
        int count;
        try { count = Integer.parseInt(interval.getText().toString().trim()); }
        catch (NumberFormatException invalid) { count = 0; }
        if (count >= 1 && count <= 999) retainedInterval = count;
        else if (mode == GagRules.Mode.INTERVAL) return showRuleError(interval, R.string.gag_interval_error);
        String sourceWords = words.getText().toString();
        String replacementWords = replacements.getText().toString();
        boolean matching = mode == GagRules.Mode.MATCH;
        List<String> legacy = GagSettings.items(sourceWords);
        if (legacy.stream().map(GagWordPairs::sourceKey).anyMatch(key -> pairs.keySet().stream()
                .map(GagWordPairs::sourceKey).anyMatch(key::equals))) {
            if (matching) return showRuleError(words, R.string.gag_pair_shared_conflict);
            return showFormError(R.string.gag_pair_hidden_shared_conflict);
        }
        if (enabled.isChecked() && matching && legacy.isEmpty() && pairs.isEmpty()) {
            if (capability.supports(Capability.PAIRS))
                return showRuleError(addPair, R.string.gag_pairs_required);
            return showRuleError(words, R.string.gag_words_error);
        }
        if (enabled.isChecked() && (!matching || !legacy.isEmpty()) && GagSettings.items(replacementWords).isEmpty()) {
            replacements.setError(activity.getString(R.string.gag_replacements_error)); return false;
        }
        try {
            FeatureEditorSession.editGagRules(activity,
                    new GagSettings(enabled.isChecked(), mode, retainedInterval, sourceWords, replacementWords, pairs).json());
            changed = false;
            return true;
        } catch (SecurityException unavailable) {
            updateFields();
            return false;
        } catch (JSONException | IllegalArgumentException invalid) { return showFormError(R.string.gag_rules_invalid); }
    }

    private boolean showFormError(int message) {
        formError.setText(message);
        formError.setVisibility(View.VISIBLE);
        return false;
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
