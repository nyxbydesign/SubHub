package com.subhub.app.atmosphere;

import android.content.SharedPreferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GagPersonalDictionaryTest {
    @Test
    public void literalKnownWordKeepsItsSpellingAheadOfCustomLearnedAndGenericSuggestions() {
        GagPersonalDictionary.Model model = new GagPersonalDictionary.Model();
        assertTrue(model.addCustomWord("Showtime"));
        assertTrue(model.addCustomWord("shOw"));
        assertTrue(model.record("showcase", null));
        assertEquals(List.of("SHOW", "Showtime", "Showcase"),
                model.suggestions("SHOW", List.of("show", "shown"), 3));
        assertEquals(List.of("show", "Showtime", "showcase"),
                model.suggestions("show", List.of("shown"), 3));
    }

    @Test
    public void strongSpellingAlternateSharesSlotsWithTheLiteralAndPersonalCompletions() {
        GagPersonalDictionary.Model model = new GagPersonalDictionary.Model();
        assertTrue(model.addCustomWord("Hosepipe"));
        assertTrue(model.addCustomWord("Hoseclamp"));
        assertTrue(model.record("hosereel", null));
        assertEquals(List.of("Hose", "Those", "Hosepipe"),
                model.suggestions("Hose", List.of("Hose", "Those", "House"), 3));
        assertEquals(List.of("hose", "Hosepipe", "Hoseclamp"),
                model.suggestions("hose", List.of("hose", "hoseholder"), 3));
    }

    @Test
    public void unicodeWordsAreNormalizedAndPrivateLookingInputIsRejected() {
        GagPersonalDictionary.Model model = new GagPersonalDictionary.Model();
        assertTrue(model.addCustomWord("E\u0301lan"));
        assertTrue(model.record("cœur", null));
        assertFalse(model.record("person@example", null));
        assertFalse(model.record("pin2468", null));
        assertFalse(model.addCustomWord("two words"));
        assertEquals(List.of("Élan"), model.suggestions("E\u0301", List.of(), 3));
        assertEquals(List.of("cœur"), model.suggestions("cœ", List.of(), 3));
        assertTrue(model.removeCustomWord("élan"));
        assertEquals(List.of(), model.suggestions("é", List.of(), 3));
    }

    @Test
    public void openingsNeedTwoBoundedMatchingCommitsAndUseGenericFallback() {
        GagPersonalDictionary.Model model = new GagPersonalDictionary.Model();
        assertTrue(model.record("morning", "Good morning"));
        assertTrue(model.record("morning", "Very good morning everyone"));
        assertTrue(model.record("morning", "Good night"));
        assertEquals(List.of("Hello there"), model.suggestions("", List.of("Hello there"), 3));
        assertTrue(model.record("Morning", "Good Morning"));
        assertEquals(List.of("Good Morning", "Hello there"),
                model.suggestions("", List.of("good morning", "Hello there"), 3));
    }

    @Test
    public void learnedCountsOrderResultsAndOldItemsLeaveTheBoundedStore() {
        GagPersonalDictionary.Model model = new GagPersonalDictionary.Model();
        assertTrue(model.record("first", null));
        assertTrue(model.record("first", null));
        assertTrue(model.record("final", null));
        assertEquals(List.of("first", "final"), model.suggestions("fi", List.of(), 3));
        for (int i = 0; i < 256; i++)
            assertTrue(model.record("word" + (char) ('a' + i / 26) + (char) ('a' + i % 26), null));
        assertEquals(List.of(), model.suggestions("fi", List.of(), 3));
    }

    @Test
    public void ordinaryLearningCoalescesAndPersistsTheLatestWords() throws Exception {
        ScheduledThreadPoolExecutor writer = immediateWriter();
        CountDownLatch releaseWriter = new CountDownLatch(1);
        try {
            writer.execute(() -> await(releaseWriter));
            PreferencesFixture fixture = new PreferencesFixture(false);
            GagPersonalDictionary dictionary = new GagPersonalDictionary(fixture.preferences(), writer);
            dictionary.recordToken("first", null);
            dictionary.recordToken("final", null);
            assertEquals(1, writer.getQueue().size());
            releaseWriter.countDown();
            writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
            GagPersonalDictionary reopened = new GagPersonalDictionary(fixture.preferences(), writer);
            assertEquals(List.of("final", "first"), reopened.suggestions("fi", List.of(), 3));
        } finally {
            releaseWriter.countDown();
            writer.shutdownNow();
        }
    }

    @Test
    public void inFlightLearnedSnapshotCannotRestoreHistoryAfterExplicitEdits() throws Exception {
        ScheduledThreadPoolExecutor writer = immediateWriter();
        PreferencesFixture fixture = new PreferencesFixture(true);
        try {
            GagPersonalDictionary dictionary = new GagPersonalDictionary(fixture.preferences(), writer);
            dictionary.recordToken("first", null);
            assertTrue(fixture.encodingStarted.await(5, TimeUnit.SECONDS));
            // The delayed writer has already copied the learned word, but has yet to publish it.
            CompletableFuture.runAsync(() -> {
                dictionary.setLearningEnabled(false);
                dictionary.clearLearned();
                assertTrue(dictionary.addCustomWord("Showtime"));
                dictionary.recordToken("final", null);
            }).get(5, TimeUnit.SECONDS);
            fixture.releaseEncoding.countDown();
            writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
            GagPersonalDictionary reopened = new GagPersonalDictionary(fixture.preferences(), writer);
            assertFalse(reopened.isLearningEnabled());
            assertEquals(List.of("Showtime"), reopened.customWords());
            assertEquals(List.of(), reopened.suggestions("fi", List.of(), 3));
        } finally {
            fixture.releaseEncoding.countDown();
            writer.shutdownNow();
        }
    }

    private static ScheduledThreadPoolExecutor immediateWriter() {
        return new ScheduledThreadPoolExecutor(1) {
            @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
                return super.schedule(task, 0, TimeUnit.MILLISECONDS);
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException shutdown) {
            Thread.currentThread().interrupt();
            throw new AssertionError(shutdown);
        }
    }

    private static final class PreferencesFixture {
        private final Map<String, Object> committed = new LinkedHashMap<>();
        private final AtomicBoolean blockFirstEdit;
        private final CountDownLatch encodingStarted = new CountDownLatch(1);
        private final CountDownLatch releaseEncoding = new CountDownLatch(1);

        PreferencesFixture(boolean blockFirstEdit) {
            this.blockFirstEdit = new AtomicBoolean(blockFirstEdit);
        }

        SharedPreferences preferences() {
            return SharedPreferences.class.cast(Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                    (target, method, args) -> {
                        if (method.getName().equals("getAll")) {
                            synchronized (committed) { return Map.copyOf(committed); }
                        }
                        if (method.getName().equals("edit")) return edit();
                        throw new AssertionError(method.getName());
                    }));
        }

        private SharedPreferences.Editor edit() {
            if (blockFirstEdit.getAndSet(false)) {
                encodingStarted.countDown();
                await(releaseEncoding);
            }
            Map<String, Object> staged = new LinkedHashMap<>();
            return SharedPreferences.Editor.class.cast(Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                    (target, method, args) -> switch (method.getName()) {
                        case "putBoolean", "putString" -> {
                            staged.put((String) args[0], args[1]);
                            yield target;
                        }
                        case "apply" -> {
                            synchronized (committed) { committed.putAll(staged); }
                            yield null;
                        }
                        default -> throw new AssertionError(method.getName());
                    }));
        }
    }
}
