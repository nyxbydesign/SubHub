package com.subhub.app.atmosphere;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
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
    private static final int LETTER_SURFACE = 0xb36a6a6a;
    private static final int MODIFIER_SURFACE = 0x88181818;
    private static final int RETURN_SURFACE = 0xff5e97f6;
    private static final int LABEL_COLOR = 0xfff6f6f6;
    private static final int REPEAT_MILLIS = 55;

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
    private boolean shifted, capsLock, symbols, literalWord;
    private long lastShiftTap;
    private LinearLayout keyboard;
    private ImageButton shiftKey;
    private KeyPress repeatingDelete;
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
        if (repeatingDelete != null) repeatingDelete.cancelRepeat();
        keyboardAlreadyEnabled = GagParticipation.keyboardEnabled(this);
        word.setLength(0);
        literalWord = false;
        shifted = false;
        capsLock = false;
        lastShiftTap = 0;
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
        if (repeatingDelete != null) repeatingDelete.cancelRepeat();
        leaveWordLiteral();
        super.onFinishInputView(finishingInput);
    }

    @Override public void onFinishInput() {
        if (repeatingDelete != null) repeatingDelete.cancelRepeat();
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
        if (!symbols && Character.isLetter(character)) lastShiftTap = 0;
        if (shifted && !capsLock && !symbols && Character.isLetter(character)) {
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
        LinearLayout surface = new LinearLayout(theme);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setBackgroundColor(Color.BLACK);
        keyboard = new LinearLayout(theme);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        keyboard.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        keyboard.setPadding(dp(2), 0, dp(2), dp(compact() ? 5 : 13));
        keyboard.setBackgroundResource(R.drawable.bg_gag_keyboard);
        surface.addView(keyboard, new LinearLayout.LayoutParams(-1, -2));
        surface.setOnApplyWindowInsetsListener((view, insets) -> {
            view.post(() -> reserveNavigationBar(view, insets));
            return insets;
        });
        surface.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            WindowInsets insets = view.getRootWindowInsets();
            if (insets != null) reserveNavigationBar(view, insets);
        });
        refreshWorkspace();
        renderKeys();
        return surface;
    }

    private void reserveNavigationBar(View surface, WindowInsets insets) {
        if (android.os.Build.VERSION.SDK_INT < 30 || !surface.isLaidOut()) return;
        WindowManager manager = getSystemService(WindowManager.class);
        if (manager == null) return;
        android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars() | WindowInsets.Type.captionBar());
        android.graphics.Rect display = manager.getMaximumWindowMetrics().getBounds();
        int[] location = new int[2];
        surface.getLocationOnScreen(location);
        int left = Math.max(0, display.left + bars.left - location[0]);
        int right = Math.max(0, location[0] + surface.getWidth() - (display.right - bars.right));
        // Android can place the IME's navigation controls above a separate system navigation area.
        View decor = getWindow().getWindow().getDecorView();
        int[] decorLocation = new int[2];
        decor.getLocationOnScreen(decorLocation);
        int decorBottom = Math.min(display.bottom, decorLocation[1] + decor.getHeight());
        int bottom = Math.max(0, location[1] + surface.getHeight() - (decorBottom - bars.bottom));
        if (left != surface.getPaddingLeft() || right != surface.getPaddingRight()
                || bottom != surface.getPaddingBottom()) surface.setPadding(left, 0, right, bottom);
    }

    private void renderKeys() {
        if (repeatingDelete != null) repeatingDelete.cancelRepeat();
        keyboard.removeAllViews();
        letters.clear();
        shiftKey = null;
        renderToolbar();
        String[] rows = symbols ? SYMBOL_ROWS : LETTER_ROWS;
        for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
            LinearLayout row = row();
            if (rowIndex == 1) spacer(row, .5f);
            if (rowIndex == 2 && !symbols) {
                shiftKey = iconKey(row, 1.5f, MODIFIER_SURFACE, R.drawable.ic_gag_shift,
                        R.string.gag_keyboard_shift, this::shift, false);
            }
            if (rowIndex == 2 && symbols) {
                key(row, "%", 1.5f, MODIFIER_SURFACE, 21, () -> type("%"));
            }
            for (int index = 0; index < rows[rowIndex].length(); index++) {
                String label = rows[rowIndex].substring(index, index + 1);
                if (symbols) key(row, label, 1, LETTER_SURFACE, 26, () -> type(label));
                else letter(row, label, SYMBOL_ROWS[rowIndex].substring(index, index + 1));
            }
            if (rowIndex == 2) {
                iconKey(row, 1.5f, MODIFIER_SURFACE, R.drawable.ic_gag_backspace,
                        R.string.gag_keyboard_delete, this::backspace, true);
            }
            if (rowIndex == 1) spacer(row, .5f);
        }
        LinearLayout bottom = row();
        key(bottom, symbols ? "ABC" : "?123", 1.5f, MODIFIER_SURFACE, 17, () -> {
            symbols = !symbols;
            renderKeys();
        });
        key(bottom, ",", 1f, MODIFIER_SURFACE, 24, () -> type(","));
        Button space = key(bottom, getString(R.string.gag_keyboard_language), 5f, LETTER_SURFACE, 13, () -> type(" "));
        space.setContentDescription(getString(R.string.gag_keyboard_space));
        space.setOnLongClickListener(view -> { chooseKeyboard(); return true; });
        key(bottom, ".", 1f, MODIFIER_SURFACE, 24, () -> type("."));
        renderEnter(bottom);
        renderCase();
    }

    private void shift() {
        long now = android.os.SystemClock.uptimeMillis();
        if (capsLock) {
            capsLock = false;
            shifted = false;
            lastShiftTap = 0;
        } else if (shifted && lastShiftTap != 0
                && now - lastShiftTap <= ViewConfiguration.getDoubleTapTimeout()) {
            capsLock = true;
            lastShiftTap = 0;
        } else {
            shifted = !shifted;
            lastShiftTap = shifted ? now : 0;
        }
        renderCase();
    }

    private void renderEnter(LinearLayout row) {
        EditorInfo editor = getCurrentInputEditorInfo();
        int action = editor == null || (editor.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                ? EditorInfo.IME_ACTION_NONE : editor.imeOptions & EditorInfo.IME_MASK_ACTION;
        switch (action) {
            case EditorInfo.IME_ACTION_SEARCH -> iconKey(row, 1.5f, RETURN_SURFACE,
                    R.drawable.ic_gag_search, R.string.gag_keyboard_search, this::enter, false);
            case EditorInfo.IME_ACTION_DONE -> iconKey(row, 1.5f, RETURN_SURFACE,
                    R.drawable.ic_gag_done, R.string.gag_keyboard_done, this::enter, false);
            case EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEND,
                    EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS -> {
                int label = switch (action) {
                    case EditorInfo.IME_ACTION_GO -> R.string.gag_keyboard_go;
                    case EditorInfo.IME_ACTION_SEND -> R.string.gag_keyboard_send;
                    case EditorInfo.IME_ACTION_NEXT -> R.string.gag_keyboard_next;
                    default -> R.string.gag_keyboard_previous;
                };
                key(row, getString(label), 1.5f, RETURN_SURFACE, 11, this::enter);
            }
            default -> iconKey(row, 1.5f, RETURN_SURFACE,
                    R.drawable.ic_gag_return, R.string.gag_keyboard_enter, this::enter, false);
        }
    }

    private void renderToolbar() {
        LinearLayout tools = row();
        tools.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(tools.getContext());
        status.setTextColor(0xffc9c9c9);
        status.setTextSize(12);
        status.setPaddingRelative(dp(12), 0, 0, 0);
        tools.addView(status, new LinearLayout.LayoutParams(0, dp(compact() ? 25 : 43), 1));
        status.setGravity(Gravity.CENTER_VERTICAL);
        updateStatus();
    }

    private ImageButton iconKey(LinearLayout row, float weight, int surface, int icon, int label,
            Runnable action, boolean repeat) {
        ImageButton button = new ImageButton(row.getContext());
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(surface == RETURN_SURFACE ? Color.WHITE : 0xffd0d0d0));
        button.setContentDescription(getString(label));
        button.setBackground(keyFace(surface, surface == RETURN_SURFACE ? 28 : 7));
        button.setPadding(0, 0, 0, 0);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setStateListAnimator(null);
        button.setHapticFeedbackEnabled(true);
        button.setOnClickListener(view -> action.run());
        KeyPress press = new KeyPress(repeat ? action : null);
        if (repeat) repeatingDelete = press;
        button.setOnTouchListener(press);
        addKeySlot(row, button, weight);
        return button;
    }

    private void letter(LinearLayout row, String label, String alternate) {
        FrameLayout cap = new FrameLayout(row.getContext());
        addKeySlot(row, cap, 1);
        Button button = makeKey(row.getContext(), label, LETTER_SURFACE, 26, () ->
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
        hintSize.setMargins(0, dp(compact() ? 4 : 7), dp(5), 0);
        cap.addView(hint, hintSize);
        letters.add(new LetterKey(button, label, alternate));
    }

    private void renderCase() {
        for (LetterKey letter : letters) {
            String label = shifted ? letter.letter().toUpperCase(Locale.ROOT) : letter.letter();
            letter.button().setText(label);
            letter.button().setContentDescription(getString(R.string.gag_keyboard_symbol_hint, label, letter.alternate()));
        }
        if (shiftKey != null) {
            shiftKey.setImageResource(capsLock ? R.drawable.ic_gag_caps_lock
                    : shifted ? R.drawable.ic_gag_shift_active : R.drawable.ic_gag_shift);
            shiftKey.setContentDescription(getString(capsLock ? R.string.gag_keyboard_caps_lock
                    : R.string.gag_keyboard_shift));
        }
    }

    private void chooseKeyboard() {
        if (word.length() != 0) literalWord = true;
        leaveWordLiteral();
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) manager.showInputMethodPicker();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(keyboard.getContext());
        row.setBaselineAligned(false);
        keyboard.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void spacer(LinearLayout row, float weight) {
        row.addView(new Space(row.getContext()), new LinearLayout.LayoutParams(0, 0, weight));
    }

    private Button key(LinearLayout row, String label, float weight, int surface, int textSize, Runnable action) {
        Button button = makeKey(row.getContext(), label, surface, textSize, action);
        addKeySlot(row, button, weight);
        return button;
    }

    private Button makeKey(Context context, String label, int surface, int textSize, Runnable action) {
        Button button = new androidx.appcompat.widget.AppCompatButton(context) {
            @Override public boolean performHapticFeedback(int feedbackConstant) {
                return feedbackConstant != HapticFeedbackConstants.LONG_PRESS
                        && super.performHapticFeedback(feedbackConstant);
            }
        };
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
        button.setBackground(keyFace(surface, surface == RETURN_SURFACE ? 28 : 7));
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(view -> action.run());
        button.setHapticFeedbackEnabled(true);
        button.setOnTouchListener(new KeyPress(null));
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

    private InsetDrawable keyFace(int surface, int radius) {
        int vertical = dp(compact() ? 2 : 6);
        return new InsetDrawable(keyBackground(surface, radius), dp(3), vertical, dp(3), vertical);
    }

    private void addKeySlot(LinearLayout row, View key, float weight) {
        row.addView(key, new LinearLayout.LayoutParams(0, dp(compact() ? 39 : 64.3f), weight));
    }

    private boolean compact() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private final class KeyPress implements View.OnTouchListener {
        private final Runnable repeatAction;
        private View pressed;
        private boolean repeated;
        private final Runnable repeat = this::repeat;

        KeyPress(Runnable repeatAction) { this.repeatAction = repeatAction; }

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    repeated = false;
                    if (repeatAction != null) {
                        pressed = view;
                        main.postDelayed(repeat, ViewConfiguration.getLongPressTimeout());
                    }
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (pressed != null && (event.getX() < 0 || event.getY() < 0
                            || event.getX() >= view.getWidth() || event.getY() >= view.getHeight()))
                        cancelRepeat();
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelRepeat();
                    if (repeated) {
                        view.setPressed(false);
                        return true;
                    }
                }
                default -> { }
            }
            return false;
        }

        private void repeat() {
            if (pressed == null || !pressed.isPressed() || !pressed.isAttachedToWindow()) return;
            repeated = true;
            repeatAction.run();
            main.postDelayed(repeat, REPEAT_MILLIS);
        }

        private void cancelRepeat() {
            main.removeCallbacks(repeat);
            pressed = null;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
