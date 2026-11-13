package com.subhub.app.atmosphere;

import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import com.subhub.app.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** The user selects this keyboard through Android. Only its own taps can enter a Gag word. */
public final class GagKeyboardService extends InputMethodService {
    private static final int MAX_WORD_LENGTH = 128;
    private static final String[] LETTER_ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    private static final String[] SYMBOL_ROWS = {"1234567890", "@#$%&-+()", "*=\"':;!?"};

    private final StringBuilder word = new StringBuilder();
    private final List<Button> letters = new ArrayList<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged =
            (prefs, key) -> reloadRules();
    private GagRules rules;
    private boolean shifted, symbols, literalWord;
    private LinearLayout keyboard;
    private Button shiftKey;

    @Override public void onCreate() {
        super.onCreate();
        GagSettings.preferences(this).registerOnSharedPreferenceChangeListener(settingsChanged);
    }

    @Override public void onDestroy() {
        GagSettings.preferences(this).unregisterOnSharedPreferenceChangeListener(settingsChanged);
        word.setLength(0);
        super.onDestroy();
    }

    @Override public void onStartInput(EditorInfo editor, boolean restarting) {
        super.onStartInput(editor, restarting);
        word.setLength(0);
        literalWord = false;
        shifted = false;
        int inputClass = editor == null ? InputType.TYPE_NULL : editor.inputType & InputType.TYPE_MASK_CLASS;
        symbols = inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE
                || inputClass == InputType.TYPE_CLASS_DATETIME;
        reloadRules();
    }

    @Override public void onStartInputView(EditorInfo editor, boolean restarting) {
        super.onStartInputView(editor, restarting);
        if (keyboard != null) renderKeys();
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public void onFinishInputView(boolean finishingInput) {
        leaveWordLiteral();
        super.onFinishInputView(finishingInput);
    }

    @Override public void onFinishInput() {
        word.setLength(0);
        rules = null;
        super.onFinishInput();
    }

    @Override public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd,
            int composingStart, int composingEnd) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, composingStart, composingEnd);
        if (word.length() > 0 && (composingStart < 0 || newStart != newEnd || newEnd != composingEnd)) {
            leaveWordLiteral();
            literalWord = true;
        }
    }

    private void reloadRules() {
        leaveWordLiteral();
        GagSettings settings = GagSettings.load(this);
        rules = settings.enabled() && GagEditorPolicy.allowsReplacement(getCurrentInputEditorInfo(), getPackageName())
                ? settings.rules() : null;
    }

    private void leaveWordLiteral() {
        if (word.length() == 0) return;
        word.setLength(0);
        InputConnection input = getCurrentInputConnection();
        if (input != null) input.finishComposingText();
    }

    private void type(String text) {
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        int character = text.codePointAt(0);
        boolean inWord = GagRules.wordCharacter(character)
                || (word.length() > 0 || literalWord) && GagRules.apostrophe(character);
        if (rules != null && inWord && !literalWord) {
            if (word.length() + text.length() <= MAX_WORD_LENGTH) {
                word.append(text);
                if (!input.setComposingText(word.toString(), 1)) {
                    leaveWordLiteral();
                    literalWord = true;
                    input.commitText(text, 1);
                }
            } else {
                leaveWordLiteral();
                literalWord = true;
                input.commitText(text, 1);
            }
        } else {
            completeWord(input, text);
            if (!inWord) literalWord = false;
        }
        if (shifted && !symbols && Character.isLetter(character)) {
            shifted = false;
            renderCase();
        }
    }

    private void completeWord(InputConnection input, String suffix) {
        if (word.length() == 0) {
            if (!suffix.isEmpty()) input.commitText(suffix, 1);
            return;
        }
        String completed = word.toString();
        word.setLength(0);
        if (rules != null) completed = rules.complete(completed, bound -> ThreadLocalRandom.current().nextInt(bound));
        input.commitText(completed + suffix, 1);
    }

    private void backspace() {
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        if (word.length() > 0) {
            int length = word.length();
            word.delete(word.offsetByCodePoints(length, -1), length);
            boolean accepted = word.length() == 0 ? input.commitText("", 1) : input.setComposingText(word.toString(), 1);
            if (!accepted) {
                word.setLength(0);
                input.finishComposingText();
                literalWord = true;
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            }
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        }
    }

    private void enter() {
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        completeWord(input, "");
        literalWord = false;
        if (!sendDefaultEditorAction(true)) input.commitText("\n", 1);
    }

    @Override public View onCreateInputView() {
        Context theme = new ContextThemeWrapper(this, android.R.style.Theme_Material_Light);
        if ((getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            theme = new ContextThemeWrapper(this, android.R.style.Theme_Material);
        }
        keyboard = new LinearLayout(theme);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        keyboard.setPadding(dp(2), dp(4), dp(2), dp(4));
        android.util.TypedValue surface = new android.util.TypedValue();
        if (theme.getTheme().resolveAttribute(android.R.attr.colorBackground, surface, true)) {
            if (surface.resourceId != 0) keyboard.setBackgroundResource(surface.resourceId);
            else keyboard.setBackgroundColor(surface.data);
        }
        renderKeys();
        return keyboard;
    }

    private void renderKeys() {
        keyboard.removeAllViews();
        letters.clear();
        shiftKey = null;
        String[] rows = symbols ? SYMBOL_ROWS : LETTER_ROWS;
        for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
            LinearLayout row = row();
            if (rowIndex == 2 && !symbols) {
                shiftKey = key(row, "⇧", 1.4f, () -> {
                    shifted = !shifted;
                    renderCase();
                });
                shiftKey.setContentDescription(getString(R.string.gag_keyboard_shift));
            }
            for (char character : rows[rowIndex].toCharArray()) {
                String label = Character.toString(character);
                Button key = key(row, label, 1, () -> type(shifted && !symbols
                        ? label.toUpperCase(Locale.ROOT) : label));
                if (!symbols) letters.add(key);
            }
            if (rowIndex == 2) {
                Button delete = key(row, "⌫", 1.4f, this::backspace);
                delete.setContentDescription(getString(R.string.gag_keyboard_delete));
            }
        }
        LinearLayout bottom = row();
        key(bottom, symbols ? "ABC" : "?123", 1.4f, () -> {
            symbols = !symbols;
            shifted = false;
            renderKeys();
        });
        Button picker = key(bottom, "⌨", 1, this::chooseKeyboard);
        picker.setContentDescription(getString(R.string.gag_choose_keyboard));
        key(bottom, ",", .8f, () -> type(","));
        key(bottom, getString(R.string.gag_keyboard_space), 3.4f, () -> type(" "));
        key(bottom, ".", .8f, () -> type("."));
        Button enter = key(bottom, "↵", 1.3f, this::enter);
        enter.setContentDescription(getString(R.string.gag_keyboard_enter));
        renderCase();
    }

    private void renderCase() {
        for (Button letter : letters) {
            String label = letter.getText().toString();
            letter.setText(shifted ? label.toUpperCase(Locale.ROOT) : label.toLowerCase(Locale.ROOT));
        }
        if (shiftKey != null) shiftKey.setSelected(shifted);
    }

    private void chooseKeyboard() {
        leaveWordLiteral();
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) manager.showInputMethodPicker();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(keyboard.getContext());
        keyboard.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private Button key(LinearLayout row, String label, float weight, Runnable action) {
        Button button = new Button(row.getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, dp(48), weight));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
