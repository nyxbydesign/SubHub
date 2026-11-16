package com.subhub.app.atmosphere;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
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
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
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
    private static final long DRAFT_IMAGE_PREVIEW_MS = 60_000L;
    private static final int DELETION_BURST_IDLE_MS = 320;
    private static final int DELETION_HISTORY_LIFETIME_MS = 60_000;
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
    private final GagDeletionHistory deletionHistory = new GagDeletionHistory();
    private final Runnable finishDeletionBurst = this::finishDeletionBurst;
    private final Runnable expireDeletionHistory = this::expireDeletionHistory;
    private GagDeletionHistory.Snapshot deletionStart;
    private InputConnection deletionConnection;
    private long deletionRulesRevision;
    private boolean deletionIncludesComposition;
    private int observedComposingStart = -1;
    private int committedCursor = -1;
    private GagSuggestionSpace suggestionSpace;
    private PendingBracket pendingBracket;
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
    private boolean rulesReadSuccessfully;
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
    private GagKeyPreview keyPreview;
    private AlertDialog keyboardPicker;
    private LinearLayout keyboardHost;
    private LinearLayout clipboardPanel;
    private GagKeyAlternativesPopup clipboardMenu;
    private ClipboardManager clipboardManager;
    private GagClipboard clipboard;
    private final android.os.Handler clipboardPasteDelivery = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ThreadPoolExecutor clipboardImages =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(1),
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private java.util.concurrent.Future<?> clipboardImageTask;
    private View clipboardSuggestion;
    private boolean clipboardEditing;
    private boolean clipboardPastingImage;
    private long clipboardImageTimestamp;
    private long clipboardCaptureGeneration;
    private final Runnable expireClipboard = this::refreshClipboard;
    private final ClipboardManager.OnPrimaryClipChangedListener clipboardChanged = this::captureClipboard;
    private final Runnable scanScreenshots = this::scanScreenshots;
    private final java.util.Set<Long> changedScreenshots = new java.util.HashSet<>();
    private android.database.ContentObserver screenshotObserver;
    private android.os.CancellationSignal screenshotCancellation;
    private java.util.concurrent.Future<?> screenshotTask;
    private boolean screenshotRescan;
    private boolean clipboardVisible;
    private InputConnection clipboardConnection;
    private android.view.Window clipboardSecuredWindow;
    private Bitmap backdrop;
    private Page renderedPage;
    private int renderedInputType;
    private boolean renderedShifted, renderedCapsLock;
    private int renderedEditorAction = -1;
    private ImageButton shiftKey;
    private GagKeyPress repeatingDelete;
    private boolean subWorkspace;
    private boolean editorAllowed;
    private boolean keyPreviewAllowed;

    private record LetterKey(Button button, String letter, String alternate) {}
    private record KeyboardChoice(InputMethodInfo method, InputMethodSubtype subtype, CharSequence label) {}
    private enum Page { LETTERS, SYMBOLS, MORE_SYMBOLS, NUMBERS, EMOJI }

    @Override public void onCreate() {
        super.onCreate();
        clipboard = new GagClipboard(new GagClipboardPins(getNoBackupFilesDir()));
        clipboard.clearThrough(GagScreenshotClipboard.preferences(this)
                .getLong(GagScreenshotClipboard.CLEARED_THROUGH, 0));
        backend = RemoteFeature.get(this);
        configurationObserver = RemoteUi.observeConfiguration(this::refreshWorkspace);
        participation = GagParticipation.preferences(this);
        participation.registerOnSharedPreferenceChangeListener(participationChanged);
    }

    @Override public void onDestroy() {
        clearDeletionHistory();
        dismissKeyboardPicker();
        dismissKeyPopups();
        stopClipboard();
        clipboard.clear();
        GagClipboardFileProvider.retireAll(getApplicationContext());
        clipboardImages.shutdownNow();
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
        clearDeletionHistory();
        observedComposingStart = -1;
        dismissKeyboardPicker();
        dismissKeyPopups();
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
        boolean previewAllowed = GagEditorPolicy.allowsKeyPreview(editor);
        if (editorAllowed != allowed || keyPreviewAllowed != previewAllowed) renderedPage = null;
        editorAllowed = allowed;
        keyPreviewAllowed = previewAllowed;
        if (editorAllowed) loadLexicon();
        resetOpening(false);
        editorEpoch++;
        requestedRevision = -1;
        rulesRevision = -1;
        rulesReadSuccessfully = false;
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
        clearDeletionHistory();
        dismissKeyboardPicker();
        dismissKeyPopups();
        stopClipboard();
        if (page == Page.EMOJI) page = pageBeforeEmoji;
        clearPendingSpace();
        resetOpening(false);
        if (repeatingDelete != null) repeatingDelete.cancelRepeat();
        leaveWordLiteral();
        super.onFinishInputView(finishingInput);
    }

    @Override public void onFinishInput() {
        clearDeletionHistory();
        observedComposingStart = -1;
        dismissKeyboardPicker();
        dismissKeyPopups();
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
        rulesReadSuccessfully = false;
        rules = null;
        super.onFinishInput();
    }

    @Override public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd,
            int composingStart, int composingEnd) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, composingStart, composingEnd);
        observedComposingStart = composingStart;
        if (deletionStart != null && (newStart != newEnd || newEnd > deletionStart.end()
                || newEnd < deletionStart.start() - GagDeletionHistory.MAX_DELETED)) clearDeletionHistory();
        if (suggestionSpace != null
                && !suggestionSpace.atSelection(getCurrentInputConnection(), newStart, newEnd))
            suggestionSpace = null;
        if (pendingBracket != null
                && pendingBracket.cursorMoved(getCurrentInputConnection(), oldStart, oldEnd, newStart, newEnd))
            pendingBracket = null;
        if (clipboardManager != null && !clipboard.allows(getCurrentInputConnection()))
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
        } else if (deletionStart == null && deletionHistory.hasEntries()) {
            scheduleSuggestions();
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
            rulesReadSuccessfully = false;
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
                if (failure == null) {
                    try {
                        GagSettings settings = value == null ? null : GagSettings.from(value);
                        boolean enabled = settings != null && settings.enabled();
                        if (enabled && word.length() != 0) {
                            leaveWordLiteral();
                            literalWord = true;
                            resetOpening(false);
                        }
                        rules = enabled ? settings.rules() : null;
                        rulesReadSuccessfully = true;
                        if (!enabled && word.length() != 0) scheduleSuggestions();
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
        clearDeletionHistory();
        refreshReplacementAuthority();
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        int character = text.codePointAt(0);
        boolean insertSpace = takePendingSpace(input, character);
        // Report selection for the separator and the newly composing character together.
        boolean batching = insertSpace && input.beginBatchEdit();
        try {
            if (insertSpace) input.commitText(" ", 1);
            typeCharacter(input, text, character);
        } finally {
            if (batching) input.endBatchEdit();
        }
    }

    private void typeCharacter(InputConnection input, String text, int character) {
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
            boolean committed = completeWordWithBracket(input, text);
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
        suggestionSpace = null;
        pendingBracket = null;
        pendingPunctuation = 0;
        punctuationCursor = -1;
    }

    private boolean takePendingSpace(InputConnection input, int next) {
        GagSuggestionSpace space = suggestionSpace;
        char punctuation = pendingPunctuation;
        clearPendingSpace();
        if (!editorAllowed || !Character.isLetter(next)) return false;
        if (space != null && space.matches(input)) return true;
        if (punctuation == 0) return false;
        CharSequence selected = input.getSelectedText(0);
        return (selected == null || selected.length() == 0)
                && GagPunctuation.needsSpace(punctuation, next, input.getTextBeforeCursor(2, 0),
                        input.getTextAfterCursor(2, 0));
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
        if (completed.equals(proposed)) rememberCompletion(completed);
        else resetOpening(false);
        return true;
    }

    private boolean completeWordWithBracket(InputConnection input, String typed) {
        if (!editorAllowed) return completeWord(input, typed, null);
        String closing = switch (typed) {
            case "(" -> ")";
            case "[" -> "]";
            case "{" -> "}";
            default -> null;
        };
        if (closing == null) return completeWord(input, typed, null);
        boolean batching = input.beginBatchEdit();
        try {
            if (!completeWord(input, typed, null)) return false;
            try {
                // Zero places the cursor before the added closer.
                if (input.commitText(closing, 0)) {
                    GagDeleteSuggestion.Snapshot position = GagDeleteSuggestion.editorSnapshot(input);
                    if (position != null && position.left().endsWith(typed) && position.right().startsWith(closing))
                        pendingBracket = new PendingBracket(input, position);
                }
            } catch (RuntimeException unavailable) {
                // The typed opener was already committed.
            }
            return true;
        } finally {
            if (batching) input.endBatchEdit();
        }
    }

    private record PendingBracket(InputConnection connection, GagDeleteSuggestion.Snapshot position) {
        boolean cursorMoved(InputConnection current, int oldStart, int oldEnd, int newStart, int newEnd) {
            int cursor = position.cursor();
            // The opener and its auto-closer share the same post-edit caret. Ignore queued
            // selection updates from before that caret was reached.
            return current != connection || (oldStart == cursor && oldEnd == cursor
                    && (newStart != cursor || newEnd != cursor));
        }

        boolean matches(InputConnection current) {
            return current == connection && position.equals(GagDeleteSuggestion.editorSnapshot(current));
        }
    }

    private boolean commitSuggestion(InputConnection input, int originalLength, String completed) {
        GagDeleteSuggestion.Snapshot before = GagDeleteSuggestion.editorSnapshot(input);
        if (!input.commitText(completed, 1)) return false;
        suggestionSpace = GagSuggestionSpace.capture(input, before, originalLength, completed);
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

    private void rememberCompletion(String completed) {
        for (String token : completed.split(" ")) rememberWord(token);
    }

    private synchronized GagPersonalDictionary personalDictionary() {
        if (personalDictionary == null) personalDictionary = new GagPersonalDictionary(this);
        return personalDictionary;
    }

    private void backspace() {
        PendingBracket bracket = pendingBracket;
        clearPendingSpace();
        resetOpening(false);
        refreshReplacementAuthority();
        InputConnection input = getCurrentInputConnection();
        if (input == null) return;
        if (editorAllowed && word.length() == 0 && bracket != null && bracket.matches(input)
                && input.deleteSurroundingText(1, 1)) {
            showDeleteSuggestions(input, bracket.position().cursor() - 1);
            return;
        }
        trackDeletion(input);
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
        if (!pendingDelete.isFor(input)) return;
        int cursor = pendingDelete.deletedCursor(input);
        if (cursor >= 0) showDeleteSuggestions(input, cursor);
        else scheduleSuggestions();
    }

    private void showDeleteSuggestions(InputConnection input, int cursor) {
        if (cursor < 0) return;
        if (cursor == 0 && isEmptyEditor(input)) resetOpening(true);
        scheduleSuggestions(cursor);
    }

    private long currentDeletionRulesRevision() {
        return backend == null ? -1 : backend.gagRevision();
    }

    private boolean mayUseDeletionHistory(InputConnection input) {
        return editorAllowed && input != null && input == deletionConnection
                && deletionRulesRevision == currentDeletionRulesRevision()
                && (!deletionIncludesComposition || phraseRepairAllowed());
    }

    private void trackDeletion(InputConnection input) {
        boolean composing = word.length() != 0 || observedComposingStart >= 0;
        if (!editorAllowed || (composing && !phraseRepairAllowed())) {
            clearDeletionHistory();
            return;
        }
        if (!mayUseDeletionHistory(input)) clearDeletionHistory();
        if (deletionStart == null) {
            deletionStart = GagDeleteSuggestion.historySnapshot(input);
            if (deletionStart == null) {
                clearDeletionHistory();
                return;
            }
            deletionConnection = input;
            deletionRulesRevision = currentDeletionRulesRevision();
        }
        deletionIncludesComposition |= composing;
        main.removeCallbacks(finishDeletionBurst);
        main.postDelayed(finishDeletionBurst, DELETION_BURST_IDLE_MS);
    }

    private void finishDeletionBurst() {
        GagDeletionHistory.Snapshot start = deletionStart;
        deletionStart = null;
        InputConnection input = getCurrentInputConnection();
        if (start == null || !mayUseDeletionHistory(input)) {
            clearDeletionHistory();
            return;
        }
        boolean hadHistory = deletionHistory.hasEntries();
        GagDeletionHistory.Snapshot current = GagDeleteSuggestion.historySnapshot(input);
        deletionHistory.record(start, current);
        if (!deletionHistory.hasEntries()) clearDeletionHistory();
        else if (!start.equals(current)) renewDeletionHistory();
        if (hadHistory || deletionHistory.hasEntries()) scheduleSuggestions();
    }

    private void renewDeletionHistory() {
        main.removeCallbacks(expireDeletionHistory);
        main.postDelayed(expireDeletionHistory, DELETION_HISTORY_LIFETIME_MS);
    }

    private void expireDeletionHistory() {
        if (deletionStart != null) {
            // Let the active burst finish without carrying forward expired steps.
            deletionHistory.clear();
            return;
        }
        clearDeletionHistory();
        scheduleSuggestions();
    }

    private void clearDeletionHistory() {
        main.removeCallbacks(finishDeletionBurst);
        main.removeCallbacks(expireDeletionHistory);
        deletionStart = null;
        deletionConnection = null;
        deletionIncludesComposition = false;
        deletionHistory.clear();
    }

    private boolean canShowDeletionActions() {
        return editorAllowed && isInputViewShown() && suggestionStrip != null && clipboardPanel == null
                && page != Page.EMOJI && deletionStart == null && deletionHistory.hasEntries();
    }

    private boolean showDeletionActions(InputConnection input) {
        if (!canShowDeletionActions()) return false;
        if (!mayUseDeletionHistory(input)) {
            clearDeletionHistory();
            return false;
        }
        GagDeletionHistory.Snapshot current = GagDeleteSuggestion.historySnapshot(input);
        boolean undo = deletionHistory.edit(GagDeletionHistory.Action.UNDO, current) != null;
        boolean redo = deletionHistory.edit(GagDeletionHistory.Action.REDO, current) != null;
        if (!undo && !redo) {
            clearDeletionHistory();
            return false;
        }
        long generation = suggestionGeneration;
        bindDeletionAction(input, 0, GagDeletionHistory.Action.UNDO, R.string.gag_keyboard_undo, undo, generation);
        bindDeletionAction(input, 1, GagDeletionHistory.Action.REDO, R.string.gag_keyboard_redo, redo, generation);
        return true;
    }

    private void bindDeletionAction(InputConnection input, int slot, GagDeletionHistory.Action action,
            int label, boolean enabled, long generation) {
        TextView view = (TextView) suggestionStrip.getChildAt(slot);
        view.setText(label);
        view.setVisibility(View.VISIBLE);
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : .45f);
        view.setOnClickListener(enabled ? ignored -> applyDeletionAction(input, action, generation) : null);
    }

    private void applyDeletionAction(InputConnection input, GagDeletionHistory.Action action, long generation) {
        refreshReplacementAuthority();
        if (suggestionGeneration != generation || input != getCurrentInputConnection()
                || !canShowDeletionActions()) return;
        GagDeletionHistory.Edit edit = mayUseDeletionHistory(input)
                ? deletionHistory.edit(action, GagDeleteSuggestion.historySnapshot(input)) : null;
        if (edit == null || ((word.length() != 0 || observedComposingStart >= 0) && !phraseRepairAllowed())) {
            clearDeletionHistory();
            scheduleSuggestions();
            return;
        }
        clearPendingSpace();
        resetOpening(false);
        word.setLength(0);
        GagDeletionHistory.Snapshot expected = edit.destination(action);
        boolean applied = false;
        try {
            input.beginBatchEdit();
            try {
                if (input.finishComposingText()) {
                    if (action == GagDeletionHistory.Action.UNDO) {
                        applied = input.commitText(edit.deleted(), 1)
                                && (expected.start() == expected.end() || input.setSelection(expected.start(), expected.end()));
                    } else if (edit.before().selected().isEmpty()) {
                        applied = input.deleteSurroundingText(edit.deleted().length(), 0);
                    } else {
                        applied = (edit.before().start() == edit.after().start()
                                || input.setSelection(edit.after().start(), edit.before().end()))
                                && input.commitText("", 1);
                    }
                }
            } finally {
                input.endBatchEdit();
            }
        } catch (RuntimeException unavailable) {
            // The receiving editor can disappear while a tap is being dispatched.
        }
        GagDeletionHistory.Snapshot current = applied ? GagDeleteSuggestion.historySnapshot(input) : null;
        if (!deletionHistory.confirm(action, edit, current)) clearDeletionHistory();
        else renewDeletionHistory();
        literalWord = current != null && current.selected().isEmpty() && !current.left().isEmpty()
                && GagRules.wordCharacter(current.left().codePointBefore(current.left().length()));
        scheduleSuggestions();
    }

    private void enter() {
        clearDeletionHistory();
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
        dismissKeyPopups();
        closeClipboard();
        Context theme = new ContextThemeWrapper(this, android.R.style.Theme_Material);
        LinearLayout surface = new LinearLayout(theme);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setBackgroundColor(Color.BLACK);
        keyboard = new LinearLayout(theme);
        keyAlternatives = new GagKeyAlternativesPopup(keyboard);
        keyPreview = new GagKeyPreview();
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
        dismissKeyPopups();
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
        clearDeletionHistory();
        clearPendingSpace();
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
            bindCharacterPress(key(row, digit, 1, LETTER_SURFACE, 26, () -> type(digit)), null);
        }
    }

    private void renderNumericSymbol(LinearLayout row, String symbol) {
        if (symbol.isEmpty()) spacer(row, 1f);
        else bindCharacterPress(key(row, symbol, 1f, MODIFIER_SURFACE, 24, () -> type(symbol)), null);
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
        String label = page == Page.NUMBERS ? "" : getString(R.string.gag_keyboard_language);
        Button space = key(row, label, weight, LETTER_SURFACE, 13, () -> type(" "));
        space.setContentDescription(getString(R.string.gag_keyboard_space));
        space.setOnLongClickListener(view -> { chooseKeyboard(view); return true; });
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
            clipboardButton.setImageResource(R.drawable.ic_gag_clipboard);
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
            view.setEnabled(true);
            view.setAlpha(1f);
            view.setText(candidate);
            view.setOnClickListener(candidate.isEmpty() ? null : ignored -> accept.accept(candidate));
        }
    }

    private void startClipboard() {
        if (clipboardManager != null || !clipboardVisible || !editorAllowed) return;
        InputConnection input = getCurrentInputConnection();
        if (!clipboard.attach(input, getCurrentInputEditorInfo(), getPackageName())) return;
        startScreenshotClipboard();
        refreshClipboard();
        clipboardManager = getSystemService(ClipboardManager.class);
        if (clipboardManager != null) {
            clipboardManager.addPrimaryClipChangedListener(clipboardChanged);
            if (word.length() == 0 && isEmptyEditor(input)) captureClipboard();
        }
    }

    private void stopClipboard() {
        clipboardVisible = false;
        stopScreenshotClipboard();
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(clipboardChanged);
            clipboardManager = null;
        }
        closeClipboard();
        clearClipboardSuggestion();
        cancelClipboardImage();
        clipboard.detach();
    }

    private boolean clipboardAllowed() {
        return clipboardVisible && editorAllowed && clipboard.allows(getCurrentInputConnection());
    }

    private void startScreenshotClipboard() {
        if (!clipboardAllowed() || !GagScreenshotClipboard.updateAccess(this)) return;
        if (screenshotObserver == null) {
            var observer = new android.database.ContentObserver(main) {
                @Override public void onChange(boolean selfChange, Uri uri) {
                    if (this != screenshotObserver) return;
                    if (uri != null && changedScreenshots.size() < 24) {
                        try { changedScreenshots.add(android.content.ContentUris.parseId(uri)); }
                        catch (NumberFormatException ignored) { }
                    }
                    scheduleScreenshotScan();
                }
            };
            try {
                getContentResolver().registerContentObserver(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
                screenshotObserver = observer;
            } catch (SecurityException unavailable) { return; }
        }
        scheduleScreenshotScan();
    }

    private void scheduleScreenshotScan() {
        main.removeCallbacks(scanScreenshots);
        main.postDelayed(scanScreenshots, 220);
    }

    private void scanScreenshots() {
        if (!clipboardAllowed() || !GagScreenshotClipboard.updateAccess(this)) {
            stopScreenshotClipboard();
            refreshClipboard();
            return;
        }
        if (screenshotTask != null) {
            screenshotRescan = true;
            return;
        }
        android.os.CancellationSignal cancellation = new android.os.CancellationSignal();
        screenshotCancellation = cancellation;
        var prefs = GagScreenshotClipboard.preferences(this);
        long enabledSince = prefs.getLong(GagScreenshotClipboard.ENABLED_SINCE, 0);
        long clearedThrough = prefs.getLong(GagScreenshotClipboard.CLEARED_THROUGH, 0);
        long wallNow = System.currentTimeMillis();
        var previous = clipboard.screenshotRevisions(android.os.SystemClock.elapsedRealtime());
        var dismissed = GagScreenshotClipboard.dismissed(this, wallNow);
        var changed = java.util.Set.copyOf(changedScreenshots);
        changedScreenshots.clear();
        try {
            screenshotTask = clipboardImages.submit(() -> {
                GagScreenshotClipboard.Scan result = GagScreenshotClipboard.enabled(this)
                        ? GagScreenshotClipboard.scan(getContentResolver(), enabledSince,
                                clearedThrough, wallNow, previous, dismissed, changed, cancellation) : null;
                main.post(() -> applyScreenshotScan(cancellation, enabledSince, clearedThrough, result));
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            screenshotCancellation = null;
            changedScreenshots.addAll(changed);
        }
    }

    private void applyScreenshotScan(android.os.CancellationSignal cancellation, long enabledSince,
            long clearedThrough, GagScreenshotClipboard.Scan result) {
        if (screenshotCancellation != cancellation) return;
        screenshotCancellation = null;
        screenshotTask = null;
        if (screenshotRescan) {
            screenshotRescan = false;
            scheduleScreenshotScan();
        }
        var prefs = GagScreenshotClipboard.preferences(this);
        if (!clipboardAllowed()) return;
        if (!GagScreenshotClipboard.updateAccess(this)) {
            stopScreenshotClipboard();
            refreshClipboard();
            return;
        }
        if (enabledSince != prefs.getLong(GagScreenshotClipboard.ENABLED_SINCE, 0)
                || clearedThrough != prefs.getLong(GagScreenshotClipboard.CLEARED_THROUGH, 0)) return;
        if (result == null) return;
        boolean removed = clipboard.retainScreenshots(result.eligible());
        long now = System.currentTimeMillis();
        long elapsed = android.os.SystemClock.elapsedRealtime();
        for (var item : result.imported()) clipboard.rememberScreenshot(item, now, elapsed);
        if (removed || !result.imported().isEmpty()) refreshClipboard();
    }

    private void stopScreenshotClipboard() {
        main.removeCallbacks(scanScreenshots);
        if (screenshotObserver != null) {
            getContentResolver().unregisterContentObserver(screenshotObserver);
            screenshotObserver = null;
        }
        if (screenshotCancellation != null) screenshotCancellation.cancel();
        screenshotCancellation = null;
        if (screenshotTask != null) {
            screenshotTask.cancel(true);
            screenshotTask = null;
            clipboardImages.purge();
        }
        screenshotRescan = false;
        changedScreenshots.clear();
    }

    private void cancelClipboardImage() {
        clipboardCaptureGeneration++;
        clipboardImageTimestamp = 0L;
        if (clipboardImageTask == null) return;
        clipboardImageTask.cancel(true);
        clipboardImages.purge();
        clipboardImageTask = null;
    }

    private void captureClipboard() {
        if (!clipboardAllowed()) { stopClipboard(); return; }
        if (clipboardManager != null) try {
            if (GagClipboard.accepts(clipboardManager.getPrimaryClipDescription())) {
                ClipData clip = clipboardManager.getPrimaryClip();
                long copied = clip == null ? 0 : clip.getDescription().getTimestamp();
                if (clipboard.eligibleTimestamp(copied)) {
                    String text = GagClipboard.plainText(clip);
                    if (text != null) {
                        clipboard.remember(text, copied, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime());
                    } else {
                        Uri uri = GagClipboard.imageUri(clip);
                        if (uri != null) captureClipboardImage(clip, uri, copied);
                    }
                }
            }
        } catch (SecurityException ignored) {
            // Android can deny clipboard access for the current profile or window.
        }
        refreshClipboard();
    }

    private void captureClipboardImage(ClipData clip, Uri source, long copied) {
        long age = System.currentTimeMillis() - copied;
        if (copied == clipboardImageTimestamp || age < 0 || age >= GagClipboard.LIFETIME_MS) return;
        cancelClipboardImage();
        InputConnection input = getCurrentInputConnection();
        long generation = clipboardCaptureGeneration;
        var description = clip.getDescription();
        try {
            clipboardImageTask = clipboardImages.submit(() -> {
                GagClipboardImage image = GagClipboardImage.read(getContentResolver(), source, description);
                main.post(() -> {
                    if (generation != clipboardCaptureGeneration || !clipboardAllowed()
                            || !clipboard.allows(input) || !clipboard.eligibleTimestamp(copied)) return;
                    clipboardImageTask = null;
                    if (image == null) { clipboardImageTimestamp = 0L; return; }
                    clipboard.remember(image, copied, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime());
                    refreshClipboard();
                });
            });
            clipboardImageTimestamp = copied;
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            // The bounded image worker is still reading another clipboard item.
        }
    }

    private void showClipboard() {
        if (!clipboardAllowed() || keyboardHost == null || clipboardPanel != null || keyboard.getHeight() == 0) return;
        dismissKeyPopups();
        android.app.Dialog ime = getWindow();
        android.view.Window window = ime == null ? null : ime.getWindow();
        if (window == null) return;
        clipboardConnection = getCurrentInputConnection();
        clipboardPanel = new LinearLayout(keyboard.getContext());
        clipboardMenu = new GagKeyAlternativesPopup(clipboardPanel);
        clipboardPanel.setOrientation(LinearLayout.VERTICAL);
        clipboardPanel.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        clipboardPanel.setBackgroundColor(0xff171717);
        clipboardPanel.setPadding(dp(8), 0, dp(8), 0);
        updateClipboardWindowSecurity();
        int height = keyboard.getHeight();
        keyboard.setVisibility(View.GONE);
        keyboardHost.addView(clipboardPanel, new LinearLayout.LayoutParams(-1, height));
        boolean requestPhotoAccess = GagScreenshotClipboard.onClipboardOpened(this);
        startScreenshotClipboard();
        captureClipboard();
        if (requestPhotoAccess) GagClipboardPermissionActivity.open(this);
    }

    private void closeClipboard() {
        if (clipboardPanel == null) return;
        clipboardMenu.dismiss();
        clipboardMenu = null;
        clipboardPanel.removeAllViews();
        keyboardHost.removeView(clipboardPanel);
        clipboardPanel = null;
        clipboardConnection = null;
        clipboardEditing = false;
        updateClipboardWindowSecurity();
        keyboard.setVisibility(View.VISIBLE);
    }

    private void updateClipboardWindowSecurity() {
        android.app.Dialog ime = getWindow();
        Window window = ime == null ? null : ime.getWindow();
        int secure = WindowManager.LayoutParams.FLAG_SECURE;
        if ((clipboardPanel != null || clipboardSuggestion != null) && window != null) {
            if ((window.getAttributes().flags & secure) == 0) {
                window.addFlags(secure);
                clipboardSecuredWindow = window;
            }
        } else if (clipboardSecuredWindow != null) {
            clipboardSecuredWindow.clearFlags(secure);
            clipboardSecuredWindow = null;
        }
    }

    private void refreshClipboard() {
        main.removeCallbacks(expireClipboard);
        long now = android.os.SystemClock.elapsedRealtime();
        if (!GagScreenshotClipboard.enabled(this)) clipboard.retainScreenshots(java.util.Set.of());
        List<GagClipboard.Entry> entries = clipboard.entries(now);
        long nextExpiry = Long.MAX_VALUE;
        GagClipboard.Entry newestRecent = null;
        for (GagClipboard.Entry entry : entries) {
            nextExpiry = Math.min(nextExpiry, entry.expiresAt());
            if (newestRecent == null && !entry.pinned()) newestRecent = entry;
        }
        if (newestRecent != null && newestRecent.image() != null) {
            long previewExpires = draftImagePreviewExpiry(newestRecent);
            if (previewExpires > now) nextExpiry = Math.min(nextExpiry, previewExpires);
        }
        if (nextExpiry != Long.MAX_VALUE) main.postDelayed(expireClipboard, Math.max(1L, nextExpiry - now));
        if (!clipboardAllowed()) {
            if (clipboardManager != null || clipboardPanel != null || clipboardSuggestion != null) stopClipboard();
            return;
        }
        if (clipboardPanel == null) {
            if (canSuggest() && word.length() == 0 && (clipboardSuggestion != null
                    || canOfferClipboardSuggestion(newestRecent, getCurrentInputEditorInfo(), false, now)
                    || isEmptyEditor(getCurrentInputConnection())))
                scheduleSuggestions();
            return;
        }
        clipboardMenu.dismiss();
        clipboardPanel.removeAllViews();
        LinearLayout header = new LinearLayout(clipboardPanel.getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        clipboardPanel.addView(header, new LinearLayout.LayoutParams(-1, -2));
        Button back = clipboardControl(header, "‹", this::closeClipboard);
        back.setContentDescription(getString(R.string.gag_keyboard_back_to_keys));
        TextView heading = clipboardLabel(header, getString(R.string.gag_clipboard_title), 17);
        ViewCompat.setAccessibilityHeading(heading, true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (!entries.isEmpty())
            clipboardControl(header, getString(clipboardEditing ? R.string.gag_clipboard_done
                    : R.string.gag_clipboard_edit), () -> {
                clipboardEditing = !clipboardEditing;
                refreshClipboard();
            });

        android.widget.ScrollView scroll = new android.widget.ScrollView(clipboardPanel.getContext());
        clipboardPanel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(scroll.getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        // Clipboard previews stay out of app-level autofill and saved hierarchy state.
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        content.setSaveFromParentEnabled(false);
        scroll.addView(content);
        if (entries.isEmpty()) {
            TextView empty = clipboardLabel(content, getString(R.string.gag_keyboard_clipboard_empty), 14);
            empty.setPadding(dp(8), dp(12), dp(8), 0);
            content.addView(empty);
            return;
        }
        var sections = entries.stream().collect(java.util.stream.Collectors.partitioningBy(GagClipboard.Entry::pinned));
        addClipboardSection(content, sections.get(true), R.string.gag_clipboard_pinned);
        addClipboardSection(content, sections.get(false), R.string.gag_clipboard_recent);
    }

    private void addClipboardSection(LinearLayout content, List<GagClipboard.Entry> entries, int title) {
        if (entries.isEmpty()) return;
        TextView heading = clipboardLabel(content, getString(title), 14);
        ViewCompat.setAccessibilityHeading(heading, true);
        content.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        if (clipboardEditing && title == R.string.gag_clipboard_recent)
            clipboardControl(content, getString(R.string.gag_keyboard_clipboard_clear), this::clearClipboardHistory);
        InputConnection input = clipboardConnection;
        LinearLayout panel = clipboardPanel;
        for (int start = 0; start < entries.size(); start += 2) {
            LinearLayout row = new LinearLayout(content.getContext());
            LinearLayout.LayoutParams rowSize = new LinearLayout.LayoutParams(-1, -2);
            rowSize.topMargin = dp(6);
            content.addView(row, rowSize);
            for (int column = 0; column < 2; column++) {
                LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(0, dp(84), 1);
                if (column == 1) cardSize.setMarginStart(dp(6));
                if (start + column == entries.size()) {
                    row.addView(new Space(row.getContext()), cardSize);
                    continue;
                }
                GagClipboard.Entry entry = entries.get(start + column);
                View card = clipboardPreview(row, entry, false);
                card.setTag("gag_clipboard_card");
                bindClipboardCard(card, panel, input, entry);
                row.addView(card, cardSize);
            }
        }
    }

    private void bindClipboardCard(View card, View panel, InputConnection input, GagClipboard.Entry entry) {
        if (!clipboardEditing || entry.text() != null) card.setOnClickListener(view -> {
            if (!clipboardEditing) {
                pasteClipboard(panel, input, entry);
            } else if (clipboardCardIsCurrent(panel, input, entry)
                    && !GagClipboardEditActivity.open(this, clipboard, entry)) {
                clipboardChangeFailed();
            }
        });
        String pin = getString(entry.pinned() ? R.string.gag_clipboard_unpin : R.string.gag_clipboard_pin);
        String delete = getString(R.string.delete);
        var choices = entry.text() == null ? List.of(delete) : List.of(pin, delete);
        GagKeyPress press = new GagKeyPress(main, null);
        press.setAlternatives(clipboardMenu);
        card.setOnTouchListener(press);
        card.setOnLongClickListener(view -> {
            if (clipboardCardIsCurrent(panel, input, entry) && clipboardMenu.showActions(view, choices, action -> {
                if (clipboardCardIsCurrent(panel, input, entry)) {
                    changeClipboardEntry(entry, action.equals(delete));
                }
            })) view.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        });
    }

    private boolean clipboardCardIsCurrent(View panel, InputConnection input, GagClipboard.Entry entry) {
        return panel == clipboardPanel && clipboardAllowed() && clipboard.allows(input)
                && (entry.screenshot() == null || GagScreenshotClipboard.enabled(this))
                && clipboard.contains(entry, android.os.SystemClock.elapsedRealtime());
    }

    private void changeClipboardEntry(GagClipboard.Entry entry, boolean delete) {
        long elapsed = android.os.SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        boolean changed = delete ? clipboard.remove(entry, wall)
                : entry.pinned() ? clipboard.unpin(entry, wall, elapsed) : clipboard.pin(entry, elapsed);
        if (!changed) {
            clipboardChangeFailed();
            return;
        }
        if (delete && entry.screenshot() != null) {
            stopScreenshotClipboard();
            GagScreenshotClipboard.dismiss(this, entry.screenshot());
            startScreenshotClipboard();
        }
        if (clipboard.entries(elapsed).isEmpty()) clipboardEditing = false;
        refreshClipboard();
    }

    private void clipboardChangeFailed() {
        android.widget.Toast.makeText(this, R.string.gag_clipboard_change_failed, android.widget.Toast.LENGTH_SHORT).show();
        refreshClipboard();
    }

    private void clearClipboardHistory() {
        cancelClipboardImage();
        stopScreenshotClipboard();
        long now = System.currentTimeMillis();
        clipboard.clearThrough(now);
        GagScreenshotClipboard.clearThrough(this, now);
        startScreenshotClipboard();
        GagClipboardFileProvider.retireAll(getApplicationContext());
        clipboardEditing = false;
        refreshClipboard();
    }

    private Button clipboardControl(LinearLayout parent, String label, Runnable action) {
        Button button = makeKey(parent.getContext(), label, MODIFIER_SURFACE, 14, action);
        button.setPadding(dp(10), 0, dp(10), 0);
        parent.addView(button, new LinearLayout.LayoutParams(-2, dp(44)));
        return button;
    }

    private TextView clipboardLabel(View parent, CharSequence label, int size) {
        return clipboardLabel(parent.getContext(), label, size);
    }

    private static TextView clipboardLabel(Context context, CharSequence label, int size) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(LABEL_COLOR);
        return view;
    }

    private String clipboardTitle(GagClipboard.Entry entry) {
        if (entry.screenshot() != null) return getString(R.string.gag_clipboard_screenshot);
        return entry.image() == null ? GagClipboard.previewText(entry.text()) : getString(R.string.gag_clipboard_image);
    }

    private View clipboardPreview(View parent, GagClipboard.Entry entry, boolean compact) {
        return clipboardPreview(parent.getContext(), entry, clipboardTitle(entry), compact);
    }

    static LinearLayout clipboardPreview(Context context, GagClipboard.Entry entry, String title, boolean compact) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        card.setGravity(compact ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        int horizontal = Math.round(8 * density);
        int vertical = Math.round((compact ? 2 : 6) * density);
        card.setPadding(horizontal, vertical, horizontal, vertical);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(compact ? MODIFIER_SURFACE : 0xff292929);
        surface.setCornerRadius(9 * density);
        card.setBackground(surface);
        card.setFocusable(true);
        card.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        card.setSaveFromParentEnabled(false);
        if (entry.image() != null) {
            ImageView image = new ImageView(context);
            image.setTag("gag_clipboard_thumbnail");
            image.setImageBitmap(entry.image().thumbnail());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams dimensions = compact
                    ? new LinearLayout.LayoutParams(Math.round(30 * density), Math.round(24 * density))
                    : new LinearLayout.LayoutParams(-1, 0, 1);
            if (compact) dimensions.setMarginEnd(horizontal);
            card.addView(image, dimensions);
        }
        TextView label = clipboardLabel(context, title, 14);
        label.setMaxLines(compact || entry.image() != null ? 1 : 3);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(label, new LinearLayout.LayoutParams(compact ? -2 : -1, -2));
        return card;
    }

    private void pasteClipboard(View panel, InputConnection input, GagClipboard.Entry entry) {
        if ((panel != null && panel != clipboardPanel) || !clipboardAllowed()
                || (entry.screenshot() != null && !GagScreenshotClipboard.enabled(this))
                || !clipboard.allows(input)
                || !clipboard.contains(entry, android.os.SystemClock.elapsedRealtime())) return;
        if (entry.image() != null) {
            pasteClipboardImage(input, entry);
            return;
        }
        refreshReplacementAuthority();
        String insertion = word + entry.text();
        String completed = subWorkspace && rules != null
                ? GagClipboard.applyWords(insertion, token -> applyGag(token, token)) : insertion;
        if (completed == null) {
            android.widget.Toast.makeText(this, R.string.gag_clipboard_paste_too_long, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        clearDeletionHistory();
        clearPendingSpace();
        clearSuggestions();
        resetOpening(false);
        word.setLength(0);
        if (!input.commitText(completed, 1)) input.finishComposingText();
        literalWord = false;
        if (!completed.isEmpty()) {
            int last = completed.codePointBefore(completed.length());
            literalWord = GagRules.wordCharacter(last) || GagRules.apostrophe(last);
        }
        closeClipboard();
    }

    private void pasteClipboardImage(InputConnection input, GagClipboard.Entry entry) {
        GagClipboardImage image = entry.image();
        if (clipboardPastingImage) return;
        if (!image.supports(getCurrentInputEditorInfo())) {
            android.widget.Toast.makeText(this, R.string.gag_clipboard_image_unsupported,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long epoch = editorEpoch;
        Context context = getApplicationContext();
        clipboardPastingImage = true;
        try {
            clipboardImages.execute(() -> {
                java.io.File prepared = null;
                try { prepared = GagClipboardFileProvider.prepare(context, image); }
                catch (java.io.IOException | RuntimeException unavailable) { }
                java.io.File file = prepared;
                clipboardPasteDelivery.post(() -> {
                    clipboardPastingImage = false;
                    if (file == null) return;
                    long now = android.os.SystemClock.elapsedRealtime();
                    EditorInfo editor = getCurrentInputEditorInfo();
                    Uri shared = null;
                    boolean accepted = false;
                    try {
                        if (epoch != editorEpoch || !clipboardAllowed() || !clipboard.allows(input)
                                || (entry.screenshot() != null && !GagScreenshotClipboard.enabled(this))
                                || !image.supports(editor) || !clipboard.entries(now).contains(entry)) return;
                        leaveWordLiteral();
                        clearDeletionHistory();
                        clearPendingSpace();
                        clearSuggestions();
                        resetOpening(false);
                        shared = GagClipboardFileProvider.publish(context, file, entry.expiresAt() - now);
                        accepted = image.commit(input, editor, shared);
                        if (accepted) closeClipboard();
                    } catch (RuntimeException unavailable) {
                        // The receiving editor or its connection can disappear after the tap.
                    } finally {
                        if (!accepted) GagClipboardFileProvider.retire(context, file, shared);
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            clipboardPastingImage = false;
        }
    }

    private void clearClipboardSuggestion() {
        if (clipboardSuggestion == null) return;
        if (clipboardSuggestion.getParent() instanceof LinearLayout strip) strip.removeView(clipboardSuggestion);
        clipboardSuggestion = null;
        updateClipboardWindowSecurity();
    }

    private boolean showClipboardSuggestion(InputConnection input) {
        if (!GagScreenshotClipboard.enabled(this)) clipboard.retainScreenshots(java.util.Set.of());
        if (!clipboardAllowed()) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        List<GagClipboard.Entry> entries = clipboard.recentEntries(now);
        if (entries.isEmpty()) return false;
        GagClipboard.Entry latest = entries.get(0);
        if (!canOfferClipboardSuggestion(latest, getCurrentInputEditorInfo(), isEmptyEditor(input), now)) return false;
        clearClipboardSuggestion();
        View preview = clipboardPreview(suggestionStrip, latest, true);
        preview.setTag("gag_clipboard_suggestion");
        long generation = suggestionGeneration;
        preview.setOnClickListener(view -> {
            if (generation == suggestionGeneration && view == clipboardSuggestion
                    && canOfferClipboardSuggestion(latest, getCurrentInputEditorInfo(), isEmptyEditor(input),
                            android.os.SystemClock.elapsedRealtime()))
                pasteClipboard(null, input, latest);
        });
        clipboardSuggestion = preview;
        updateClipboardWindowSecurity();
        placeClipboardSuggestion(suggestionStrip, preview);
        return true;
    }

    static void placeClipboardSuggestion(LinearLayout strip, View preview) {
        strip.setGravity(Gravity.CENTER);
        for (int index = 0; index < 3; index++) strip.getChildAt(index).setVisibility(View.GONE);
        strip.addView(preview, new LinearLayout.LayoutParams(-2, -1));
    }

    static boolean canOfferClipboardSuggestion(GagClipboard.Entry entry, EditorInfo editor, boolean empty, long now) {
        if (entry == null || entry.expiresAt() <= now) return false;
        if (entry.image() == null) return empty;
        return entry.image().supports(editor) && (empty || now < draftImagePreviewExpiry(entry));
    }

    private static long draftImagePreviewExpiry(GagClipboard.Entry entry) {
        return entry.expiresAt() - GagClipboard.LIFETIME_MS + DRAFT_IMAGE_PREVIEW_MS;
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
        if (!canSuggest() && !canShowDeletionActions()) return;
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
        clearClipboardSuggestion();
        if (suggestionStrip != null) bindSuggestionSlots(suggestionStrip, List.of(), null);
    }

    private void requestSuggestions() {
        InputConnection input = getCurrentInputConnection();
        if (input == null || showDeletionActions(input) || !canSuggest()) return;
        int cursor = committedCursor;
        String token = cursor < 0 ? word.toString() : readCommittedPrefix(input, cursor);
        long generation = suggestionGeneration;
        long epoch = editorEpoch;
        if (token.isEmpty()) {
            if (showClipboardSuggestion(input)) return;
            GagSuggestionContext context = GagSuggestionContext.read(input);
            if (context == null || (cursor >= 0 && context.cursor() >= 0 && context.cursor() != cursor)) return;
            String before = context.before();
            if (suggestionSpace != null && suggestionSpace.matches(input)) before += " ";
            List<String> candidates = nextWords(before);
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
        boolean repairPhrases = phraseRepairAllowed();
        String language = getResources().getConfiguration().getLocales().get(0).getLanguage();
        suggestionTask = spelling.submit(() -> {
            List<String> generic = source == null ? List.of() : source.suggestions(token);
            boolean known = source != null && source.isKnownWord(token);
            if (known) {
                List<String> withLiteral = new ArrayList<>(generic.size() + 1);
                withLiteral.add(token);
                withLiteral.addAll(generic);
                generic = withLiteral;
            }
            List<String> singleWords = personalDictionary().suggestions(token, generic, 3);
            String phrase = repairPhrases && source != null ? source.phraseRepair(token, language) : null;
            main.post(() -> {
                if (generation != suggestionGeneration || epoch != editorEpoch
                        || input != getCurrentInputConnection()
                        || !canSuggest()) return;
                if (cursor >= 0 && !token.equals(readCommittedPrefix(input, cursor))) return;
                List<String> candidates = phraseRepairAllowed() && phrase != null
                        ? GagLexicon.prioritizePhrase(token, known, singleWords, phrase) : singleWords;
                if (candidates.isEmpty()) return;
                bindSuggestionSlots(suggestionStrip, candidates,
                        candidate -> acceptSuggestion(generation, epoch, input, cursor, token, candidate));
            });
        });
    }

    private boolean phraseRepairAllowed() {
        // A phrase would change how a contract matches or counts the active token.
        return !subWorkspace || !GagParticipation.allowed(participation, keyboardAlreadyEnabled)
                || (rules == null && rulesReadSuccessfully && backend != null
                        && rulesRevision == backend.gagRevision());
    }

    private List<String> nextWords(String before) {
        String language = getResources().getConfiguration().getLocales().get(0).getLanguage();
        if (!before.isEmpty()) return GagNextWords.predict(before, language);
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
        clearDeletionHistory();
        clearSuggestions();
        GagSuggestionSpace space = suggestionSpace;
        clearPendingSpace();
        if (!context.commit(input, candidate, token -> applyGag(token, token),
                completed -> commitSuggestion(input, 0,
                        space != null && space.matches(input) ? " " + completed : completed))) return;
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
                if (selection != text.getSelectionEnd()
                        || (cursor >= 0 && text.getOffset() + selection != cursor))
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
        boolean phrase = candidate.indexOf(' ') >= 0;
        if (phrase && !phraseRepairAllowed()) return;
        clearDeletionHistory();
        clearPendingSpace();
        if (cursor < 0) {
            if (!token.contentEquals(word) || (phrase && !token.equals(readCommittedPrefix(input, cursor)))) return;
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
                if (completed.equals(candidate)) rememberCompletion(candidate);
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
        bindCharacterPress(button, () -> GagKeyAlternatives.forLetter(label, alternate, shifted));
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
        bindCharacterPress(button, choices.isEmpty() ? null : () -> choices);
        return button;
    }

    private void bindCharacterPress(Button button, java.util.function.Supplier<List<String>> choices) {
        GagKeyPress press = new GagKeyPress(main, null, keyPreviewAllowed ? keyPreview : null);
        press.setAlternatives(keyAlternatives);
        button.setOnTouchListener(press);
        if (choices == null) return;
        button.setOnLongClickListener(view -> {
            keyPreview.dismiss();
            keyAlternatives.show(view, choices.get(), this::type);
            return true;
        });
    }

    private void dismissKeyPopups() {
        if (keyPreview != null) keyPreview.dismiss();
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

    private void chooseKeyboard(View anchor) {
        if (keyboardPicker != null || anchor.getWindowToken() == null) return;
        dismissKeyPopups();
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager == null) return;

        ComponentName ownService = new ComponentName(this, GagKeyboardService.class);
        InputMethodSubtype currentSubtype = manager.getCurrentInputMethodSubtype();
        List<KeyboardChoice> choices = new ArrayList<>();
        int checked = -1;
        for (InputMethodInfo method : manager.getEnabledInputMethodList()) {
            boolean ownKeyboard = ownService.equals(method.getComponent());
            CharSequence name = method.loadLabel(getPackageManager());
            if (TextUtils.isEmpty(name)) name = method.getPackageName();
            List<InputMethodSubtype> subtypes = manager.getEnabledInputMethodSubtypeList(method, true);
            if (subtypes.isEmpty()) {
                if (ownKeyboard) checked = choices.size();
                choices.add(new KeyboardChoice(method, null, name));
                continue;
            }
            if (Build.VERSION.SDK_INT < 28 && subtypes.size() > 1) {
                showSystemKeyboardPicker(manager);
                return;
            }
            for (InputMethodSubtype subtype : subtypes) {
                if (ownKeyboard && (subtype.equals(currentSubtype) || subtypes.size() == 1)) {
                    checked = choices.size();
                }
                CharSequence language = subtype.getDisplayName(this, method.getPackageName(),
                        method.getServiceInfo().applicationInfo);
                CharSequence label = ownKeyboard || TextUtils.isEmpty(language) || TextUtils.equals(language, name)
                        ? name : TextUtils.concat(language, "\n", name);
                choices.add(new KeyboardChoice(method, subtype, label));
            }
        }
        if (checked == -1) {
            showSystemKeyboardPicker(manager);
            return;
        }

        int currentChoice = checked;
        CharSequence[] labels = choices.stream().map(KeyboardChoice::label).toArray(CharSequence[]::new);
        AlertDialog picker = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.gag_keyboard_change)
                .setSingleChoiceItems(labels, currentChoice, (dialog, position) -> {
                    dialog.dismiss();
                    if (position != currentChoice) switchKeyboard(manager, choices.get(position));
                })
                .create();
        picker.setCanceledOnTouchOutside(true);
        picker.setOnDismissListener(dialog -> {
            if (keyboardPicker == picker) keyboardPicker = null;
        });
        Window window = picker.getWindow();
        if (window == null) {
            showSystemKeyboardPicker(manager);
            return;
        }
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.token = anchor.getWindowToken();
        attributes.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        attributes.gravity = Gravity.CENTER;
        window.setAttributes(attributes);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        keyboardPicker = picker;
        try {
            picker.show();
        } catch (WindowManager.BadTokenException detached) {
            keyboardPicker = null;
            if (isInputViewShown()) showSystemKeyboardPicker(manager);
        }
    }

    private void dismissKeyboardPicker() {
        if (keyboardPicker == null) return;
        keyboardPicker.dismiss();
        keyboardPicker = null;
    }

    private void switchKeyboard(InputMethodManager manager, KeyboardChoice choice) {
        prepareKeyboardSwitch();
        try {
            if (Build.VERSION.SDK_INT >= 28 && choice.subtype() != null) {
                switchInputMethod(choice.method().getId(), choice.subtype());
            } else {
                switchInputMethod(choice.method().getId());
            }
        } catch (IllegalArgumentException | SecurityException unavailable) {
            manager.showInputMethodPicker();
        }
    }

    private void showSystemKeyboardPicker(InputMethodManager manager) {
        prepareKeyboardSwitch();
        manager.showInputMethodPicker();
    }

    private void prepareKeyboardSwitch() {
        clearPendingSpace();
        if (word.length() != 0) literalWord = true;
        leaveWordLiteral();
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
