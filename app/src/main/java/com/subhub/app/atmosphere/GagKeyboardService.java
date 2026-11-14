package com.subhub.app.atmosphere;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
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
    private static final String[] ALTERNATE_ROWS = {"1234567890", "@#$–&-+()", "*\"':;!?"};
    private static final String[] SYMBOL_ROWS = {"1234567890", "@#$_&-+()/", "*\"':;!?"};
    private static final String[] MORE_SYMBOL_ROWS = {"~`|•√π÷×§Δ", "£¢€¥^°={}\\", "%©®™✓[]"};
    private static final int LETTER_SURFACE = 0xb36a6a6a;
    private static final int MODIFIER_SURFACE = 0x88181818;
    private static final int RETURN_SURFACE = 0xff5e97f6;
    private static final int LABEL_COLOR = 0xfff6f6f6;

    private final StringBuilder word = new StringBuilder();
    private final StringBuilder sentenceOpening = new StringBuilder();
    private boolean collectOpening;
    private int openingWords;
    private GagPersonalDictionary personalDictionary;
    private final List<LetterKey> letters = new ArrayList<>();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService spelling = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Runnable requestSuggestions = this::requestSuggestions;
    private java.util.concurrent.Future<?> suggestionTask;
    private GagLexicon lexicon;
    private boolean loadingLexicon;
    private long suggestionGeneration;
    private boolean awaitingDeleteSelection;
    private int committedCursor = -1;
    private char pendingPunctuation;
    private int punctuationCursor = -1;
    private LinearLayout suggestionStrip;
    private final List<TextView> suggestionViews = new ArrayList<>(3);
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
    private boolean shifted, capsLock, literalWord;
    private Page page = Page.LETTERS;
    private int editorInputType;
    private long lastShiftTap;
    private LinearLayout keyboard;
    private Bitmap backdrop;
    private Page renderedPage;
    private int renderedInputType;
    private boolean renderedShifted, renderedCapsLock;
    private int renderedEditorAction = -1;
    private ImageButton shiftKey;
    private GagKeyPress repeatingDelete;
    private TextView status;
    private int statusLabel;
    private boolean subWorkspace;
    private boolean enabled;
    private boolean editorAllowed;

    private record LetterKey(Button button, String letter, String alternate) {}
    private enum Page { LETTERS, SYMBOLS, MORE_SYMBOLS, NUMBERS }

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
        clearSuggestions();
        spelling.shutdownNow();
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
        clearSuggestions();
        literalWord = false;
        clearPendingSpace();
        shifted = false;
        capsLock = false;
        lastShiftTap = 0;
        editorInputType = editor == null ? InputType.TYPE_NULL : editor.inputType;
        page = numberEditor() ? Page.NUMBERS : Page.LETTERS;
        editorAllowed = GagEditorPolicy.allowsReplacement(editor, getPackageName());
        if (editorAllowed) loadLexicon();
        resetOpening(false);
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
        if (keyboard != null) renderKeysIfNeeded(editor);
        if (!restarting && editorAllowed) {
            resetOpening(isEmptyEditor(getCurrentInputConnection()));
            scheduleSuggestions();
        }
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public void onFinishInputView(boolean finishingInput) {
        clearPendingSpace();
        resetOpening(false);
        if (repeatingDelete != null) repeatingDelete.cancelRepeat();
        leaveWordLiteral();
        super.onFinishInputView(finishingInput);
    }

    @Override public void onFinishInput() {
        if (repeatingDelete != null) repeatingDelete.cancelRepeat();
        word.setLength(0);
        clearPendingSpace();
        resetOpening(false);
        clearSuggestions();
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
            resetOpening(false);
        }
        if (newStart == oldStart && newEnd == oldEnd) return;
        if (pendingPunctuation != 0) {
            if (newStart != newEnd || (punctuationCursor != -1 && newEnd != punctuationCursor))
                clearPendingSpace();
            else punctuationCursor = newEnd;
        }
        if (newStart != newEnd || newEnd < oldEnd) resetOpening(false);
        if (awaitingDeleteSelection) {
            if (newStart == 0 && newEnd == 0 && isEmptyEditor(getCurrentInputConnection()))
                resetOpening(true);
            scheduleSuggestions(newStart == newEnd && composingStart < 0 ? newEnd : -1);
        } else if (committedCursor != -1 && (newStart != committedCursor || newEnd != committedCursor)) {
            clearSuggestions();
        }
    }

    private void refreshWorkspace() {
        RemoteFeature feature = backend;
        if (feature == null) return;
        subWorkspace = GagActivity.canSetUp(this);
        long revision = feature.gagRevision();
        boolean allowed = subWorkspace && GagParticipation.allowed(participation, keyboardAlreadyEnabled);
        if (!allowed || !editorAllowed || rulesRevision != revision) {
            if (rules != null && word.length() != 0) {
                leaveWordLiteral();
                literalWord = true;
                resetOpening(false);
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
                        if (enabled && word.length() != 0) {
                            leaveWordLiteral();
                            literalWord = true;
                            resetOpening(false);
                        }
                        rules = enabled ? settings.rules() : null;
                    } catch (org.json.JSONException | IllegalArgumentException ignored) { }
                }
                updateStatus();
            }));
        }
        updateStatus();
    }

    private void updateStatus() {
        int label = !subWorkspace || !editorAllowed
                ? R.string.gag_keyboard_excluded
                : enabled ? R.string.gag_keyboard_active : R.string.gag_keyboard_inactive;
        if (status != null && statusLabel != label) {
            statusLabel = label;
            status.setText(label);
        }
    }

    private void leaveWordLiteral() {
        clearSuggestions();
        if (word.length() == 0) return;
        word.setLength(0);
        InputConnection input = getCurrentInputConnection();
        if (input != null) input.finishComposingText();
    }

    private void type(String text) {
        refreshReplacementAuthority();
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        int character = text.codePointAt(0);
        insertPendingSpace(input, character);
        boolean inWord = (page == Page.LETTERS && GagRules.wordCharacter(character))
                || ((word.length() > 0 || literalWord) && GagRules.apostrophe(character));
        if (editorAllowed && inWord && !literalWord) {
            if (word.length() + text.length() <= MAX_WORD_LENGTH) {
                word.append(text);
                if (!input.setComposingText(word.toString(), 1)) {
                    leaveWordLiteral();
                    literalWord = true;
                    resetOpening(false);
                    input.commitText(text, 1);
                } else scheduleSuggestions();
            } else {
                leaveWordLiteral();
                literalWord = true;
                resetOpening(false);
                input.commitText(text, 1);
            }
        } else {
            boolean committed = completeWord(input, text, null);
            if (committed && editorAllowed && (character == '.' || character == ','))
                pendingPunctuation = (char) character;
            if (committed && editorAllowed) {
                if (character == '.' || character == '!' || character == '?' || character == '\n')
                    resetOpening(true);
                else if (character != ' ') resetOpening(false);
            }
            literalWord = inWord;
        }
        if (page == Page.LETTERS && Character.isLetter(character)) lastShiftTap = 0;
        if (shifted && !capsLock && page == Page.LETTERS && Character.isLetter(character)) {
            shifted = false;
            renderCase();
        }
    }

    private void clearPendingSpace() {
        pendingPunctuation = 0;
        punctuationCursor = -1;
    }

    private void insertPendingSpace(InputConnection input, int next) {
        char punctuation = pendingPunctuation;
        clearPendingSpace();
        if (!editorAllowed || punctuation == 0 || !Character.isLetter(next)) return;
        CharSequence selected = input.getSelectedText(0);
        if (selected != null && selected.length() != 0) return;
        if (GagPunctuation.needsSpace(punctuation, next, input.getTextBeforeCursor(2, 0),
                input.getTextAfterCursor(2, 0))) input.commitText(" ", 1);
    }

    private boolean completeWord(InputConnection input, String suffix, String suggestion) {
        clearSuggestions();
        if (word.length() == 0) {
            boolean committed = !suffix.isEmpty() && input.commitText(suffix, 1);
            if (!committed || literalWord) resetOpening(false);
            return committed;
        }
        String original = word.toString();
        String completed = suggestion == null ? original : suggestion;
        word.setLength(0);
        String proposed = completed;
        completed = applyGag(original, proposed);
        if (!input.commitText(completed + suffix, 1)) {
            resetOpening(false);
            return false;
        }
        if (completed.equals(proposed)) rememberWord(completed);
        else resetOpening(false);
        return true;
    }

    private String applyGag(String original, String proposed) {
        return subWorkspace && rules != null
                ? rules.complete(original, proposed, bound -> ThreadLocalRandom.current().nextInt(bound))
                : proposed;
    }

    private void resetOpening(boolean collect) {
        sentenceOpening.setLength(0);
        openingWords = 0;
        collectOpening = collect;
    }

    private void rememberWord(String learned) {
        if (!editorAllowed) return;
        GagPersonalDictionary personal = personalDictionary();
        if (!personal.isLearningEnabled()) {
            resetOpening(false);
            return;
        }
        String opener = null;
        if (collectOpening) {
            if (openingWords > 0) sentenceOpening.append(' ');
            sentenceOpening.append(learned);
            if (++openingWords >= 2) opener = sentenceOpening.toString();
            if (openingWords == 3) resetOpening(false);
        }
        personal.recordToken(learned, opener);
    }

    private synchronized GagPersonalDictionary personalDictionary() {
        if (personalDictionary == null) personalDictionary = new GagPersonalDictionary(this);
        return personalDictionary;
    }

    private void backspace() {
        clearPendingSpace();
        resetOpening(false);
        refreshReplacementAuthority();
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
            scheduleSuggestions();
        } else {
            clearSuggestions();
            awaitingDeleteSelection = canSuggest();
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        }
    }

    private void enter() {
        clearPendingSpace();
        refreshReplacementAuthority();
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        completeWord(input, "", null);
        literalWord = false;
        resetOpening(!sendDefaultEditorAction(true) && input.commitText("\n", 1));
    }

    private void refreshReplacementAuthority() {
        // The literal path cannot replace text. Lifecycle and configuration changes still load
        // rules, and every key that could use them rechecks the current authority.
        RemoteFeature feature = backend;
        if (rules != null || (feature != null && requestedRevision != feature.gagRevision()))
            refreshWorkspace();
    }

    @Override public View onCreateInputView() {
        Context theme = new ContextThemeWrapper(this, android.R.style.Theme_Material);
        LinearLayout surface = new LinearLayout(theme);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setBackgroundColor(Color.BLACK);
        keyboard = new LinearLayout(theme);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        keyboard.setMotionEventSplittingEnabled(true);
        keyboard.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        keyboard.setPadding(dp(2), 0, dp(2), dp(compact() ? 5 : 13));
        if (backdrop == null) backdrop = BitmapFactory.decodeResource(getResources(), R.drawable.bg_gag_keyboard);
        keyboard.setBackground(new KeyboardBackdrop(backdrop));
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
        renderedEditorAction = -1;
        renderKeysIfNeeded(getCurrentInputEditorInfo());
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

    private void renderKeysIfNeeded(EditorInfo editor) {
        int action = editor == null || (editor.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                ? EditorInfo.IME_ACTION_NONE : editor.imeOptions & EditorInfo.IME_MASK_ACTION;
        int keyInputType = page == Page.NUMBERS ? editorInputType : InputType.TYPE_NULL;
        if (renderedPage != page || renderedInputType != keyInputType || renderedEditorAction != action) {
            renderedPage = page;
            renderedInputType = keyInputType;
            renderedEditorAction = action;
            renderKeys(action);
        } else if (renderedShifted != shifted || renderedCapsLock != capsLock) {
            renderCase();
        }
    }

    private void renderKeys(int editorAction) {
        if (repeatingDelete != null) repeatingDelete.cancelRepeat();
        keyboard.removeAllViews();
        letters.clear();
        shiftKey = null;
        renderToolbar();
        if (page == Page.NUMBERS) {
            renderNumberKeys(editorAction);
            return;
        }
        String[] rows = switch (page) {
            case SYMBOLS -> SYMBOL_ROWS;
            case MORE_SYMBOLS -> MORE_SYMBOL_ROWS;
            default -> LETTER_ROWS;
        };
        boolean lettersPage = page == Page.LETTERS;
        for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
            LinearLayout row = row();
            if (lettersPage && rowIndex == 1) spacer(row, .5f);
            if (rowIndex == 2) {
                if (lettersPage) {
                    shiftKey = iconKey(row, 1.5f, MODIFIER_SURFACE, R.drawable.ic_gag_shift,
                            R.string.gag_keyboard_shift, this::shift, false);
                } else if (page == Page.MORE_SYMBOLS) {
                    pageKey(row, "?123", 1.5f, R.string.gag_keyboard_symbols, Page.SYMBOLS);
                } else {
                    pageKey(row, "=\\<", 1.5f, R.string.gag_keyboard_more_symbols, Page.MORE_SYMBOLS);
                }
            }
            for (int index = 0; index < rows[rowIndex].length(); index++) {
                String label = rows[rowIndex].substring(index, index + 1);
                if (lettersPage) letter(row, label, ALTERNATE_ROWS[rowIndex].substring(index, index + 1));
                else key(row, label, 1, LETTER_SURFACE, 26, () -> type(label));
            }
            if (rowIndex == 2) renderDelete(row, 1.5f);
            if (lettersPage && rowIndex == 1) spacer(row, .5f);
        }
        LinearLayout bottom = row();
        if (lettersPage) pageKey(bottom, "?123", 1.5f, R.string.gag_keyboard_symbols, Page.SYMBOLS);
        else pageKey(bottom, "ABC", 1.5f, R.string.gag_keyboard_letters, Page.LETTERS);
        String left = page == Page.MORE_SYMBOLS ? "<" : ",";
        String right = page == Page.MORE_SYMBOLS ? ">" : ".";
        key(bottom, left, 1f, MODIFIER_SURFACE, 24, () -> type(left));
        if (!lettersPage) pageKey(bottom, "1 2\n3 4", 1f, R.string.gag_keyboard_numbers, Page.NUMBERS);
        renderSpace(bottom, lettersPage ? 5f : 4f);
        key(bottom, right, 1f, MODIFIER_SURFACE, 24, () -> type(right));
        renderEnter(bottom, editorAction, 1.5f);
        renderCase();
    }

    private boolean numberEditor() {
        int inputClass = editorInputType & InputType.TYPE_MASK_CLASS;
        return inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE
                || inputClass == InputType.TYPE_CLASS_DATETIME;
    }

    private void renderNumberKeys(int editorAction) {
        int inputClass = editorInputType & InputType.TYPE_MASK_CLASS;
        boolean phone = inputClass == InputType.TYPE_CLASS_PHONE;
        boolean dateTime = inputClass == InputType.TYPE_CLASS_DATETIME;
        boolean number = inputClass == InputType.TYPE_CLASS_NUMBER;
        LinearLayout first = row();
        renderDigits(first, "123");
        renderNumericSymbol(first, phone ? "+" : dateTime ? "/" :
                !number || (editorInputType & InputType.TYPE_NUMBER_FLAG_SIGNED) != 0 ? "-" : "");
        LinearLayout second = row();
        renderDigits(second, "456");
        renderNumericSymbol(second, phone ? "*" : dateTime ? ":" :
                !number || (editorInputType & InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0 ? "." : "");
        LinearLayout third = row();
        renderDigits(third, "789");
        renderDelete(third, 1f);
        LinearLayout bottom = row();
        if (numberEditor()) renderNumericSymbol(bottom, phone ? "#" : "");
        else pageKey(bottom, "ABC", 1f, R.string.gag_keyboard_letters, Page.LETTERS);
        renderDigits(bottom, "0");
        if (numberEditor()) renderNumericSymbol(bottom, dateTime ? "-" : "");
        else renderSpace(bottom, 1f);
        renderEnter(bottom, editorAction, 1f);
    }

    private void renderDigits(LinearLayout row, String digits) {
        for (int i = 0; i < digits.length(); i++) {
            String digit = digits.substring(i, i + 1);
            key(row, digit, 1, LETTER_SURFACE, 26, () -> type(digit));
        }
    }

    private void renderNumericSymbol(LinearLayout row, String symbol) {
        if (symbol.isEmpty()) spacer(row, 1f);
        else key(row, symbol, 1f, MODIFIER_SURFACE, 24, () -> type(symbol));
    }

    private void pageKey(LinearLayout row, String title, float weight, int description, Page target) {
        Button button = key(row, title, weight, target == Page.NUMBERS ? LETTER_SURFACE : MODIFIER_SURFACE,
                target == Page.NUMBERS && compact() ? 11 : 17, () -> {
                    clearSuggestions();
                    page = target;
                    renderKeysIfNeeded(getCurrentInputEditorInfo());
                });
        if (target == Page.NUMBERS) button.setSingleLine(false);
        button.setContentDescription(getString(description));
    }

    private void renderDelete(LinearLayout row, float weight) {
        iconKey(row, weight, MODIFIER_SURFACE, R.drawable.ic_gag_backspace,
                R.string.gag_keyboard_delete, this::backspace, true);
    }

    private void renderSpace(LinearLayout row, float weight) {
        Button space = key(row, getString(R.string.gag_keyboard_language), weight, LETTER_SURFACE, 13, () -> type(" "));
        space.setContentDescription(getString(R.string.gag_keyboard_space));
        space.setOnLongClickListener(view -> { chooseKeyboard(); return true; });
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

    private void renderEnter(LinearLayout row, int action, float weight) {
        switch (action) {
            case EditorInfo.IME_ACTION_SEARCH -> iconKey(row, weight, RETURN_SURFACE,
                    R.drawable.ic_gag_search, R.string.gag_keyboard_search, this::enter, false);
            case EditorInfo.IME_ACTION_DONE -> iconKey(row, weight, RETURN_SURFACE,
                    R.drawable.ic_gag_done, R.string.gag_keyboard_done, this::enter, false);
            case EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEND,
                    EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS -> {
                int label = switch (action) {
                    case EditorInfo.IME_ACTION_GO -> R.string.gag_keyboard_go;
                    case EditorInfo.IME_ACTION_SEND -> R.string.gag_keyboard_send;
                    case EditorInfo.IME_ACTION_NEXT -> R.string.gag_keyboard_next;
                    default -> R.string.gag_keyboard_previous;
                };
                key(row, getString(label), weight, RETURN_SURFACE, 11, this::enter);
            }
            default -> iconKey(row, weight, RETURN_SURFACE,
                    R.drawable.ic_gag_return, R.string.gag_keyboard_enter, this::enter, false);
        }
    }

    private void renderToolbar() {
        LinearLayout tools = row();
        tools.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout toolbar = new FrameLayout(tools.getContext());
        tools.addView(toolbar, new LinearLayout.LayoutParams(0, dp(compact() ? 25 : 43), 1));
        status = new TextView(tools.getContext());
        statusLabel = 0;
        status.setTextColor(0xffc9c9c9);
        status.setTextSize(12);
        status.setPaddingRelative(dp(12), 0, 0, 0);
        toolbar.addView(status, new FrameLayout.LayoutParams(-1, -1));
        status.setGravity(Gravity.CENTER_VERTICAL);
        suggestionStrip = new LinearLayout(tools.getContext());
        suggestionStrip.setVisibility(View.GONE);
        toolbar.addView(suggestionStrip, new FrameLayout.LayoutParams(-1, -1));
        suggestionViews.clear();
        for (int index = 0; index < 3; index++) {
            TextView choice = new TextView(tools.getContext());
            choice.setTextColor(LABEL_COLOR);
            choice.setTextSize(16);
            choice.setGravity(Gravity.CENTER);
            choice.setSingleLine();
            choice.setEllipsize(android.text.TextUtils.TruncateAt.END);
            choice.setPadding(dp(6), 0, dp(6), 0);
            choice.setBackground(keyFace(MODIFIER_SURFACE, 7));
            choice.setOnTouchListener(new View.OnTouchListener() {
                private long pressedGeneration;

                @Override public boolean onTouch(View view, MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        pressedGeneration = suggestionGeneration;
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && pressedGeneration != suggestionGeneration) {
                        view.setPressed(false);
                        return true;
                    }
                    return false;
                }
            });
            suggestionStrip.addView(choice, new LinearLayout.LayoutParams(0, -1, 1));
            suggestionViews.add(choice);
        }
        updateStatus();
        scheduleSuggestions();
    }

    private void loadLexicon() {
        if (lexicon != null || loadingLexicon) return;
        loadingLexicon = true;
        spelling.execute(() -> {
            // Android's asset merger unwraps .gz and stores the resulting TSV in the APK.
            try (java.io.InputStream source = getAssets().open("gag_words/words.tsv")) {
                GagLexicon loaded = GagLexicon.read(source);
                main.post(() -> {
                    if (backend == null) return;
                    lexicon = loaded;
                    if (editorAllowed && isInputViewShown()) scheduleSuggestions();
                });
            } catch (java.io.IOException | NumberFormatException invalid) {
                // Invalid optional dictionary data must not interfere with literal typing.
            }
        });
    }

    private boolean canSuggest() {
        return editorAllowed && suggestionStrip != null && page == Page.LETTERS;
    }

    private void scheduleSuggestions() { scheduleSuggestions(-1); }

    private void scheduleSuggestions(int cursor) {
        clearSuggestions();
        if (!canSuggest()) return;
        committedCursor = cursor;
        main.postDelayed(requestSuggestions, 45);
    }

    private void clearSuggestions() {
        awaitingDeleteSelection = false;
        committedCursor = -1;
        suggestionGeneration++;
        main.removeCallbacks(requestSuggestions);
        if (suggestionTask != null) {
            suggestionTask.cancel(false);
            suggestionTask = null;
        }
        if (suggestionStrip != null && suggestionStrip.getVisibility() != View.GONE) {
            for (TextView view : suggestionViews) {
                view.setOnClickListener(null);
                view.setText("");
            }
            suggestionStrip.setVisibility(View.GONE);
            status.setVisibility(View.VISIBLE);
        }
    }

    private void requestSuggestions() {
        if (!canSuggest()) return;
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        int cursor = committedCursor;
        String token = cursor < 0 ? word.toString() : readCommittedPrefix(input, cursor);
        if (token.isEmpty() && !isEmptyEditor(input)) return;
        long generation = suggestionGeneration;
        GagLexicon source = lexicon;
        suggestionTask = spelling.submit(() -> {
            List<String> generic = source == null ? List.of() : source.suggestions(token);
            if (source != null && source.isKnownWord(token)) {
                List<String> withLiteral = new ArrayList<>(generic.size() + 1);
                withLiteral.add(token);
                withLiteral.addAll(generic);
                generic = withLiteral;
            }
            List<String> candidates = personalDictionary().suggestions(token, generic, 3);
            main.post(() -> {
                if (generation != suggestionGeneration || input != getCurrentInputConnection()
                        || !editorAllowed || candidates.isEmpty()
                        || suggestionStrip == null) return;
                if (token.isEmpty() && !isEmptyEditor(input)) return;
                for (int index = 0; index < suggestionViews.size(); index++) {
                    TextView view = suggestionViews.get(index);
                    int rank = index == 0 ? 1 : index == 1 ? 0 : 2;
                    boolean available = rank < candidates.size();
                    view.setVisibility(available ? View.VISIBLE : View.INVISIBLE);
                    if (!available) continue;
                    String candidate = candidates.get(rank);
                    view.setText(candidate);
                    view.setOnClickListener(key -> acceptSuggestion(generation, input, cursor, token, candidate));
                }
                status.setVisibility(View.INVISIBLE);
                suggestionStrip.setVisibility(View.VISIBLE);
            });
        });
    }

    private static boolean isEmptyEditor(InputConnection input) {
        if (input == null) return false;
        CharSequence selection = input.getSelectedText(0);
        if (selection != null && selection.length() != 0) return false;
        CharSequence before = input.getTextBeforeCursor(1, 0);
        CharSequence after = input.getTextAfterCursor(1, 0);
        return before != null && before.length() == 0 && after != null && after.length() == 0;
    }

    private static String readCommittedPrefix(InputConnection input, int cursor) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.view.inputmethod.SurroundingText text =
                    input.getSurroundingText(GagLexicon.cursorPrefixLimit(), 2, 0);
            if (text != null && text.getOffset() >= 0) {
                int selection = text.getSelectionStart();
                if (selection != text.getSelectionEnd() || text.getOffset() + selection != cursor)
                    return "";
                CharSequence buffer = text.getText();
                return GagLexicon.cursorPrefix(buffer.subSequence(0, selection),
                        buffer.subSequence(selection, buffer.length()));
            }
        }
        CharSequence selection = input.getSelectedText(0);
        if (selection != null && selection.length() != 0) return "";
        return GagLexicon.cursorPrefix(input.getTextBeforeCursor(GagLexicon.cursorPrefixLimit(), 0),
                input.getTextAfterCursor(2, 0));
    }

    private void acceptSuggestion(long generation, InputConnection input, int cursor,
            String token, String candidate) {
        refreshReplacementAuthority();
        if (generation != suggestionGeneration || !canSuggest()
                || input != getCurrentInputConnection()) return;
        clearPendingSpace();
        if (token.isEmpty()) {
            clearSuggestions();
            if (!isEmptyEditor(input)) return;
            StringBuilder completed = new StringBuilder();
            for (String chunk : candidate.split(" "))
                completed.append(applyGag(chunk, chunk)).append(' ');
            input.commitText(completed.toString(), 1);
            resetOpening(false);
        } else if (cursor < 0) {
            if (word.length() == 0) return;
            completeWord(input, " ", candidate);
        } else {
            clearSuggestions();
            if (cursor < token.length() || !token.equals(readCommittedPrefix(input, cursor))) return;
            input.beginBatchEdit();
            try {
                if (!input.setComposingRegion(cursor - token.length(), cursor)) return;
                String completed = applyGag(token, candidate);
                if (!input.commitText(completed + " ", 1)) input.finishComposingText();
                else if (completed.equals(candidate)) rememberWord(candidate);
            } finally {
                input.endBatchEdit();
            }
        }
        literalWord = false;
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
        GagKeyPress press = new GagKeyPress(main, repeat ? action : null);
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
        renderedShifted = shifted;
        renderedCapsLock = capsLock;
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
        row.setMotionEventSplittingEnabled(true);
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
        button.setOnTouchListener(new GagKeyPress(main, null));
        return button;
    }

    private GradientDrawable keyBackground(int surface, int radius) {
        GradientDrawable face = new GradientDrawable();
        face.setColor(surface);
        face.setCornerRadius(dp(radius));
        return face;
    }

    private InsetDrawable keyFace(int surface, int radius) {
        int vertical = dp(compact() ? 2 : 6);
        StateListDrawable states = new StateListDrawable();
        int pressed = androidx.core.graphics.ColorUtils.compositeColors(0x35ffffff, surface);
        states.addState(new int[]{android.R.attr.state_pressed}, keyBackground(pressed, radius));
        states.addState(new int[0], keyBackground(surface, radius));
        return new InsetDrawable(states, dp(3), vertical, dp(3), vertical);
    }

    private void addKeySlot(LinearLayout row, View key, float weight) {
        row.addView(key, new LinearLayout.LayoutParams(0, dp(compact() ? 39 : 64.3f), weight));
    }

    private boolean compact() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Keep the supplied four-repeat artwork at the same scale in both axes. */
    private static final class KeyboardBackdrop extends Drawable {
        // Match the reference's dim artwork while retaining contrast with the translucent key faces.
        private static final int ARTWORK_ALPHA = 74;
        private static final float VERTICAL_PHASE = 0.042f;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final BitmapShader shader;
        private final int imageWidth;
        private final Matrix transform = new Matrix();

        KeyboardBackdrop(Bitmap bitmap) {
            imageWidth = bitmap.getWidth();
            shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.REPEAT);
            paint.setShader(shader);
            paint.setAlpha(ARTWORK_ALPHA);
        }

        @Override protected void onBoundsChange(Rect bounds) {
            float scale = (float) bounds.width() / imageWidth;
            transform.setScale(scale, scale);
            transform.postTranslate(bounds.left, bounds.top - bounds.width() * VERTICAL_PHASE);
            shader.setLocalMatrix(transform);
        }

        @Override public void draw(Canvas canvas) { canvas.drawRect(getBounds(), paint); }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(Math.round(alpha * ARTWORK_ALPHA / 255f));
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
