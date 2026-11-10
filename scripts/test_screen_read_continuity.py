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
