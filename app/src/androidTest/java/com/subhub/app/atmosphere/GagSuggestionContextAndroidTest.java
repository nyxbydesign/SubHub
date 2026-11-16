package com.subhub.app.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.text.Editable;
import android.text.Selection;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import androidx.test.core.app.ActivityScenario;
import com.subhub.app.help.HelpActivity;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class GagSuggestionContextAndroidTest {
    @Test public void explicitSelectionAppendsAtTheVerifiedCursorAndRechecksTheWholeContext() {
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            host.onActivity(activity -> {
                BaseInputConnection input = new BaseInputConnection(new View(activity), true);
                Editable editor = input.getEditable();
                Selection.setSelection(editor, 0);
                GagSuggestionContext empty = GagSuggestionContext.read(input);
                assertNotNull(empty);
                String starter = GagNextWords.starters("en").get(0);
                assertEquals("", editor.toString());
                assertTrue(commit(input, empty, starter, word -> word));
                assertEquals("I", editor.toString());
                assertFalse(commit(input, empty, starter, word -> word));

                assertTrue(input.commitText(" ", 1));
                GagSuggestionContext afterStarter = GagSuggestionContext.read(input);
                assertNotNull(afterStarter);
                String next = GagNextWords.after(afterStarter.before(), "en").get(0);
                Selection.setSelection(editor, 0, 1);
                assertNull(GagSuggestionContext.read(input));
                assertFalse(commit(input, afterStarter, next, word -> word));
                Selection.setSelection(editor, 1);
                assertNull(GagSuggestionContext.read(input));
                Selection.setSelection(editor, editor.length());
                assertTrue(commit(input, afterStarter, next, word -> word.equals("am") ? "mmph" : word));
                assertEquals("I mmph", editor.toString());
            });
        }
    }

    @Test public void suggestionSpaceIsPendingOnlyAtTheUnchangedCursor() {
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            host.onActivity(activity -> {
                android.widget.EditText text = new android.widget.EditText(activity);
                text.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
                text.setShowSoftInputOnFocus(false);
                activity.setContentView(text);
                text.requestFocus();
                text.setText("hel");
                InputConnection editorConnection = text.onCreateInputConnection(
                        new android.view.inputmethod.EditorInfo());
                assertNotNull(editorConnection);
                AtomicReference<Runnable> queuedCommit = new AtomicReference<>();
                InputConnection input = new InputConnectionWrapper(editorConnection, false) {
                    @Override public boolean commitText(CharSequence text, int cursor) {
                        queuedCommit.set(() -> assertTrue(super.commitText(text, cursor)));
                        return true;
                    }
                };
                Editable editor = text.getText();
                Selection.setSelection(editor, 3);
                GagDeleteSuggestion.Snapshot before = GagDeleteSuggestion.editorSnapshot(input);
                assertNotNull(before);
                assertTrue(input.setComposingRegion(0, 3));
                assertTrue(input.commitText("hello", 1));
                GagSuggestionSpace pending = GagSuggestionSpace.capture(input, before, 3, "hello");
                assertNotNull(pending);
                assertEquals("hel", editor.toString());
                assertFalse(pending.matches(input));
                assertFalse(pending.cursorMoved(input, -1, -1, 3, 3));
                queuedCommit.get().run();
                assertFalse(pending.cursorMoved(input, 3, 3, 5, 5));
                assertTrue(pending.matches(input));
                assertFalse(pending.matches(editorConnection));
                assertEquals("hello", editor.toString());
                assertTrue(pending.cursorMoved(input, 5, 5, 3, 3));

                Selection.setSelection(editor, 4);
                assertTrue(pending.cursorMoved(input, 5, 5, 4, 4));
                assertTrue(pending.cursorMoved(input, 5, 5, 4, 5));
                assertFalse(pending.matches(input));
                editor.append(" ");
                Selection.setSelection(editor, 5);
                assertFalse(pending.matches(input));
                editor.replace(0, editor.length(), "world");
                Selection.setSelection(editor, 5);
                assertFalse(pending.matches(input));
                assertNull(GagSuggestionSpace.capture(input, before, 3, "hello"));
            });
        }
    }

    @Test public void nextWordPreservesPriorComposingTextAndRejectsExternalEditsAtTheSameCursor() {
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            host.onActivity(activity -> {
                BaseInputConnection input = new BaseInputConnection(new View(activity), true);
                Editable editor = input.getEditable();
                editor.append("hello ");
                Selection.setSelection(editor, 6);
                assertTrue(input.setComposingRegion(0, 5));
                GagSuggestionContext hello = GagSuggestionContext.read(input);
                assertNotNull(hello);
                assertTrue(commit(input, hello, "there", word -> word));
                assertEquals("hello there", editor.toString());

                GagSuggestionContext there = GagSuggestionContext.read(input);
                assertNotNull(there);
                editor.replace(6, 11, "again");
                assertEquals(11, Selection.getSelectionEnd(editor));
                assertFalse(commit(input, there, "how", word -> word));
                assertEquals("hello again", editor.toString());
            });
        }
    }

    private static boolean commit(BaseInputConnection input, GagSuggestionContext expected,
            String candidate, java.util.function.UnaryOperator<String> replace) {
        return expected.commit(input, candidate, replace, completed -> input.commitText(completed, 1));
    }
}
