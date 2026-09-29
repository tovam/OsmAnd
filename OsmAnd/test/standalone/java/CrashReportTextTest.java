import net.osmand.util.CrashReportText;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Synthetic fixtures only; the caller supplies a new, empty test directory. */
public final class CrashReportTextTest {
	private static int assertions;

	public static void main(String[] args) throws Exception {
		File directory = new File(args[0]);
		require(directory.isDirectory() && directory.list().length == 0, "Fresh fixture directory");
		File fixture = new File(directory, "synthetic.txt");
		Files.write(fixture.toPath(), new byte[0]);
		require(CrashReportText.readUtf8Tail(fixture, 32).equals(""), "Empty file");
		write(fixture, "Synthetic error: échec 🛫\nline 2\n");
		require(CrashReportText.readUtf8Tail(fixture, 256).equals("Synthetic error: échec 🛫\nline 2\n"), "UTF-8 exact");
		write(fixture, "abc\n");
		require(CrashReportText.readUtf8Tail(fixture, 4).equals("abc\n"), "Exact size: no truncation marker");
		write(fixture, "old synthetic data\nLATEST\n");
		String tail = CrashReportText.readUtf8Tail(fixture, 11);
		require(tail.endsWith("LATEST\n"), "Newest complete line retained");
		require(!tail.contains("data") && tail.contains("omitted"), "Truncation explicit, partial line removed");
		write(fixture, "ééééé");
		tail = CrashReportText.readUtf8Tail(fixture, 5);
		require(tail.endsWith("éé") && !tail.contains("\ufffd"), "UTF-8 boundary inside long line");
		write(fixture, "x".repeat(400_000) + "\nlast synthetic stack trace\n");
		tail = CrashReportText.readUtf8Tail(fixture, 256 * 1024);
		require(tail.endsWith("last synthetic stack trace\n"), "Large synthetic log: newest data");
		require(tail.length() < 256 * 1024 + 100, "Large synthetic log: bounded memory output");
		try {
			CrashReportText.readUtf8Tail(new File(directory, "missing.txt"), 32);
			throw new AssertionError("Missing file must report an IO error");
		} catch (IOException expected) {
			assertions++;
		}
		try {
			CrashReportText.readUtf8Tail(fixture, 0);
			throw new AssertionError("Zero limit must be rejected");
		} catch (IllegalArgumentException expected) {
			assertions++;
		}
		System.out.println("CrashReportText: " + assertions + " checks passed");
	}

	private static void write(File file, String text) throws IOException {
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	private static void require(boolean condition, String description) {
		if (!condition) throw new AssertionError(description);
		assertions++;
	}
}
