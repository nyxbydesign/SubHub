package com.subhub.app.atmosphere;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/** Shell-only synthetic editor in the instrumentation APK's separate package. */
public final class GagEditorFixtureActivity extends Activity {
    static final String ACTION = "com.subhub.app.test.GAG_EDITOR_FIXTURE";
    static final String EDITOR = "Gag spacing test editor";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!ACTION.equals(getIntent().getAction())) {
            finish();
            return;
        }

        EditText editor = new EditText(this);
        editor.setSingleLine(true);
        editor.setInputType(InputType.TYPE_CLASS_TEXT);
        editor.setContentDescription(EDITOR);
        boolean middle = getIntent().getBooleanExtra("gag_spacing_middle", false);
        editor.setText(middle ? " next" : "");
        editor.setSelection(0);
        setContentView(editor);
        editor.requestFocus();
        editor.post(() -> getSystemService(InputMethodManager.class)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT));
    }
}
