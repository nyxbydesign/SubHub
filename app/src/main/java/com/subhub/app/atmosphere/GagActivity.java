package com.subhub.app.atmosphere;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.people.DummySubPreview;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.ProfileStore;
import com.subhub.app.remote.GagContractFields;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.InputFocusPolicy;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.StateToggle;
import org.json.JSONException;

public final class GagActivity extends AppCompatActivity {
    private static final String WORKSPACE = "gag_editor_workspace";
    private StateToggle enabled;
    private RadioGroup modes;
    private EditText interval;
    private EditText words;
    private EditText replacements;
    private TextView keyboardStatus;
    private TextView keyboardAction;
    private boolean localSetup;
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
        } else {
            bindEditor(content);
        }
        InputFocusPolicy.openActivityForReading(this);
    }

    private void bindEditor(View content) {
        enabled = findViewById(R.id.gag_enabled);
        modes = findViewById(R.id.gag_modes);
        interval = findViewById(R.id.gag_interval);
        words = findViewById(R.id.gag_words);
        replacements = findViewById(R.id.gag_replacements);
        GagSettings settings;
        try { settings = GagSettings.from(FeatureEditorSession.gagRules(this)); }
        catch (Exception unavailable) { finish(); return; }
        enabled.setText(R.string.gag_enable);
        enabled.setChecked(settings.enabled());
        modes.check(switch (settings.mode()) {
            case ALL -> R.id.gag_all;
            case INTERVAL -> R.id.gag_every_n;
            case MATCH -> R.id.gag_specific;
        });
        interval.setText(Integer.toString(settings.interval()));
        words.setText(settings.words());
        replacements.setText(settings.replacements());
        enabled.setOnCheckedChangeListener((view, checked) -> rulesChanged());
        modes.setOnCheckedChangeListener((group, id) -> { updateFields(); rulesChanged(); });
        updateFields();
        FeatureEditorSession.bind(this, content, GagContractFields.SECTION, this::applyRules);
    }

    @Override protected void onResume() {
        super.onResume();
        if (requireRole() && keyboardAction != null) refreshKeyboardStatus();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && keyboardAction != null && requireRole()) refreshKeyboardStatus();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (!localSetup && requireRole()) FeatureEditorSession.saveState(this, state);
        super.onSaveInstanceState(state);
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

    private boolean requireRole() {
        if (!isFinishing() && (localSetup ? canSetUp(this)
                : currentWorkspace() && FeatureEditorSession.canEditGag(this))) return true;
        finish();
        return false;
    }

    private boolean currentWorkspace() {
        return getIntent().getLongExtra(WORKSPACE, -1) == ControllerPinManager.domWorkspaceRevision();
    }

    private void updateFields() {
        interval.setVisibility(modes.getCheckedRadioButtonId() == R.id.gag_every_n ? View.VISIBLE : View.GONE);
        words.setVisibility(modes.getCheckedRadioButtonId() == R.id.gag_specific ? View.VISIBLE : View.GONE);
    }

    private void rulesChanged() {
        if (requireRole()) FeatureEditorSession.gagRulesChanged(this);
    }

    private boolean applyRules() {
        if (!requireRole()) return false;
        int id = modes.getCheckedRadioButtonId();
        GagRules.Mode mode = id == R.id.gag_every_n ? GagRules.Mode.INTERVAL
                : id == R.id.gag_specific ? GagRules.Mode.MATCH : GagRules.Mode.ALL;
        int count = 3;
        if (mode == GagRules.Mode.INTERVAL) {
            try { count = Integer.parseInt(interval.getText().toString().trim()); }
            catch (NumberFormatException invalid) { count = 0; }
            if (count < 1 || count > 999) { interval.setError(getString(R.string.gag_interval_error)); return false; }
        }
        String sourceWords = words.getText().toString();
        String replacementWords = replacements.getText().toString();
        if (enabled.isChecked() && mode == GagRules.Mode.MATCH && GagSettings.items(sourceWords).isEmpty()) {
            words.setError(getString(R.string.gag_words_error)); return false;
        }
        if (enabled.isChecked() && GagSettings.items(replacementWords).isEmpty()) {
            replacements.setError(getString(R.string.gag_replacements_error)); return false;
        }
        try {
            FeatureEditorSession.editGagRules(this,
                    new GagSettings(enabled.isChecked(), mode, count, sourceWords, replacementWords).json());
            return true;
        } catch (JSONException | IllegalArgumentException invalid) { return false; }
    }
}
