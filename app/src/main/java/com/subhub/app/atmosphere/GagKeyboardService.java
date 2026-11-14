package com.subhub.app.atmosphere;

import android.content.ClipboardManager;
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
    private static final int DELETE_CONFIRMATION_MS = 80;
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
    private GagDeleteSuggestion pendingDelete;
    private final Runnable checkDeleteSuggestions = this::checkDeleteSuggestions;
    private int committedCursor = -1;
    private GagSuggestionSpace suggestionSpace;
    private char pendingPunctuation;
    private int punctuationCursor = -1;
    private LinearLayout suggestionStrip;
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
    private Page pageBeforeEmoji = Page.LETTERS;
    private GagEmojiCatalog emojiCatalog;
    private int editorInputType;
    private long lastShiftTap;
    private LinearLayout keyboard;
    private GagKeyAlternativesPopup keyAlternatives;
    private LinearLayout keyboardHost;
    private LinearLayout clipboardPanel;
    private ClipboardManager clipboardManager;
    private final GagClipboard clipboard = new GagClipboard();
    private final Runnable expireClipboard = this::refreshClipboard;
    private final ClipboardManager.OnPrimaryClipChangedListener clipboardChanged = this::captureClipboard;
    private boolean clipboardVisible;
    private InputConnection clipboardConnection;
    private InputConnection clipboardSessionConnection;
    private android.view.Window clipboardSecuredWindow;
    private long clipboardClearedAt;
    private Bitmap backdrop;
    private Page renderedPage;
    private int renderedInputType;
    private boolean renderedShifted, renderedCapsLock;
    private int renderedEditorAction = -1;
    private ImageButton shiftKey;
    private GagKeyPress repeatingDelete;
    private boolean subWorkspace;
    private boolean editorAllowed;

    private record LetterKey(Button button, String letter, String alternate) {}
    private enum Page { LETTERS, SYMBOLS, MORE_SYMBOLS, NUMBERS, EMOJI }

    @Override public void onCreate() {
        super.onCreate();
        backend = RemoteFeature.get(this);
        configurationObserver = RemoteUi.observeConfiguration(this::refreshWorkspace);
        participation = GagParticipation.preferences(this);
        participation.registerOnSharedPreferenceChangeListener(participationChanged);
    }

    @Override public void onDestroy() {
        dismissAlternatives();
        stopClipboard();
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
        suggestionSpace = null;
        stopClipboard();
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
        boolean allowed = GagEditorPolicy.allowsReplacement(editor, getPackageName());
        if (editorAllowed != allowed) renderedPage = null;
        editorAllowed = allowed;
        if (editorAllowed) loadLexicon();
        resetOpening(false);
        editorEpoch++;
        requestedRevision = -1;
        rulesRevision = -1;
        rules = null;
        refreshWorkspace();
    }

    @Override public void onStartInputView(EditorInfo editor, boolean restarting) {
        super.onStartInputView(editor, restarting);
        clipboardVisible = true;
        if (editorAllowed) startClipboard();
        refreshWorkspace();
        if (keyboard != null) renderKeysIfNeeded(editor);
        if (editorAllowed) {
            if (!restarting) resetOpening(isEmptyEditor(getCurrentInputConnection()));
            scheduleSuggestions();
        }
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public void onFinishInputView(boolean finishingInput) {
        dismissAlternatives();
        suggestionSpace = null;
        stopClipboard();
        if (page == Page.EMOJI) page = pageBeforeEmoji;
        clearPendingSpace();
        resetOpening(false);
        if (repeatingDelete != null) repeatingDelete.cancelRepeat();
        leaveWordLiteral();
        super.onFinishInputView(finishingInput);
    }

    @Override public void onFinishInput() {
        dismissAlternatives();
        suggestionSpace = null;
        stopClipboard();
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
        if (suggestionSpace != null
                && !suggestionSpace.atSelection(getCurrentInputConnection(), newStart, newEnd))
            suggestionSpace = null;
        if (clipboardSessionConnection != null && clipboardSessionConnection != getCurrentInputConnection())
            stopClipboard();
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
        if (pendingDelete != null) {
            InputConnection input = getCurrentInputConnection();
            showDeleteSuggestions(input, pendingDelete.selectionCursor(input, newStart, newEnd, composingStart));
        } else if (committedCursor != -1 && (newStart != committedCursor || newEnd != committedCursor)) {
            clearSuggestions();
        } else if (word.length() == 0 && committedCursor < 0) {
            if (newStart == newEnd && composingStart < 0 && isInputViewShown()) scheduleSuggestions();
            else clearSuggestions();
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
                        boolean enabled = settings.enabled();
                        if (enabled && word.length() != 0) {
                            leaveWordLiteral();
                            literalWord = true;
                            resetOpening(false);
                        }
                        rules = enabled ? settings.rules() : null;
                    } catch (org.json.JSONException | IllegalArgumentException ignored) { }
                }
            }));
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
        GagSuggestionSpace ownedSpace = suggestionSpace;
        suggestionSpace = null;
        if (ownedSpace != null) ownedSpace.removeBefore(input, character);
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
            if (committed && Character.isWhitespace(character)) scheduleSuggestions();
        }
        if (page == Page.LETTERS && Character.isLetter(character)) lastShiftTap = 0;
        if (shifted && !capsLock && page == Page.LETTERS && Character.isLetter(character)) {
            shifted = false;
            renderCase();
        }
        if ((page == Page.SYMBOLS || page == Page.MORE_SYMBOLS)
                && (character == ' ' || GagRules.apostrophe(character))) {
            page = Page.LETTERS;
            renderKeysIfNeeded(getCurrentInputEditorInfo());
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
        boolean committed = suggestion == null ? input.commitText(completed + suffix, 1)
                : commitSuggestion(input, original.length(), completed);
        if (!committed) {
            resetOpening(false);
            return false;
        }
        if (completed.equals(proposed)) rememberWord(completed);
        else resetOpening(false);
        return true;
    }

    private boolean commitSuggestion(InputConnection input, int originalLength, String completed) {
        suggestionSpace = null;
        boolean append = GagSuggestionSpace.needsSpace(input.getTextAfterCursor(2, 0));
        GagDeleteSuggestion.Snapshot before = append ? GagDeleteSuggestion.editorSnapshot(input) : null;
        String inserted = append ? completed + " " : completed;
        if (!input.commitText(inserted, 1)) return false;
        if (append) suggestionSpace = GagSuggestionSpace.capture(input, before, originalLength, inserted);
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
        suggestionSpace = null;
        clearPendingSpace();
        resetOpening(false);
        refreshReplacementAuthority();
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        if (word.length() > 0) {
            int length = word.length();
            word.delete(word.offsetByCodePoints(length, -1), length);
            boolean accepted = word.length() == 0 ? input.commitText("", 1) : input.setComposingText(word.toString(), 1);
            if (accepted) {
                scheduleSuggestions();
                return;
            }
            word.setLength(0);
            input.finishComposingText();
            literalWord = true;
        }
        clearSuggestions();
        if (canSuggest()) pendingDelete = GagDeleteSuggestion.capture(input);
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        if (pendingDelete != null) main.postDelayed(checkDeleteSuggestions, DELETE_CONFIRMATION_MS);
    }

    private void checkDeleteSuggestions() {
        if (pendingDelete == null) return;
        InputConnection input = getCurrentInputConnection();
        showDeleteSuggestions(input, pendingDelete.deletedCursor(input));
    }

    private void showDeleteSuggestions(InputConnection input, int cursor) {
        if (cursor < 0) return;
        if (cursor == 0 && isEmptyEditor(input)) resetOpening(true);
        scheduleSuggestions(cursor);
    }

    private void enter() {
        suggestionSpace = null;
        clearPendingSpace();
        refreshReplacementAuthority();
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        completeWord(input, "", null);
        literalWord = false;
        boolean insertedNewline = !sendDefaultEditorAction(true) && input.commitText("\n", 1);
        resetOpening(insertedNewline);
        if (insertedNewline) scheduleSuggestions();
    }

    private void refreshReplacementAuthority() {
        // The literal path cannot replace text. Lifecycle and configuration changes still load
        // rules, and every key that could use them rechecks the current authority.
        RemoteFeature feature = backend;
        if (rules != null || (feature != null && requestedRevision != feature.gagRevision()))
            refreshWorkspace();
    }

    @Override public View onCreateInputView() {
        dismissAlternatives();
        Context theme = new ContextThemeWrapper(this, android.R.style.Theme_Material);
        LinearLayout surface = new LinearLayout(theme);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setBackgroundColor(Color.BLACK);
        keyboard = new LinearLayout(theme);
        keyAlternatives = new GagKeyAlternativesPopup(keyboard);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        keyboard.setMotionEventSplittingEnabled(true);
        keyboard.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        keyboard.setPadding(dp(2), 0, dp(2), dp(compact() ? 5 : 13));
        if (backdrop == null) backdrop = BitmapFactory.decodeResource(getResources(), R.drawable.bg_gag_keyboard);
        keyboard.setBackground(new KeyboardBackdrop(backdrop));
        keyboardHost = surface;
        clipboardPanel = null;
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
        dismissAlternatives();
        if (repeatingDelete != null) repeatingDelete.cancelRepeat();
        keyboard.removeAllViews();
        letters.clear();
        shiftKey = null;
        if (page == Page.EMOJI) {
            int height = dp(compact() ? 25 : 43) + 4 * dp(compact() ? 39 : 64.3f);
            GagEmojiPicker picker = new GagEmojiPicker(keyboard.getContext(),
                    getSharedPreferences(GagEmojiPicker.PREFS, MODE_PRIVATE), emojiCatalog, compact(),
                    this::insertEmoji, this::closeEmojiPicker);
            keyboard.addView(picker, new LinearLayout.LayoutParams(-1, height));
            return;
        }
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
                else symbolKey(row, label, LETTER_SURFACE, 26);
            }
            if (rowIndex == 2) renderDelete(row, 1.5f);
            if (lettersPage && rowIndex == 1) spacer(row, .5f);
        }
        LinearLayout bottom = row();
        if (lettersPage) pageKey(bottom, "?123", 1.5f, R.string.gag_keyboard_symbols, Page.SYMBOLS);
        else pageKey(bottom, "ABC", 1.5f, R.string.gag_keyboard_letters, Page.LETTERS);
        String left = page == Page.MORE_SYMBOLS ? "<" : ",";
        String right = page == Page.MORE_SYMBOLS ? ">" : ".";
        Button punctuation = symbolKey(bottom, left, MODIFIER_SURFACE, 24);
        if (editorAllowed && ",".equals(left)) GagEmojiPicker.bindComma(punctuation, this::openEmojiPicker);
        if (!lettersPage) pageKey(bottom, "1 2\n3 4", 1f, R.string.gag_keyboard_numbers, Page.NUMBERS);
        renderSpace(bottom, lettersPage ? 5f : 4f);
        symbolKey(bottom, right, MODIFIER_SURFACE, 24);
        renderEnter(bottom, editorAction, 1.5f);
        renderCase();
    }

    private void openEmojiPicker() {
        if (!editorAllowed || getCurrentInputConnection() == null
                || (page != Page.LETTERS && page != Page.SYMBOLS)) return;
        if (emojiCatalog == null) {
            try {
                emojiCatalog = GagEmojiCatalog.load(this);
            } catch (java.io.IOException unreadable) {
                android.widget.Toast.makeText(this, R.string.gag_emoji_unavailable,
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
        }
        pageBeforeEmoji = page;
        clearSuggestions();
        page = Page.EMOJI;
        renderKeysIfNeeded(getCurrentInputEditorInfo());
    }

    private void closeEmojiPicker() {
        if (page != Page.EMOJI) return;
        page = pageBeforeEmoji;
        renderKeysIfNeeded(getCurrentInputEditorInfo());
        scheduleSuggestions();
    }

    private boolean insertEmoji(String emoji) {
        if (page != Page.EMOJI || !editorAllowed) return false;
        refreshReplacementAuthority();
        InputConnection input = getCurrentInputConnection();
        if (input == null) return false;
        clearPendingSpace();
        suggestionSpace = null;
        boolean committed = completeWord(input, emoji, null);
        literalWord = false;
        if (committed) resetOpening(false);
        return committed;
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
        if (word.length() == 0 && committedCursor < 0) scheduleSuggestions();
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
        int height = dp(compact() ? 25 : 43);
        if (editorAllowed) {
            int gutter = dp(44);
            ImageButton clipboardButton = new ImageButton(tools.getContext());
            clipboardButton.setImageResource(R.drawable.ic_gag_clipboard_tiles);
            clipboardButton.setContentDescription(getString(R.string.gag_keyboard_clipboard));
            clipboardButton.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            clipboardButton.setBackground(keyFace(MODIFIER_SURFACE, 7));
            clipboardButton.setPadding(0, 0, 0, 0);
            clipboardButton.setOnClickListener(view -> showClipboard());
            tools.addView(clipboardButton, new LinearLayout.LayoutParams(gutter, height));
        }
        suggestionStrip = new LinearLayout(tools.getContext());
        // Keep all three equal-width slots even when their candidate is absent.
        suggestionStrip.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        tools.addView(suggestionStrip, new LinearLayout.LayoutParams(0, height, 1));
        // Reserve the same width opposite the button to keep the middle candidate centered.
        if (editorAllowed) tools.addView(new Space(tools.getContext()),
                new LinearLayout.LayoutParams(dp(44), height));
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
        }
        scheduleSuggestions();
    }

    /** Rank zero owns the middle physical slot, including when either side is blank. */
    static void bindSuggestionSlots(LinearLayout strip, List<String> ranked,
            java.util.function.Consumer<String> accept) {
        for (int slot = 0; slot < 3; slot++) {
            TextView view = (TextView) strip.getChildAt(slot);
            int rank = slot == 0 ? 1 : slot == 1 ? 0 : 2;
            String candidate = rank < ranked.size() ? ranked.get(rank) : "";
            view.setVisibility(candidate.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            view.setText(candidate);
            view.setOnClickListener(candidate.isEmpty() ? null : ignored -> accept.accept(candidate));
        }
    }

    private void startClipboard() {
        if (clipboardManager != null || !clipboardVisible || !editorAllowed) return;
        clipboardSessionConnection = getCurrentInputConnection();
        if (clipboardSessionConnection == null) return;
        clipboardManager = getSystemService(ClipboardManager.class);
        if (clipboardManager != null) clipboardManager.addPrimaryClipChangedListener(clipboardChanged);
    }

    private void stopClipboard() {
        clipboardVisible = false;
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(clipboardChanged);
            clipboardManager = null;
        }
        closeClipboard();
        clipboardSessionConnection = null;
        clipboardClearedAt = 0L;
        clipboard.clear();
        main.removeCallbacks(expireClipboard);
    }

    private boolean clipboardAllowed() {
        return clipboardVisible && editorAllowed && clipboardSessionConnection != null
                && clipboardSessionConnection == getCurrentInputConnection();
    }

    private void captureClipboard() {
        if (!clipboardAllowed()) { stopClipboard(); return; }
        if (clipboardManager != null) try {
            if (GagClipboard.isPlainText(clipboardManager.getPrimaryClipDescription())) {
                android.content.ClipData clip = clipboardManager.getPrimaryClip();
                String text = GagClipboard.plainText(clip);
                if (text != null) {
                    long copied = clip.getDescription().getTimestamp();
                    if (copied > clipboardClearedAt)
                        clipboard.remember(text, copied, System.currentTimeMillis(),
                                android.os.SystemClock.elapsedRealtime());
                }
            }
        } catch (SecurityException ignored) {
            // Android can deny clipboard access for the current profile or window.
        }
        refreshClipboard();
    }

    private void showClipboard() {
        if (!clipboardAllowed() || keyboardHost == null || clipboardPanel != null || keyboard.getHeight() == 0) return;
        android.app.Dialog ime = getWindow();
        android.view.Window window = ime == null ? null : ime.getWindow();
        if (window == null) return;
        clipboardConnection = getCurrentInputConnection();
        clipboardPanel = new LinearLayout(keyboard.getContext());
        clipboardPanel.setOrientation(LinearLayout.VERTICAL);
        clipboardPanel.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        clipboardPanel.setBackgroundColor(0xff171717);
        clipboardPanel.setPadding(dp(8), 0, dp(8), 0);
        int secure = WindowManager.LayoutParams.FLAG_SECURE;
        if ((window.getAttributes().flags & secure) == 0) {
            window.addFlags(secure);
            clipboardSecuredWindow = window;
        }
        int height = keyboard.getHeight();
        keyboard.setVisibility(View.GONE);
        keyboardHost.addView(clipboardPanel, new LinearLayout.LayoutParams(-1, height));
        captureClipboard();
    }

    private void closeClipboard() {
        if (clipboardPanel == null) return;
        clipboardPanel.removeAllViews();
        keyboardHost.removeView(clipboardPanel);
        clipboardPanel = null;
        clipboardConnection = null;
        if (clipboardSecuredWindow != null) {
            clipboardSecuredWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            clipboardSecuredWindow = null;
        }
        keyboard.setVisibility(View.VISIBLE);
    }

    private void refreshClipboard() {
        main.removeCallbacks(expireClipboard);
        long now = android.os.SystemClock.elapsedRealtime();
        if (!clipboardAllowed()) {
            stopClipboard();
            return;
        }
        List<GagClipboard.Entry> entries = clipboard.entries(now);
        long nextExpiry = Long.MAX_VALUE;
        for (GagClipboard.Entry entry : entries) nextExpiry = Math.min(nextExpiry, entry.expiresAt());
        if (nextExpiry != Long.MAX_VALUE) main.postDelayed(expireClipboard, Math.max(1L, nextExpiry - now));
        if (clipboardPanel == null) return;
        clipboardPanel.removeAllViews();
        LinearLayout header = new LinearLayout(clipboardPanel.getContext());
        clipboardPanel.addView(header, new LinearLayout.LayoutParams(-1, dp(43)));
        clipboardButton(header, getString(R.string.gag_keyboard_back_to_keys), this::closeClipboard, true);
        clipboardButton(header, getString(R.string.gag_keyboard_clipboard_clear), () -> {
            clipboardClearedAt = System.currentTimeMillis();
            clipboard.clear();
            refreshClipboard();
        }, true);
        android.widget.ScrollView scroll = new android.widget.ScrollView(clipboardPanel.getContext());
        clipboardPanel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(scroll.getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        // Clipboard previews stay out of app-level autofill and saved hierarchy state.
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        content.setSaveFromParentEnabled(false);
        scroll.addView(content);
        if (entries.isEmpty()) {
            TextView empty = new TextView(content.getContext());
            empty.setText(getString(R.string.gag_keyboard_clipboard_empty));
            empty.setTextColor(LABEL_COLOR);
            empty.setPadding(dp(8), dp(12), dp(8), 0);
            content.addView(empty);
        }
        InputConnection input = clipboardConnection;
        LinearLayout panel = clipboardPanel;
        for (GagClipboard.Entry entry : entries)
            clipboardButton(content, entry.text(), () -> pasteClipboard(panel, input, entry), false);
    }

    private void clipboardButton(LinearLayout parent, String label, Runnable action, boolean inline) {
        Button button = makeKey(parent.getContext(), label, MODIFIER_SURFACE, 14, action);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setPadding(dp(10), 0, dp(10), 0);
        parent.addView(button, inline ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void pasteClipboard(View panel, InputConnection input, GagClipboard.Entry entry) {
        if (panel != clipboardPanel || !clipboardAllowed() || input != getCurrentInputConnection()
                || !clipboard.entries(android.os.SystemClock.elapsedRealtime()).contains(entry)) return;
        refreshReplacementAuthority();
        clearPendingSpace();
        clearSuggestions();
        resetOpening(false);
        suggestionSpace = null;
        String insertion = word + entry.text();
        word.setLength(0);
        String completed = GagClipboard.applyWords(insertion, token -> applyGag(token, token));
        if (!input.commitText(completed, 1)) input.finishComposingText();
        literalWord = false;
        if (!completed.isEmpty()) {
            int last = completed.codePointBefore(completed.length());
            literalWord = GagRules.wordCharacter(last) || GagRules.apostrophe(last);
        }
        closeClipboard();
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
        pendingDelete = null;
        main.removeCallbacks(checkDeleteSuggestions);
        committedCursor = -1;
        suggestionGeneration++;
        main.removeCallbacks(requestSuggestions);
        if (suggestionTask != null) {
            suggestionTask.cancel(false);
            suggestionTask = null;
        }
        if (suggestionStrip != null) bindSuggestionSlots(suggestionStrip, List.of(), null);
    }

    private void requestSuggestions() {
        if (!canSuggest()) return;
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        int cursor = committedCursor;
        String token = cursor < 0 ? word.toString() : readCommittedPrefix(input, cursor);
        long generation = suggestionGeneration;
        long epoch = editorEpoch;
        if (token.isEmpty()) {
            GagSuggestionContext context = GagSuggestionContext.read(input);
            if (context == null || (cursor >= 0 && context.cursor() >= 0 && context.cursor() != cursor)) return;
            List<String> candidates = nextWords(context);
            if (shifted) {
                List<String> uppercase = new ArrayList<>(candidates.size());
                for (String candidate : candidates) {
                    uppercase.add(capsLock ? candidate.toUpperCase(Locale.ROOT)
                            : Character.toUpperCase(candidate.charAt(0)) + candidate.substring(1));
                }
                candidates = uppercase;
            }
            bindSuggestionSlots(suggestionStrip, candidates,
                    candidate -> acceptNextWord(generation, epoch, input, context, candidate));
            return;
        }
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
                if (generation != suggestionGeneration || epoch != editorEpoch
                        || input != getCurrentInputConnection()
                        || !canSuggest() || candidates.isEmpty()) return;
                if (cursor >= 0 && !token.equals(readCommittedPrefix(input, cursor))) return;
                bindSuggestionSlots(suggestionStrip, candidates,
                        candidate -> acceptSuggestion(generation, epoch, input, cursor, token, candidate));
            });
        });
    }

    private List<String> nextWords(GagSuggestionContext context) {
        String language = getResources().getConfiguration().getLocales().get(0).getLanguage();
        if (!context.before().isEmpty()) return GagNextWords.after(context.before(), language);
        List<String> candidates = new ArrayList<>(3);
        GagPersonalDictionary personal = personalDictionary();
        if (personal.isLearningEnabled()) candidates.addAll(personal.suggestions("", List.of(), 3));
        for (String starter : GagNextWords.starters(language)) {
            if (candidates.size() == 3) break;
            if (candidates.stream().noneMatch(starter::equalsIgnoreCase)) candidates.add(starter);
        }
        return candidates;
    }

    private void acceptNextWord(long generation, long epoch, InputConnection input,
            GagSuggestionContext context, String candidate) {
        refreshReplacementAuthority();
        if (!isCurrentSuggestion(generation, epoch, input) || word.length() != 0) return;
        clearSuggestions();
        clearPendingSpace();
        if (!context.commit(input, candidate, token -> applyGag(token, token),
                completed -> commitSuggestion(input, 0, completed))) return;
        resetOpening(false);
        literalWord = false;
        lastShiftTap = 0;
        if (shifted && !capsLock) {
            shifted = false;
            renderCase();
        }
        scheduleSuggestions();
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

    private boolean isCurrentSuggestion(long generation, long epoch, InputConnection input) {
        return generation == suggestionGeneration && epoch == editorEpoch && canSuggest()
                && input == getCurrentInputConnection();
    }

    private void acceptSuggestion(long generation, long epoch, InputConnection input, int cursor,
            String token, String candidate) {
        refreshReplacementAuthority();
        if (!isCurrentSuggestion(generation, epoch, input)) return;
        clearPendingSpace();
        if (cursor < 0) {
            if (word.length() == 0) return;
            if (!completeWord(input, "", candidate)) return;
        } else {
            clearSuggestions();
            if (cursor < token.length() || !token.equals(readCommittedPrefix(input, cursor))) return;
            input.beginBatchEdit();
            try {
                if (!input.setComposingRegion(cursor - token.length(), cursor)) return;
                String completed = applyGag(token, candidate);
                if (!commitSuggestion(input, token.length(), completed)) {
                    input.finishComposingText();
                    return;
                }
                if (completed.equals(candidate)) rememberWord(candidate);
            } finally {
                input.endBatchEdit();
            }
        }
        literalWord = false;
        scheduleSuggestions();
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
        bindAlternatives(button, () -> GagKeyAlternatives.forLetter(label, alternate, shifted), alternate);
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

    private Button symbolKey(LinearLayout row, String label, int surface, int textSize) {
        Button button = key(row, label, 1f, surface, textSize, () -> type(label));
        List<String> choices = GagKeyAlternatives.forSymbol(label);
        if (!choices.isEmpty()) bindAlternatives(button, () -> choices, null);
        return button;
    }

    private void bindAlternatives(Button button, java.util.function.Supplier<List<String>> choices, String direct) {
        GagKeyPress press = new GagKeyPress(main, null);
        press.setAlternatives(keyAlternatives);
        button.setOnTouchListener(press);
        button.setOnLongClickListener(view -> {
            List<String> options = choices.get();
            if (direct != null && options.size() == 1) {
                type(direct);
                return true;
            }
            return keyAlternatives != null && keyAlternatives.show(view, options, this::type);
        });
    }

    private void dismissAlternatives() {
        if (keyAlternatives != null) keyAlternatives.dismiss();
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
        suggestionSpace = null;
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
