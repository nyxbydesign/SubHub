package com.subhub.app.atmosphere;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.people.ProfileStore;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.util.InputFocusPolicy;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.StateToggle;

/** Device-owned configuration. Paired contracts never activate this input method. */
public final class GagActivity extends AppCompatActivity {
    private StateToggle enabled;
    private RadioGroup modes;
    private EditText interval;
    private EditText words;
    private EditText replacements;

    /** Shared local participation controls. This preference is never added to paired contracts. */
    public static boolean isEnabled(android.content.Context context) { return GagSettings.enabled(context); }

    public static boolean setSubEnabled(android.app.Activity activity, boolean value) {
        if (!DeviceRolePreferences.supportsSub(activity) || DeviceRolePreferences.isDomWorkspace(activity)
                || !ProfileStore.mayEdit(activity, false)) return false;
        GagSettings.setEnabled(activity, value);
        return true;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_gag);
        View content = findViewById(R.id.gag_content);
        PrimaryHeader.bindSecondary(content, R.string.gag_title, false);
        PrimaryHeader.backButton(content).setOnClickListener(view -> finish());
        enabled = findViewById(R.id.gag_enabled);
        modes = findViewById(R.id.gag_modes);
        interval = findViewById(R.id.gag_interval);
        words = findViewById(R.id.gag_words);
        replacements = findViewById(R.id.gag_replacements);
        GagSettings settings = GagSettings.load(this);
        enabled.setChecked(settings.enabled());
        modes.check(switch (settings.mode()) {
            case ALL -> R.id.gag_all;
            case INTERVAL -> R.id.gag_every_n;
            case MATCH -> R.id.gag_specific;
        });
        interval.setText(Integer.toString(settings.interval()));
        words.setText(settings.words());
        replacements.setText(settings.replacements());
        enabled.setOnCheckedChangeListener((view, checked) -> {
            if (!checked) {
                GagSettings current = GagSettings.load(this);
                new GagSettings(false, current.mode(), current.interval(),
                        current.words(), current.replacements()).save(this);
            }
        });
        modes.setOnCheckedChangeListener((group, id) -> updateFields());
        enabled.setText(R.string.gag_enable);
        TextView save = findViewById(R.id.gag_save);
        save.setText(R.string.gag_save);
        save.setOnClickListener(view -> save());
        TextView keyboardSettings = findViewById(R.id.gag_keyboard_settings);
        keyboardSettings.setText(R.string.gag_android_keyboard_settings);
        keyboardSettings.setOnClickListener(
                view -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        TextView keyboardPicker = findViewById(R.id.gag_keyboard_picker);
        keyboardPicker.setText(R.string.gag_choose_keyboard);
        keyboardPicker.setOnClickListener(view -> {
            InputMethodManager manager = getSystemService(InputMethodManager.class);
            if (manager != null) manager.showInputMethodPicker();
        });
        updateFields();
        InputFocusPolicy.openActivityForReading(this);
    }

    private void updateFields() {
        interval.setVisibility(modes.getCheckedRadioButtonId() == R.id.gag_every_n ? View.VISIBLE : View.GONE);
        words.setVisibility(modes.getCheckedRadioButtonId() == R.id.gag_specific ? View.VISIBLE : View.GONE);
    }

    private void save() {
        int id = modes.getCheckedRadioButtonId();
        GagRules.Mode mode = id == R.id.gag_every_n ? GagRules.Mode.INTERVAL
                : id == R.id.gag_specific ? GagRules.Mode.MATCH : GagRules.Mode.ALL;
        int count = 3;
        if (mode == GagRules.Mode.INTERVAL) {
            try {
                count = Integer.parseInt(interval.getText().toString().trim());
            } catch (NumberFormatException invalid) {
                count = 0;
            }
            if (count < 1 || count > 999) {
                InputFocusPolicy.showError(interval, getString(R.string.gag_interval_error));
                return;
            }
        }
        String sourceWords = words.getText().toString();
        String replacementWords = replacements.getText().toString();
        if (enabled.isChecked() && mode == GagRules.Mode.MATCH && GagSettings.items(sourceWords).isEmpty()) {
            InputFocusPolicy.showError(words, getString(R.string.gag_words_error));
            return;
        }
        if (enabled.isChecked() && GagSettings.items(replacementWords).isEmpty()) {
            InputFocusPolicy.showError(replacements, getString(R.string.gag_replacements_error));
            return;
        }
        new GagSettings(enabled.isChecked(), mode, count, sourceWords, replacementWords).save(this);
        ((TextView) findViewById(R.id.gag_save_status)).setText(R.string.gag_saved);
    }
}
