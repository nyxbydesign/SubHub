package com.subhub.app.atmosphere;

import static org.junit.Assert.*;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class GagRulesTest {
    @Test public void everyWordUsesAConfiguredReplacementAndPreservesTrailingQuotes() {
        GagRules rules = new GagRules(GagRules.Mode.ALL, 1, Set.of(), List.of("mmph", "mph"));
        assertEquals("mph", rules.complete("hello", size -> size - 1));
        assertEquals("mmph’", rules.complete("world’", size -> 0));
        assertEquals("'", rules.complete("'", size -> 0));
    }

    @Test public void intervalCountsOnlyCompletedWords() {
        GagRules rules = new GagRules(GagRules.Mode.INTERVAL, 2, Set.of(), List.of("mph"));
        assertEquals("one", rules.complete("one", size -> 0));
        assertEquals("", rules.complete("", size -> 0));
        assertEquals("mph", rules.complete("two", size -> 0));
        assertEquals("three", rules.complete("three", size -> 0));
    }

    @Test public void specificWordsMatchCaseInsensitivelyWithoutSubstringMatches() {
        GagRules rules = new GagRules(GagRules.Mode.MATCH, 1, Set.of("hello", "don't"), List.of("mph"));
        assertEquals("mph", rules.complete("HELLO", size -> 0));
        assertEquals("hellos", rules.complete("hellos", size -> 0));
        assertEquals("mph", rules.complete("Don't", size -> 0));
    }

    @Test public void fieldPolicyRejectsSensitiveEditors() {
        String ownPackage = "com.subhub.app";
        int text = InputType.TYPE_CLASS_TEXT;
        assertFalse(GagEditorPolicy.allowsReplacement(null, ownPackage));
        assertFalse(GagEditorPolicy.allowsReplacement(ownPackage, ownPackage, text, 0));
        assertFalse(GagEditorPolicy.allowsReplacement("other", ownPackage, text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING));
        int[] excluded = {InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE,
                text | InputType.TYPE_TEXT_VARIATION_PASSWORD, text | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                text | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, text | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                text | InputType.TYPE_TEXT_VARIATION_URI, text | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS};
        for (int type : excluded) assertFalse(GagEditorPolicy.allowsReplacement("other", ownPackage, type, 0));
        assertTrue(GagEditorPolicy.allowsReplacement("other", ownPackage, text | InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, 0));
        assertTrue(GagEditorPolicy.allowsReplacement("other", ownPackage, text | InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0));
    }
}
