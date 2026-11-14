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
import androidx.test.core.app.ActivityScenario;
import com.subhub.app.help.HelpActivity;
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
                assertEquals("I ", editor.toString());
                assertFalse(commit(input, empty, starter, word -> word));

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
                assertEquals("I mmph ", editor.toString());
            });
        }
    }

    @Test public void suggestionSpaceIsOwnedOnlyAtTheUnchangedCursor() {
        try (ActivityScenario<HelpActivity> host = ActivityScenario.launch(HelpActivity.class)) {
            host.onActivity(activity -> {
                BaseInputConnection input = new BaseInputConnection(new View(activity), true);
                Editable editor = input.getEditable();
                editor.append("hel");
                Selection.setSelection(editor, 3);
                GagDeleteSuggestion.Snapshot before = GagDeleteSuggestion.editorSnapshot(input);
                assertNotNull(before);
                assertTrue(input.setComposingRegion(0, 3));
                assertTrue(input.commitText("hello ", 1));
                GagSuggestionSpace owned = GagSuggestionSpace.capture(input, before, 3, "hello ");
                assertNotNull(owned);
                assertTrue(owned.atSelection(input, 6, 6));
                owned.removeBefore(input, '(');
                assertEquals("hello", editor.toString());

                editor.append("  ");
                Selection.setSelection(editor, 7);
                assertFalse(owned.atSelection(input, 7, 7));
                owned.removeBefore(input, ',');
                assertEquals("hello  ", editor.toString());
            });
        }
    }

    private static boolean commit(BaseInputConnection input, GagSuggestionContext expected,
            String candidate, java.util.function.UnaryOperator<String> replace) {
        return expected.commit(input, candidate, replace, completed -> input.commitText(completed + " ", 1));
    }
}
