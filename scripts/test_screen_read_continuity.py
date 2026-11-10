"""Read-only UI resource inventory: unknown, empty and failed must remain distinct."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


class ScreenReadContinuityTest(unittest.TestCase):
    def test_no_loading_copy_in_any_localized_ui_resource(self):
        failures = []
        for path in (ROOT / "app/src/main/res").rglob("*.xml"):
            for node in ET.parse(path).getroot().iter():
                if node.tag not in {"string", "item"}:
                    continue
                value = "".join(node.itertext())
                if re.search(r"\bloading\b", value, re.I):
                    failures.append(f"{path.relative_to(ROOT)}:{node.attrib.get('name', '?')}")
        self.assertEqual([], failures)

    def test_home_counter_read_stays_inside_the_background_job(self):
        source = (ROOT / "app/src/main/java/com/subhub/app/MainActivity.java").read_text()
        self.assertNotIn("renderAchievementsPreview(new StatsRepository", source)
        runtime = source.split("private void renderRuntimeState()", 1)[1].split(
            "private void observeAchievementInputs()", 1)[0]
        self.assertNotIn("new StatsRepository", runtime)
        read = source.split("private void renderAchievementsPreview()", 1)[1].split(
            "private static final class AchievementRead", 1)[0]
        self.assertLess(read.index('uiData.load("achievements"'), read.index("new StatsRepository"))
        self.assertIn("epoch != achievementReadEpoch", read)
        self.assertIn("lastAchievementRead < 5_000L", read)

    def test_journal_authority_precedes_pending_read_coalescing(self):
        source = (ROOT / "app/src/main/java/com/subhub/app/journal/JournalActivity.java").read_text()
        refresh = source.split("private void refresh()", 1)[1].split("private void unavailable()", 1)[0]
        self.assertLess(refresh.index("if (!authorized())"), refresh.index('data.isPending("journal-screen")'))
        revoke = source.split("private void unavailable()", 1)[1].split("private void failedRefresh()", 1)[0]
        self.assertIn('data.cancel("journal-screen")', revoke)
        self.assertIn("closePlayers()", revoke)
        self.assertIn('rendered = ""', revoke)

    def test_allowance_save_readiness_comes_from_stored_rows_not_metadata(self):
        source = (ROOT / "app/src/main/java/com/subhub/app/appmode/AppModeActivity.java").read_text()
        render = source.split("private void renderStoredAllowances", 1)[1].split("private void message", 1)[0]
        self.assertIn("displayedPackages = requested", render)
        metadata = source.split('appData.load("limits-apps"', 1)[1].split("private void renderStoredAllowances", 1)[0]
        self.assertNotIn("displayedPackages =", metadata)
        self.assertIn("metadataPackages = requested", metadata)

    def test_unknown_does_not_masquerade_as_empty_or_error(self):
        resources = {}
        for path in (ROOT / "app/src/main/res/values").glob("*.xml"):
            for node in ET.parse(path).getroot().findall("string"):
                resources[node.attrib["name"]] = "".join(node.itertext())
        for first, second in [("role_data_unknown", "role_person_unavailable"),
                              ("journal_loading", "journal_empty"),
                              ("profile_journal_loading", "journal_shared_empty"),
                              ("role_action_pending", "role_data_unknown")]:
            self.assertNotEqual(resources[first], resources[second])


if __name__ == "__main__":
    unittest.main()
