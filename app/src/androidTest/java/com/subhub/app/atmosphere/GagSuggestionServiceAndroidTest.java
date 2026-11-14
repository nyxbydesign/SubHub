package com.subhub.app.atmosphere;

import static org.junit.Assert.*;

import android.content.ContextWrapper;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.SystemClock;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.help.HelpActivity;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Runs production IME views against a real EditText without changing the selected system IME. */
public final class GagSuggestionServiceAndroidTest {
    @Test public void explicitSuggestionsPreserveExistingSpaceAndOwnOnlyTheirNewSeparator() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class);
                Fixture fixture = new Fixture(host)) {
            fixture.editor("teh next", 3);
            fixture.suggest("teh", "the");
            fixture.assertText("the next");

            for (String punctuation : List.of(",", "(", ")")) {
                fixture.editor("hel", 3);
                fixture.suggest("hel", "hello");
                fixture.assertText("hello ");
                fixture.tap(punctuation.equals(",") ? "," : "?123");
                if (!punctuation.equals(",")) fixture.tap(punctuation);
                fixture.assertText("hello" + punctuation);
            }

            fixture.editor("hel", 3);
            fixture.suggest("hel", "hello");
            fixture.tapDescription(R.string.gag_keyboard_space);
            fixture.tap(",");
            fixture.assertText("hello  ,");
        }
    }

    @Test public void apostropheReturnsTheRenderedSymbolPageToLetters() {
        withFixture(fixture -> {
            fixture.editor("", 0);
            fixture.tap("?123");
            fixture.tap("'");
            fixture.assertLetters();
            fixture.assertText("'");
        });
    }

    @Test public void spaceReturnsTheFirstSymbolPageToLetters() {
        withFixture(fixture -> {
            fixture.editor("", 0);
            fixture.tap("?123");
            fixture.tapDescription(R.string.gag_keyboard_space);
            fixture.assertLetters();
            fixture.assertText(" ");
        });
    }

    @Test public void spaceReturnsTheSecondSymbolPageToLetters() {
        withFixture(fixture -> {
            fixture.editor("", 0);
            fixture.tap("?123");
            fixture.tap("=\\<");
            fixture.tapDescription(R.string.gag_keyboard_space);
            fixture.assertLetters();
            fixture.assertText(" ");
        });
    }

    private static void withFixture(java.util.function.Consumer<Fixture> test) {
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class);
                Fixture fixture = new Fixture(host)) {
            test.accept(fixture);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ActivityScenario<HelpActivity> host;
        final GagKeyboardService service;
        final EditText editor;
        final InputConnection input;
        final LinearLayout keyboard;
        final GagPersonalDictionary dictionary;
        final boolean learning;

        Fixture(ActivityScenario<HelpActivity> host) {
            this.host = host;
            AtomicReference<EditText> editorRef = new AtomicReference<>();
            AtomicReference<InputConnection> inputRef = new AtomicReference<>();
            AtomicReference<GagKeyboardService> serviceRef = new AtomicReference<>();
            AtomicReference<LinearLayout> keysRef = new AtomicReference<>();
            AtomicReference<GagPersonalDictionary> dictionaryRef = new AtomicReference<>();
            host.onActivity(activity -> {
                EditText text = new EditText(activity);
                text.setInputType(InputType.TYPE_CLASS_TEXT);
                text.setShowSoftInputOnFocus(false);
                LinearLayout body = new LinearLayout(activity);
                body.setOrientation(LinearLayout.VERTICAL);
                body.addView(text, new LinearLayout.LayoutParams(-1, 64));
                LinearLayout keys = new LinearLayout(activity);
                keys.setOrientation(LinearLayout.VERTICAL);
                body.addView(keys, new LinearLayout.LayoutParams(-1, -2));
                activity.setContentView(body);
                text.requestFocus();
                InputConnection connection = text.onCreateInputConnection(new EditorInfo());
                assertNotNull(connection);
                GagKeyboardService ime = new GagKeyboardService();
                invoke(ContextWrapper.class, ime, "attachBaseContext",
                        new Class<?>[]{android.content.Context.class}, activity);
                set(InputMethodService.class, ime, "mStartedInputConnection", connection);
                set(GagKeyboardService.class, ime, "keyboard", keys);
                set(GagKeyboardService.class, ime, "lexicon", new GagLexicon(java.util.Map.of()));
                dictionaryRef.set(new GagPersonalDictionary(activity));
                set(GagKeyboardService.class, ime, "personalDictionary", dictionaryRef.get());
                invoke(GagKeyboardService.class, ime, "renderKeys", new Class<?>[]{int.class}, 0);
                set(GagKeyboardService.class, ime, "editorAllowed", true);
                assertSame(connection, ime.getCurrentInputConnection());
                editorRef.set(text);
                inputRef.set(connection);
                serviceRef.set(ime);
                keysRef.set(keys);
            });
            editor = editorRef.get();
            input = inputRef.get();
            service = serviceRef.get();
            keyboard = keysRef.get();
            dictionary = dictionaryRef.get();
            learning = dictionary.isLearningEnabled();
            dictionary.setLearningEnabled(false);
            idle();
        }

        void editor(String text, int cursor) {
            host.onActivity(activity -> {
                invoke(GagKeyboardService.class, service, "clearSuggestions", new Class<?>[0]);
                set(GagKeyboardService.class, service, "suggestionSpace", null);
                ((StringBuilder) field(GagKeyboardService.class, service, "word")).setLength(0);
                set(GagKeyboardService.class, service, "literalWord", false);
                invoke(GagKeyboardService.class, service, "clearPendingSpace", new Class<?>[0]);
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object letters = Enum.valueOf((Class) field(GagKeyboardService.class, service, "page").getClass(), "LETTERS");
                set(GagKeyboardService.class, service, "page", letters);
                invoke(GagKeyboardService.class, service, "renderKeys", new Class<?>[]{int.class}, 0);
                editor.setText(text);
                editor.setSelection(cursor);
            });
            idle();
        }

        void suggest(String token, String candidate) {
            host.onActivity(activity -> {
                long generation = (long) field(GagKeyboardService.class, service, "suggestionGeneration");
                long epoch = (long) field(GagKeyboardService.class, service, "editorEpoch");
                LinearLayout strip = (LinearLayout) field(GagKeyboardService.class, service, "suggestionStrip");
                GagKeyboardService.bindSuggestionSlots(strip, List.of(candidate), selected ->
                        invoke(GagKeyboardService.class, service, "acceptSuggestion",
                                new Class<?>[]{long.class, long.class, InputConnection.class,
                                        int.class, String.class, String.class},
                                generation, epoch, input, editor.getSelectionStart(), token, selected));
            });
            idle();
            tap(candidate);
        }

        void tap(String label) { tapView(find(keyboard, label, false)); }
        void tapDescription(int resource) {
            tapView(find(keyboard, InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getString(resource), true));
        }

        private void tapView(View view) {
            assertNotNull("Rendered key exists", view);
            host.onActivity(activity -> {
                assertTrue(view.isAttachedToWindow());
                assertTrue(view.getWidth() > 0 && view.getHeight() > 0);
                long now = SystemClock.uptimeMillis();
                dispatch(view, now, MotionEvent.ACTION_DOWN);
                dispatch(view, now, MotionEvent.ACTION_UP);
            });
            idle();
            host.onActivity(activity ->
                    invoke(GagKeyboardService.class, service, "clearSuggestions", new Class<?>[0]));
        }

        void assertText(String expected) {
            host.onActivity(activity -> assertEquals(expected, editor.getText().toString()));
        }

        void assertLetters() {
            host.onActivity(activity -> {
                assertEquals("LETTERS", field(GagKeyboardService.class, service, "page").toString());
                assertNotNull(find(keyboard, "q", false));
                assertNotNull(find(keyboard, "?123", false));
            });
        }

        @Override public void close() {
            dictionary.setLearningEnabled(learning);
            host.onActivity(activity -> {
                ((Handler) field(GagKeyboardService.class, service, "main")).removeCallbacksAndMessages(null);
                ((java.util.concurrent.ExecutorService) field(GagKeyboardService.class, service, "spelling")).shutdownNow();
                set(InputMethodService.class, service, "mStartedInputConnection", null);
            });
        }
    }

    private static void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static View find(View view, String label, boolean description) {
        if (description ? label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
                : view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup group)
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = find(group.getChildAt(index), label, description);
                if (found != null) return found;
            }
        return null;
    }

    private static void dispatch(View view, long time, int action) {
        MotionEvent event = MotionEvent.obtain(time, time, action, view.getWidth() / 2f, view.getHeight() / 2f, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    private static Object field(Class<?> owner, Object target, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void set(Class<?> owner, Object target, String name, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void invoke(Class<?> owner, Object target, String name, Class<?>[] types, Object... arguments) {
        try {
            Method method = owner.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(target, arguments);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
