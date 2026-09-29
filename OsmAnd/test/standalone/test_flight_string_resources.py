#!/usr/bin/env python3
"""Catch literal question marks parsed as Android theme references before building."""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


RESOURCE_ROOT = Path(__file__).resolve().parents[2] / "res"


def has_unescaped_theme_prefix(text):
    return text.lstrip().startswith("?")


class FlightStringResourcesTest(unittest.TestCase):
    def test_original_build_failure_is_detected(self):
        self.assertTrue(has_unescaped_theme_prefix("? = %1$d files not checked yet"))
        self.assertTrue(has_unescaped_theme_prefix("  ? = %1$d fichiers pas encore vérifiés"))

    def test_escaped_and_quoted_literals_are_allowed(self):
        self.assertFalse(has_unescaped_theme_prefix(r"\? = %1$d files not checked yet"))
        self.assertFalse(has_unescaped_theme_prefix('"? = %1$d files not checked yet"'))
        self.assertFalse(has_unescaped_theme_prefix("Remaining files?"))

    def test_flight_labels_do_not_start_with_theme_references(self):
        # These resources are user-facing labels, not theme attribute aliases.
        for locale in ("values", "values-fr"):
            strings = ET.parse(RESOURCE_ROOT / locale / "strings.xml").getroot()
            labels = {
                item.attrib["name"]: "".join(item.itertext())
                for item in strings.findall("string")
                if item.attrib.get("name", "").startswith("flight_")
            }
            with self.subTest(locale=locale):
                self.assertIn("flight_files_unchecked", labels)
                self.assertTrue(labels["flight_files_unchecked"].startswith(r"\? = "))
                self.assertEqual(labels["flight_files_unchecked"].count("%1$d"), 1)
            for name, text in labels.items():
                with self.subTest(locale=locale, name=name):
                    self.assertFalse(
                        has_unescaped_theme_prefix(text),
                        "Escape a leading literal ? as \\? so AAPT does not resolve an attribute.",
                    )


if __name__ == "__main__":
    unittest.main()
