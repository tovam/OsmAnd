"""Source-contract checks for the local-only crash report flow."""

from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


APP_MODULE = Path(__file__).resolve().parents[2]
SOURCE = APP_MODULE / "src" / "net" / "osmand" / "plus"


def read_source(relative_path: str) -> str:
    return (SOURCE / relative_path).read_text(encoding="utf-8")


def method_body(source: str, signature: str) -> str:
    """Return a Java/Kotlin method body using a small brace-balanced scan."""
    start = source.index(signature)
    body_start = source.index("{", start)
    depth = 0
    for index in range(body_start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[body_start + 1:index]
    raise AssertionError(f"Unclosed method body for {signature}")


class LocalCrashReportSourceContractTest(unittest.TestCase):
    def test_both_feedback_helper_overloads_open_only_local_viewer(self):
        source = read_source("feedback/FeedbackHelper.java")
        for signature, expected in (
            ("public void sendCrashLog()", "LocalCrashReportActivity.show(app, null)"),
            ("public void sendCrashLog(@NonNull File file)", "LocalCrashReportActivity.show(app, file)"),
        ):
            with self.subTest(signature=signature):
                body = method_body(source, signature)
                self.assertIn(expected, body)
                for forbidden in ("ACTION_SEND", "EXTRA_EMAIL", "EXTRA_STREAM", "createChooser"):
                    self.assertNotIn(forbidden, body)

    def test_viewer_is_private_and_uses_text_document_export(self):
        manifest = (APP_MODULE / "AndroidManifest.xml").read_text(encoding="utf-8")
        registration = re.search(
            r'<activity\s+android:name="\.feedback\.LocalCrashReportActivity"(?P<body>[^>]*)>', manifest
        )
        self.assertIsNotNone(registration)
        self.assertIn('android:exported="false"', registration.group("body"))

        activity = read_source("feedback/LocalCrashReportActivity.kt")
        self.assertIn('ActivityResultContracts.CreateDocument("text/plain")', activity)
        self.assertNotIn("Intent.ACTION_SEND", activity)
        self.assertNotIn("Intent.createChooser", activity)

    def test_report_text_is_selectable_and_links_are_not_activated(self):
        layout = (APP_MODULE / "res" / "layout" / "local_crash_report.xml").read_text(encoding="utf-8")
        self.assertIn('android:textIsSelectable="true"', layout)
        self.assertIn('android:autoLink="none"', layout)

    def test_report_file_io_runs_on_io_dispatcher(self):
        view_model = read_source("feedback/LocalCrashReportViewModel.kt")
        load_body = method_body(view_model, "fun load(file: File?)")
        self.assertRegex(load_body, r"withContext\(Dispatchers\.IO\)\s*\{")
        self.assertIn("app.feedbackHelper.getCopyableCrashReport(file)", load_body)

        save_body = method_body(view_model, "fun save(uri: Uri)")
        self.assertRegex(save_body, r"withContext\(Dispatchers\.IO\)\s*\{")
        self.assertIn("app.contentResolver.openOutputStream(uri, \"wt\")", save_body)

    def test_report_issues_category_has_no_upstream_issue_link(self):
        help_source = read_source("help/HelpMainFragment.java")
        category = method_body(help_source, "private void createReportIssuesCategory(")
        self.assertNotIn("issues_github", category)
        self.assertNotIn("discussion_github", category)
        self.assertIn("app.getFeedbackHelper().sendCrashLog()", category)

    def test_crash_warning_and_render_error_ctas_open_local_report(self):
        dashboard = read_source("dashboard/DashErrorFragment.java")
        self.assertIn("errorBtn.setOnClickListener(v -> app.getFeedbackHelper().sendCrashLog())", dashboard)

        crash_sheet = read_source("feedback/CrashBottomSheetDialogFragment.java")
        self.assertIn("app.getFeedbackHelper().sendCrashLog()", crash_sheet)

        render_sheet = read_source("feedback/RenderInitErrorBottomSheet.java")
        self.assertIn("app.getFeedbackHelper().sendCrashLog()", render_sheet)
        for source in (dashboard, crash_sheet, render_sheet):
            self.assertIn("R.string.local_crash_report", source)
            self.assertNotIn("R.string.shared_string_send", source)

    def test_local_classes_have_no_external_destination(self):
        for name in ("LocalCrashReportActivity", "LocalCrashReportViewModel", "LocalCrashDiagnostics"):
            source = read_source(f"feedback/{name}.kt")
            for forbidden in ("https://", "mailto:", "EXTRA_EMAIL", "ACTION_SEND", "openConnection("):
                self.assertNotIn(forbidden, source)

    def test_xml_and_localized_report_resources(self):
        for path in ("AndroidManifest.xml", "res/layout/local_crash_report.xml", "res/values/local_crash_report_styles.xml"):
            ET.parse(APP_MODULE / path)
        english = ET.parse(APP_MODULE / "res/values/strings.xml").getroot()
        french = ET.parse(APP_MODULE / "res/values-fr/strings.xml").getroot()
        names = {node.attrib["name"] for node in english if node.attrib.get("name", "").startswith("local_report_")}
        names.add("local_crash_report")
        for root in (english, french):
            for name in names:
                self.assertEqual(1, len(root.findall(f"string[@name='{name}']")), name)


if __name__ == "__main__":
    unittest.main()
