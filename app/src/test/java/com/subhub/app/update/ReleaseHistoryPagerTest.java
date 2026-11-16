package com.subhub.app.update;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ReleaseHistoryPagerTest {
    @Test public void browseCachedPagesAndContinueAcrossOverlappingGitHubPages() {
        List<ReleaseHistoryItem> cache = new ArrayList<>();
        for (int patch = 1; patch <= 33; patch++) cache.add(item("0.7." + patch, false));
        ReleaseHistoryPager pager = new ReleaseHistoryPager(List.of(), "0.7.22");
        pager.update(cache, null, 0L, false);
        assertEquals(16, pager.rows().size());
        assertEquals("v0.7.33", pager.rows().get(0).tag);
        assertEquals("v0.7.18", pager.rows().get(15).tag);

        assertNull(pager.older());
        assertEquals("v0.7.17", pager.rows().get(0).tag);
        assertNull(pager.older());
        assertEquals(List.of("v0.7.1"), tags(pager));
        assertTrue(pager.hasNewer());

        ReleaseHistoryPager.Request first = pager.older();
        assertEquals(1, first.page());
        assertNull(pager.older());
        pager.finish(first, success(true, item("0.7.1", false), item("0.7.1-dev.2", true)));
        assertTrue(pager.noOlderMatches());
        assertEquals(List.of("v0.7.1"), tags(pager));
        ReleaseHistoryPager.Request second = pager.older();
        assertEquals(2, second.page());
        pager.finish(second, success(false, item("0.7.0", false), item("0.7.0-dev.1", true)));
        assertEquals(List.of("v0.7.1", "v0.7.0"), tags(pager));
        assertFalse(pager.hasOlder());
        pager.newer();
        assertEquals("v0.7.17", pager.rows().get(0).tag);
        assertTrue(pager.hasOlder());
    }

    @Test public void onlyTheExactInstalledPreviewSurvivesStableFilteringEvenWhenBackfilled() {
        String installed = "0.7.0-dev.2";
        ReleaseHistoryPager pager = new ReleaseHistoryPager(List.of(item("0.7.0", false)), installed);
        List<ReleaseHistoryItem> cache = List.of(item("0.7.0-dev.200", true), item("0.7.1", false));
        pager.update(cache, null, 0L, false);
        assertEquals(List.of("v0.7.1", "v0.7.0", "v" + installed), tags(pager));
        assertNull(pager.installed());

        ReleaseHistoryItem installedNotes = item(installed, true);
        pager.finish(pager.older(), success(false, installedNotes, item("0.7.0-dev.20", true)));
        assertEquals(installedNotes, pager.installed());
        assertEquals(List.of("v0.7.1", "v0.7.0", "v" + installed), tags(pager));
        assertFalse(pager.hasOlder());

        pager.update(cache, null, 1L, true);
        assertEquals(List.of("v0.7.1", "v0.7.0", "v0.7.0-dev.200", "v0.7.0-dev.20", "v" + installed),
                tags(pager));
        assertEquals(installedNotes, pager.installed());
    }

    @Test public void retryKeepsCursorAndStaleResponsesCannotAdvanceIt() {
        ReleaseHistoryPager pager = new ReleaseHistoryPager(List.of(), "0.7.0");
        List<ReleaseHistoryItem> original = List.of(item("0.7.1", false));
        pager.update(original, null, 0L, false);
        pager.finish(pager.older(), success(true, item("0.7.0", false)));
        ReleaseHistoryPager.Request limited = pager.older();
        assertEquals(2, limited.page());
        GitHubReleaseRepository.Failure rateLimited = GitHubReleaseRepository.Failure.RATE_LIMITED;
        pager.finish(limited, new GitHubReleaseRepository.HistoryPage(List.of(), false, rateLimited));
        assertEquals(rateLimited, pager.failure());
        assertEquals(List.of("v0.7.1", "v0.7.0"), tags(pager));
        ReleaseHistoryPager.Request retry = pager.older();
        assertEquals(2, retry.page());
        assertNull(pager.failure());

        pager.update(original, null, 1L, true);
        pager.finish(retry, success(false, item("0.6.9", false)));
        assertEquals(List.of("v0.7.1", "v0.7.0"), tags(pager));
        ReleaseHistoryPager.Request oldHead = pager.older();
        assertEquals(2, oldHead.page());
        pager.update(List.of(item("0.7.2", false), item("0.7.1", false)), null, 1L, true);
        pager.finish(oldHead, success(false, item("0.6.9", false)));
        assertEquals(List.of("v0.7.2", "v0.7.1", "v0.7.0"), tags(pager));
        ReleaseHistoryPager.Request refreshed = pager.older();
        assertEquals(1, refreshed.page());
        pager.cancel(refreshed);
        ReleaseHistoryPager.Request recreated = pager.older();
        pager.finish(refreshed, success(false, item("0.6.9", false)));
        assertTrue(pager.loading());
        assertEquals(1, recreated.page());
    }

    private static List<String> tags(ReleaseHistoryPager pager) {
        return pager.rows().stream().map(item -> item.tag).toList();
    }

    private static ReleaseHistoryItem item(String version, boolean development) {
        return new ReleaseHistoryItem(version, "v" + version, "Notes", "", "", development);
    }

    private static GitHubReleaseRepository.HistoryPage success(boolean hasNext, ReleaseHistoryItem... items) {
        return new GitHubReleaseRepository.HistoryPage(List.of(items), hasNext, null);
    }
}
