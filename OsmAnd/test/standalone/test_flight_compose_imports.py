from pathlib import Path
import tempfile
import unittest

from check_flight_compose_imports import check_sources, missing_imports


class FlightComposeImportsTest(unittest.TestCase):
    def test_actual_build_failure_is_detected_at_the_call_site(self):
        source = """import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
fun selector() {
    DropdownMenu(expanded = true, onDismissRequest = {}) {
        DropdownMenuItem(text = { Text("Flight") }, onClick = {})
    }
}
"""
        self.assertEqual(missing_imports(source), [(5, "DropdownMenuItem")])
        fixed = "import androidx.compose.material3.DropdownMenuItem\n" + source
        self.assertEqual(missing_imports(fixed), [])

    def test_wildcard_import_applies_only_to_its_package(self):
        self.assertEqual(missing_imports("import androidx.compose.material3.*\nText(\"x\"); DropdownMenuItem()"), [])
        self.assertEqual(missing_imports("import androidx.compose.foundation.layout.*\nRow { Text(\"x\") }"), [(2, "Text")])

    def test_alias_does_not_make_the_original_symbol_available(self):
        source = "import androidx.compose.material3.Text as Label\nLabel(\"x\"); Text(\"x\")"
        self.assertEqual(missing_imports(source), [(2, "Text")])

    def test_qualified_calls_local_helpers_and_comments_are_not_missing_imports(self):
        source = '''// Text("comment")
/* DropdownMenuItem() /* Row() */ Text() */
val example = "DropdownMenuItem()"
val shader = """Text()\nRow()"""
fun Text(value: String) {}
fun test() {
    androidx.compose.material3.DropdownMenuItem()
    receiver.DropdownMenuItem()
    Text("x")
    Row {}
}
'''
        self.assertEqual(missing_imports(source), [(11, "Row")])

    def test_escape_sequences_and_newlines_do_not_hide_a_real_call(self):
        source = 'val escaped = "Text(\\\"x\\\")"\n/* comment\nText() */\nDropdownMenuItem()'
        self.assertEqual(missing_imports(source), [(4, "DropdownMenuItem")])

    def test_project_helpers_resolve_within_their_package_only(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as temporary:
            root = Path(temporary)
            (root / "Helper.kt").write_text("package synthetic\ninternal fun Text(value: String) {}")
            (root / "Ui.kt").write_text('package synthetic\nfun ui() { Text("x") }')
            _, errors = check_sources(root)
            self.assertEqual(errors, [])
            (root / "Ui.kt").write_text('package other\nfun ui() { Text("x") }')
            _, errors = check_sources(root)
            self.assertEqual([(line, symbol) for _, line, symbol in errors], [(2, "Text")])


if __name__ == "__main__":
    unittest.main()
