package com.subhub.app.atmosphere;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.people.DummySubPreview;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.PersonUi;
import com.subhub.app.people.ProfileStore;
import com.subhub.app.remote.GagContractFields;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.InputFocusPolicy;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.StateToggle;

public final class GagActivity extends AppCompatActivity {
    private static final String WORKSPACE = "gag_editor_workspace";
    private static final String DICTIONARY_EXPANDED = "gag_dictionary_expanded";
    private static final String EDITOR_STATE = "gag_rules_editor_state";
    private GagRulesEditor editor;
    private TextView keyboardStatus;
    private TextView keyboardAction;
    private GagPersonalDictionary dictionary;
    private View dictionaryButton;
    private View dictionaryContent;
    private StateToggle learningEnabled;
    private boolean localSetup;
    private LinearLayout customWords;
    private EditText customWord;
    private boolean keyboardAvailable;

    public static boolean canSetUp(Context context) { return ProfileStore.mayEdit(context, false); }

    static Intent editorIntent(AppCompatActivity activity) {
        return FeatureEditorSession.child(activity, GagActivity.class)
                .putExtra(WORKSPACE, ControllerPinManager.domWorkspaceRevision());
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        localSetup = PersonRoutes.isLocalTarget(getIntent()) && !DummySubPreview.isRequested(getIntent());
        if (!localSetup && !currentWorkspace()) { finish(); return; }
        if (!localSetup && !FeatureEditorSession.attach(this, GagContractFields.SECTION, state)) return;
        if (!requireRole()) return;
        setContentView(localSetup ? R.layout.activity_gag_setup : R.layout.activity_gag);
        View content = findViewById(R.id.gag_content);
        PrimaryHeader.bindSecondary(content, localSetup ? R.string.gag_keyboard_setup : R.string.gag_title, false);
        PrimaryHeader.backButton(content).setOnClickListener(view -> finish());
        if (localSetup) {
            keyboardStatus = findViewById(R.id.gag_keyboard_status);
            keyboardAction = findViewById(R.id.gag_keyboard_settings);
            keyboardAction.setOnClickListener(view -> openKeyboardSetup());
            bindDictionary(state != null && state.getBoolean(DICTIONARY_EXPANDED));
        } else {
            Bundle retained = state != null && (!FeatureEditorSession.internal(this)
                    || FeatureEditorSession.restoreInternalWidgets(this, state)) ? state.getBundle(EDITOR_STATE) : null;
            try {
                editor = new GagRulesEditor(this, findViewById(R.id.gag_rules_settings), retained);
            } catch (Exception unavailable) { finish(); return; }
            FeatureEditorSession.bind(this, findViewById(android.R.id.content), GagContractFields.SECTION, editor::flush);
        }
        InputFocusPolicy.openActivityForReading(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (requireRole() && keyboardAction != null) {
            refreshKeyboardStatus();
            if (dictionaryContent.getVisibility() == View.VISIBLE) refreshDictionary();
        }
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && keyboardAction != null && requireRole()) refreshKeyboardStatus();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (localSetup && dictionaryContent != null)
            state.putBoolean(DICTIONARY_EXPANDED, dictionaryContent.getVisibility() == View.VISIBLE);
        else if (!localSetup && requireRole()) {
            FeatureEditorSession.saveState(this, state);
            if (editor != null) state.putBundle(EDITOR_STATE, editor.saveState());
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onRestoreInstanceState(Bundle state) {
        if (!FeatureEditorSession.internal(this) || FeatureEditorSession.restoreInternalWidgets(this, state))
            super.onRestoreInstanceState(state);
    }

    private void refreshKeyboardStatus() {
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        keyboardAvailable = false;
        boolean selected = false;
        if (manager != null) {
            ComponentName own = new ComponentName(this, GagKeyboardService.class);
            String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            for (InputMethodInfo info : manager.getEnabledInputMethodList()) {
                if (own.equals(info.getComponent())) {
                    keyboardAvailable = true;
                    selected = info.getId().equals(current);
                    break;
                }
            }
        }
        keyboardStatus.setText(selected ? R.string.gag_keyboard_selected
                : keyboardAvailable ? R.string.gag_keyboard_choose_next : R.string.gag_keyboard_enable_next);
        keyboardAction.setText(keyboardAvailable ? R.string.gag_choose_keyboard : R.string.gag_android_keyboard_settings);
    }

    private void openKeyboardSetup() {
        if (!requireRole()) return;
        refreshKeyboardStatus();
        if (!keyboardAvailable) startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        else {
            InputMethodManager manager = getSystemService(InputMethodManager.class);
            if (manager != null) manager.showInputMethodPicker();
        }
    }

    private void bindDictionary(boolean expanded) {
        dictionary = new GagPersonalDictionary(getApplicationContext());
        dictionaryButton = findViewById(R.id.gag_personal_dictionary);
        dictionaryContent = findViewById(R.id.gag_dictionary_content);
        learningEnabled = findViewById(R.id.gag_learning_enabled);
        customWord = findViewById(R.id.gag_custom_word);
        customWord.setImeOptions(customWord.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        customWords = findViewById(R.id.gag_custom_words);
        dictionaryButton.setOnClickListener(view -> {
            if (requireRole()) showDictionary(dictionaryContent.getVisibility() != View.VISIBLE);
        });
        findViewById(R.id.gag_add_custom_word).setOnClickListener(view -> addCustomWord());
        customWord.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            addCustomWord();
            return true;
        });
        findViewById(R.id.gag_clear_learned).setOnClickListener(view -> {
            if (!requireRole()) return;
            dictionary.clearLearned();
            Toast.makeText(this, R.string.gag_learned_cleared, Toast.LENGTH_SHORT).show();
        });
        showDictionary(expanded);
    }

    private void showDictionary(boolean expanded) {
        dictionaryContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
        ViewCompat.setStateDescription(dictionaryButton,
                getString(expanded ? R.string.section_expanded : R.string.section_collapsed));
        if (expanded) refreshDictionary();
        else if (customWord.hasFocus()) {
            hideWordKeyboard();
            dictionaryButton.requestFocus();
        }
    }

    private void refreshDictionary() {
        learningEnabled.setOnCheckedChangeListener(null);
        learningEnabled.setChecked(dictionary.isLearningEnabled());
        learningEnabled.setOnCheckedChangeListener((view, checked) -> {
            if (requireRole()) dictionary.setLearningEnabled(checked);
        });
        customWords.removeAllViews();
        for (String word : dictionary.customWords()) {
            LinearLayout row = PersonUi.row(customWords, 0, word, "", null);
            TextView remove = (TextView) getLayoutInflater().inflate(R.layout.view_ux_action, row, false);
            remove.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
            remove.setText(R.string.gag_remove_word);
            remove.setContentDescription(getString(R.string.gag_remove_custom_word, word));
            remove.setOnClickListener(view -> {
                if (requireRole() && dictionary.removeCustomWord(word)) refreshDictionary();
            });
            row.addView(remove);
        }
    }

    private void addCustomWord() {
        if (!requireRole()) return;
        if (!dictionary.addCustomWord(customWord.getText().toString())) {
            customWord.setError(getString(R.string.gag_custom_word_invalid));
            return;
        }
        customWord.setText("");
        hideWordKeyboard();
        refreshDictionary();
    }

    private void hideWordKeyboard() {
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) manager.hideSoftInputFromWindow(customWord.getWindowToken(), 0);
        customWord.clearFocus();
    }

    private boolean requireRole() {
        if (!isFinishing() && (localSetup ? canSetUp(this)
                : currentWorkspace() && FeatureEditorSession.canEditGag(this))) return true;
        finish();
        return false;
    }

    private boolean currentWorkspace() {
        return getIntent().getLongExtra(WORKSPACE, -1) == ControllerPinManager.domWorkspaceRevision();
    }
}
