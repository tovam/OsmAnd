# Local-only crash reports

## Behavior

- Crash startup/dashboard prompts, graphics-init failures and Help's report action open a private, renderer-independent Android screen.
- The screen shows selectable plain text, Copy, Save .txt, and Close. Export uses Android's document picker; no storage permission is added.
- Nothing is uploaded or addressed to OsmAnd/GitHub. The issue-report GitHub shortcut is removed. Unrelated support/contact links elsewhere in Help are unchanged.
- Both existing `FeedbackHelper.sendCrashLog` signatures intentionally keep their upstream names but now open the local screen. This also makes the developer logcat action local-only.
- Report reading and export run on IO workers. A ViewModel keeps one snapshot across screen rotations and the document picker, with visible load/save errors and retry.

## Content and limits

- Device, Android version, package version/code and full Smart build version.
- Current OpenGL preference and renderer failure count.
- On Android 11+, up to five recent main-process crash/native-crash/ANR/initialization-failure records supplied by Android, with timestamp, reason/status, description and memory figures.
- Java exception text (or an explicitly selected developer log), limited to the last 256 KiB with a visible truncation marker.
- Native tombstone protobuf is **not decoded as text**. Native records explicitly identify that their backtrace is not included; lack of a Java stack must not be mistaken for absence of a native crash. Existing exception capture/native handler code is unchanged.

## Upstream merges

Keep new viewer/diagnostics classes and resources separate. Preserve the two local-only hooks in `FeedbackHelper`, the private manifest entry, local wording in crash/render/dashboard prompts, and the local Help report category. No change to `MapActivity`, exception capture, or native rendering is required.

## Verification

Completed: 11 synthetic JVM assertions for text extraction, 8 source/XML integration tests, and `git diff --check`. No real app/user reports were accessed.

Run `python3 OsmAnd/test/standalone/test_local_crash_report.py` for source/manifest/resource integration guards.
Compile `CrashReportText.java` and `OsmAnd/test/standalone/java/CrashReportTextTest.java` with `javac`, then run the test with a new empty fixture directory inside the project. Only synthetic text fixtures are used.

On-device acceptance (not yet executed): open report from each entrypoint; scroll/select/copy; save and cancel Save .txt; rotate during load and file selection; retry an export failure; check both themes/font scaling and Android navigation insets. No mail/browser/share chooser should open from a crash-report action. No APK build was launched for this change.
