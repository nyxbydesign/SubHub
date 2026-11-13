package com.subhub.app.atmosphere;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.remote.RemoteFeature;
import com.subhub.app.remote.RemoteUi;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** The user selects this keyboard through Android. Only its own taps can enter a Gag word. */
public final class GagKeyboardService extends InputMethodService {
    private static final int MAX_WORD_LENGTH = 128;
    private static final String[] LETTER_ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    private static final String[] SYMBOL_ROWS = {"1234567890", "@#$–&-+()", "*\"':;!?"};
    private static final int LETTER_SURFACE = 0x66767676;
    private static final int MODIFIER_SURFACE = 0x55191919;
    private static final int RETURN_SURFACE = 0xff438ff0;
    private static final int LABEL_COLOR = 0xfff6f6f6;

    private final StringBuilder word = new StringBuilder();
    private final List<LetterKey> letters = new ArrayList<>();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private RemoteFeature backend;
    private AutoCloseable configurationObserver;
    private SharedPreferences participation;
    private boolean keyboardAlreadyEnabled;
    private final SharedPreferences.OnSharedPreferenceChangeListener participationChanged =
            (preferences, key) -> refreshWorkspace();
    private long requestedRevision = -1;
    private long rulesRevision = -1;
    private long editorEpoch;
    private GagRules rules;
    private boolean shifted, symbols, literalWord;
    private LinearLayout keyboard;
    private Button shiftKey;
    private TextView status;
    private boolean subWorkspace;
    private boolean enabled;
    private boolean editorAllowed;

    private record LetterKey(Button button, String letter, String alternate) {}

    @Override public void onCreate() {
        super.onCreate();
        backend = RemoteFeature.get(this);
        configurationObserver = RemoteUi.observeConfiguration(this::refreshWorkspace);
        participation = GagParticipation.preferences(this);
        participation.registerOnSharedPreferenceChangeListener(participationChanged);
    }

    @Override public void onDestroy() {
        if (configurationObserver != null) try { configurationObserver.close(); } catch (Exception ignored) { }
        participation.unregisterOnSharedPreferenceChangeListener(participationChanged);
        main.removeCallbacksAndMessages(null);
        backend = null;
        word.setLength(0);
        super.onDestroy();
    }

    @Override public void onStartInput(EditorInfo editor, boolean restarting) {
        super.onStartInput(editor, restarting);
        keyboardAlreadyEnabled = GagParticipation.keyboardEnabled(this);
        word.setLength(0);
        literalWord = false;
        shifted = false;
        int inputClass = editor == null ? InputType.TYPE_NULL : editor.inputType & InputType.TYPE_MASK_CLASS;
        symbols = inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE
                || inputClass == InputType.TYPE_CLASS_DATETIME;
        editorAllowed = GagEditorPolicy.allowsReplacement(editor, getPackageName());
        editorEpoch++;
        requestedRevision = -1;
        rulesRevision = -1;
        rules = null;
        enabled = false;
        refreshWorkspace();
    }

    @Override public void onStartInputView(EditorInfo editor, boolean restarting) {
        super.onStartInputView(editor, restarting);
        refreshWorkspace();
        if (keyboard != null) renderKeys();
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public void onFinishInputView(boolean finishingInput) {
        leaveWordLiteral();
        super.onFinishInputView(finishingInput);
    }

    @Override public void onFinishInput() {
        word.setLength(0);
        editorAllowed = false;
        editorEpoch++;
        requestedRevision = -1;
        rulesRevision = -1;
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

    private void refreshWorkspace() {
        RemoteFeature feature = backend;
        if (feature == null) return;
        subWorkspace = GagActivity.canSetUp(this);
        long revision = feature.gagRevision();
        boolean allowed = subWorkspace && GagParticipation.allowed(participation, keyboardAlreadyEnabled);
        if (!allowed || !editorAllowed || rulesRevision != revision) {
            if (word.length() != 0) {
                leaveWordLiteral();
                literalWord = true;
            }
            enabled = false;
            rules = null;
            rulesRevision = -1;
        }
        if (!allowed || !editorAllowed) {
            if (requestedRevision != -1) editorEpoch++;
            requestedRevision = -1;
        } else if (requestedRevision != revision) {
            requestedRevision = revision;
            long requestEpoch = editorEpoch;
            feature.readAppliedGag().whenComplete((value, failure) -> main.post(() -> {
                if (backend != feature || !editorAllowed || !GagActivity.canSetUp(this)
                        || !GagParticipation.allowed(participation, keyboardAlreadyEnabled)
                        || editorEpoch != requestEpoch || requestedRevision != revision
                        || revision != feature.gagRevision()) return;
                rulesRevision = revision;
                if (failure == null && value != null) {
                    try {
                        GagSettings settings = GagSettings.from(value);
                        enabled = settings.enabled();
                        rules = enabled ? settings.rules() : null;
                    } catch (org.json.JSONException | IllegalArgumentException ignored) { }
                }
                updateStatus();
            }));
        }
        updateStatus();
    }

    private void updateStatus() {
        if (status != null) status.setText(!subWorkspace || !editorAllowed
                ? R.string.gag_keyboard_excluded
                : enabled ? R.string.gag_keyboard_active : R.string.gag_keyboard_inactive);
    }

    private void leaveWordLiteral() {
        if (word.length() == 0) return;
        word.setLength(0);
        InputConnection input = getCurrentInputConnection();
        if (input != null) input.finishComposingText();
    }

    private void type(String text) {
        refreshWorkspace();
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        int character = text.codePointAt(0);
        boolean inWord = GagRules.wordCharacter(character)
                || (word.length() > 0 || literalWord) && GagRules.apostrophe(character);
        if (subWorkspace && rules != null && inWord && !literalWord) {
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
        if (subWorkspace && rules != null)
            completed = rules.complete(completed, bound -> ThreadLocalRandom.current().nextInt(bound));
        input.commitText(completed + suffix, 1);
    }

    private void backspace() {
        refreshWorkspace();
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
        refreshWorkspace();
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        completeWord(input, "");
        literalWord = false;
        if (!sendDefaultEditorAction(true)) input.commitText("\n", 1);
    }

    @Override public View onCreateInputView() {
        Context theme = new ContextThemeWrapper(this, android.R.style.Theme_Material);
        keyboard = new LinearLayout(theme);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        keyboard.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        keyboard.setPadding(dp(2), dp(2), dp(2), dp(6));
        keyboard.setBackgroundResource(R.drawable.bg_gag_keyboard);
        refreshWorkspace();
        renderKeys();
        return keyboard;
    }

    private void renderKeys() {
        keyboard.removeAllViews();
        letters.clear();
        shiftKey = null;
        renderToolbar();
        String[] rows = symbols ? SYMBOL_ROWS : LETTER_ROWS;
        for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
            LinearLayout row = row();
            if (rowIndex == 1) spacer(row, .5f);
            if (rowIndex == 2 && !symbols) {
                shiftKey = key(row, "⇧", 1.5f, MODIFIER_SURFACE, 24, () -> {
                    shifted = !shifted;
                    renderCase();
                });
                shiftKey.setContentDescription(getString(R.string.gag_keyboard_shift));
            }
            if (rowIndex == 2 && symbols) {
                key(row, "%", 1.5f, MODIFIER_SURFACE, 21, () -> type("%"));
            }
            for (int index = 0; index < rows[rowIndex].length(); index++) {
                String label = rows[rowIndex].substring(index, index + 1);
                if (symbols) key(row, label, 1, LETTER_SURFACE, 23, () -> type(label));
                else letter(row, label, SYMBOL_ROWS[rowIndex].substring(index, index + 1));
            }
            if (rowIndex == 2) {
                Button delete = key(row, "⌫", 1.5f, MODIFIER_SURFACE, 23, this::backspace);
                delete.setContentDescription(getString(R.string.gag_keyboard_delete));
            }
            if (rowIndex == 1) spacer(row, .5f);
        }
        LinearLayout bottom = row();
        key(bottom, symbols ? "ABC" : "?123", 1.4f, MODIFIER_SURFACE, 16, () -> {
            symbols = !symbols;
            shifted = false;
            renderKeys();
        });
        key(bottom, ",", .9f, MODIFIER_SURFACE, 22, () -> type(","));
        Button space = key(bottom, getString(R.string.gag_keyboard_language), 5f, LETTER_SURFACE, 14, () -> type(" "));
        space.setContentDescription(getString(R.string.gag_keyboard_space));
        key(bottom, ".", .9f, MODIFIER_SURFACE, 22, () -> type("."));
        Button enter = key(bottom, "↵", 1.4f, RETURN_SURFACE, 24, this::enter);
        enter.setContentDescription(getString(R.string.gag_keyboard_enter));
        renderCase();
    }

    private void renderToolbar() {
        LinearLayout tools = row();
        tools.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(tools.getContext());
        status.setTextColor(0xffc9c9c9);
        status.setTextSize(13);
        status.setPaddingRelative(dp(12), 0, 0, 0);
        tools.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        toolbarAction(tools, R.drawable.ic_gag_picker, R.string.gag_choose_keyboard, this::chooseKeyboard);
        updateStatus();
    }

    private ImageButton toolbarAction(LinearLayout row, int icon, int label, Runnable action) {
        ImageButton button = new ImageButton(row.getContext());
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(0xffc9c9c9));
        button.setContentDescription(getString(label));
        button.setBackground(keyBackground(Color.TRANSPARENT, 12));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    private void letter(LinearLayout row, String label, String alternate) {
        FrameLayout cap = new FrameLayout(row.getContext());
        row.addView(cap, keySize(1));
        Button button = makeKey(row.getContext(), label, LETTER_SURFACE, 23, () ->
                type(shifted ? label.toUpperCase(Locale.ROOT) : label));
        button.setOnLongClickListener(view -> {
            type(alternate);
            return true;
        });
        cap.addView(button, new FrameLayout.LayoutParams(-1, -1));
        TextView hint = new TextView(row.getContext());
        hint.setText(alternate);
        hint.setTextColor(0xffdadada);
        hint.setTextSize(10);
        hint.setIncludeFontPadding(false);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams hintSize = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        hintSize.setMargins(0, dp(2), dp(3), 0);
        cap.addView(hint, hintSize);
        letters.add(new LetterKey(button, label, alternate));
    }

    private void renderCase() {
        for (LetterKey letter : letters) {
            String label = shifted ? letter.letter().toUpperCase(Locale.ROOT) : letter.letter();
            letter.button().setText(label);
            letter.button().setContentDescription(getString(R.string.gag_keyboard_symbol_hint, label, letter.alternate()));
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

    private void spacer(LinearLayout row, float weight) {
        row.addView(new Space(row.getContext()), new LinearLayout.LayoutParams(0, 0, weight));
    }

    private Button key(LinearLayout row, String label, float weight, int surface, int textSize, Runnable action) {
        Button button = makeKey(row.getContext(), label, surface, textSize, action);
        row.addView(button, keySize(weight));
        return button;
    }

    private Button makeKey(Context context, String label, int surface, int textSize, Runnable action) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setTextColor(LABEL_COLOR);
        button.setTextSize(textSize);
        button.setTypeface(android.graphics.Typeface.DEFAULT);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setIncludeFontPadding(false);
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setBackground(keyBackground(surface, surface == RETURN_SURFACE ? 24 : 8));
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private RippleDrawable keyBackground(int surface, int radius) {
        GradientDrawable face = new GradientDrawable();
        face.setColor(surface);
        face.setCornerRadius(dp(radius));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radius));
        return new RippleDrawable(ColorStateList.valueOf(0x55ffffff), face, mask);
    }

    private LinearLayout.LayoutParams keySize(float weight) {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(landscape ? 36 : 48), weight);
        size.setMargins(dp(2), dp(3), dp(2), dp(3));
        return size;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
