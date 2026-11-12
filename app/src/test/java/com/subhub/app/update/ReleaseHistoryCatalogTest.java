package com.subhub.app.update;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ReleaseHistoryCatalogTest {
    @Test public void liveMetadataKeepsBundledNotesWhenImmutableBodyIsBlank() {
        ReleaseHistoryItem bundled = item("0.5.2", "v0.5.2", "- Fixed timers", "", false);
        ReleaseHistoryItem live = item("0.5.2", "v0.5.2", "",
                "2026-08-26T13:19:10Z", false);
        List<ReleaseHistoryItem> merged = ReleaseHistoryCatalog.merge(
                Arrays.asList(live), Arrays.asList(bundled));
        assertEquals(1, merged.size());
        assertEquals("- Fixed timers", merged.get(0).notes);
        assertEquals("2026-08-26T13:19:10Z", merged.get(0).publishedAt);
    }

    @Test public void sortsSemanticVersionsAndRoundTripsTheCache() throws Exception {
        List<ReleaseHistoryItem> values = ReleaseHistoryCatalog.merge(Arrays.asList(
                item("0.5.3-beta.1", "v0.5.3-beta.1", "Preview", "", true),
                item("0.5.3", "v0.5.3", "Stable", "", false),
                item("0.5.2", "v0.5.2", "Older", "", false)), null);
        assertEquals("v0.5.3", values.get(0).tag);
        assertEquals("v0.5.3-beta.1", values.get(1).tag);
        List<ReleaseHistoryItem> decoded = ReleaseHistoryItem.decode(
                ReleaseHistoryItem.encode(values));
        assertEquals(3, decoded.size());
        assertTrue(decoded.get(1).prerelease);
    }

    @Test public void candidateNotesOverrideAStaleHistoryEntry() throws Exception {
        ReleaseHistoryItem stale = item("0.6.0", "v0.6.0", "Stale", "", false);
        UpdateCandidate candidate = new UpdateCandidate(manifest("0.6.0"), "Fresh", "");
        List<ReleaseHistoryItem> merged = ReleaseHistoryCatalog.withCandidate(
                Arrays.asList(stale), candidate);
        assertEquals("Fresh", merged.get(0).notes);
    }

    @Test public void cachedCandidateKeepsItsPublicationDateWhenMissingFromHistory() throws Exception {
        ReleaseHistoryItem stable = item("0.7.0", "v0.7.0", "Stable",
                "2026-11-12T16:01:59Z", false);
        UpdateCandidate candidate = new UpdateCandidate(manifest("0.7.0-dev.1"), "Fresh",
                "", true, "2026-11-12T21:00:00Z");
        List<ReleaseHistoryItem> merged = ReleaseHistoryCatalog.withCandidate(
                Arrays.asList(stable), UpdateCandidate.parse(candidate.json()));
        assertEquals("v0.7.0-dev.1", merged.get(0).tag);
        assertEquals(candidate.publishedAt, merged.get(0).publishedAt);
    }

    @Test public void releaseChronologyBeatsSemverIncludingLegacyInstalledBuildsAndReset() {
        ReleaseHistoryItem stable = item("0.7.0", "v0.7.0", "Stable",
                "2026-11-12T16:01:59Z", false);
        ReleaseHistoryItem legacy = item("0.7.0-dev.259", "v0.7.0-dev.259", "Installed",
                "2026-11-12T20:10:00+01:00", true);
        ReleaseHistoryItem reset = item("0.7.0-dev.1", "v0.7.0-dev.1", "New",
                "2026-11-12T21:00:00Z", true);
        List<ReleaseHistoryItem> merged = ReleaseHistoryCatalog.withCandidate(
                Arrays.asList(stable, legacy, reset), null);
        assertEquals(Arrays.asList(reset, legacy, stable), merged);
    }

    @Test public void dateOnlyFallbackDoesNotHideLaterSameDayPreview() {
        ReleaseHistoryItem stable = item("0.7.0", "v0.7.0", "Stable", "2026-11-12", false);
        ReleaseHistoryItem legacy = item("0.7.0-dev.259", "v0.7.0-dev.259", "Preview",
                "2026-11-12T20:00:00Z", true);
        ReleaseHistoryItem mergedStable = item("0.7.0", "v0.7.0", "Live stable",
                "2026-11-12T21:00:00Z", false);
        assertEquals(Arrays.asList(legacy, stable), ReleaseHistoryCatalog.merge(
                Arrays.asList(legacy), Arrays.asList(stable)));
        List<ReleaseHistoryItem> merged = ReleaseHistoryCatalog.merge(
                Arrays.asList(legacy, mergedStable), Arrays.asList(stable));
        assertEquals("v0.7.0", merged.get(0).tag);
        assertEquals("2026-11-12T21:00:00Z", merged.get(0).publishedAt);
    }

    private static ReleaseHistoryItem item(String version, String tag, String notes,
            String publishedAt, boolean preview) {
        return new ReleaseHistoryItem(version, tag, notes,
                "https://github.com/bozo-industries/SubHub/releases/tag/" + tag,
                publishedAt, preview);
    }

    private static UpdateManifest manifest(String version) throws Exception {
        return UpdateManifest.parse("{\"schema\":1,"
                + "\"packageName\":\"com.subhub.app\",\"versionName\":\"" + version + "\","
                + "\"versionCode\":9,\"minSdk\":26,\"tag\":\"v" + version + "\","
                + "\"releaseNotes\":\"Fresh\",\"assets\":[{"
                + "\"abi\":\"universal\",\"name\":\"SubHub-" + version + "-universal.apk\","
                + "\"url\":\"https://github.com/bozo-industries/SubHub/app.apk\","
                + "\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                + "\"size\":42}]}");
    }
}
