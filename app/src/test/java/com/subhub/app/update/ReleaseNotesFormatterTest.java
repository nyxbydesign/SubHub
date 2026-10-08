package com.subhub.app.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReleaseNotesFormatterTest {
    @Test public void extractsChangesWithoutApkGuidanceFromLegacyReleaseBody() {
        String body = "## Choose your APK\n\nUniversal works everywhere.\n\n"
                + "## Changes\n\n### Fixed\n\n- Installer opens\n\n"
                + "**Full Changelog**: https://example.invalid";
        assertEquals("### Fixed\n\n- Installer opens",
                ReleaseNotesFormatter.changelogOnly(body));
    }

    @Test public void stopsBeforeDownloadSectionInNewReleaseBody() {
        String body = "## What’s new in SubHub 0.6.0\n\n### New\n\n- Better Home\n\n"
                + "## Choose your APK\n\nUniversal works everywhere.";
        String notes = ReleaseNotesFormatter.changelogOnly(body);
        assertTrue(notes.contains("Better Home"));
        assertFalse(notes.contains("Universal"));
    }

    @Test public void rendersSimpleReadableText() {
        assertEquals("Fixed\n\n" + ReleaseNotesFormatter.BULLET + "Installer opens",
                ReleaseNotesFormatter.forDisplay("### Fixed\n\n- **Installer opens**", "fallback"));
        assertEquals("fallback", ReleaseNotesFormatter.forDisplay("", "fallback"));
        assertEquals("New\n\n" + ReleaseNotesFormatter.BULLET + "Better Home",
                ReleaseNotesFormatter.forDisplay(
                        "## What’s new in SubHub 0.6.0\n\n### New\n\n- Better Home", "fallback"));
    }

    @Test public void keepsBulletAndHeadingStructureWithoutRawMarkdown() {
        java.util.List<ReleaseNotesFormatter.DisplayLine> lines = ReleaseNotesFormatter.displayLines(
                "### Fixed\n\n- **Installer opens** — [Details](https://example.invalid) and `code`\n"
                        + "* Other change\n+ Third change", "fallback");
        assertTrue(lines.get(0).heading);
        assertFalse(lines.get(0).bullet);
        assertTrue(lines.get(2).bullet);
        assertEquals("Installer opens — Details and code", lines.get(2).text);
        assertEquals("Installer opens".length(), lines.get(2).boldTitleLength);
        assertTrue(lines.get(3).bullet);
        assertTrue(lines.get(4).bullet);
    }

    @Test public void emptyMarkdownFallsBackAndLongNotesStayBounded() {
        assertEquals("fallback", ReleaseNotesFormatter.forDisplay("**``**", "fallback"));
        String display = ReleaseNotesFormatter.forDisplay("- " + "word ".repeat(1000), "fallback");
        assertEquals(4001, display.length());
        assertTrue(display.startsWith(ReleaseNotesFormatter.BULLET));
        assertTrue(display.endsWith("…"));
    }
}
