package net.osmand.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Bounded local text extraction; never interprets binary native tombstones as text. */
public final class CrashReportText {
	private CrashReportText() {
	}

	public static String readUtf8Tail(File file, int maxBytes) throws IOException {
		if (maxBytes <= 0) {
			throw new IllegalArgumentException("A positive report size limit is required");
		}
		try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
			long length = input.length();
			long offset = Math.max(0, length - maxBytes);
			byte[] data = new byte[(int) (length - offset)];
			input.seek(offset);
			input.readFully(data);
			int start = 0;
			if (offset > 0) {
				// Avoid starting with a partial UTF-8 character when a single line exceeds the limit.
				while (start < data.length && (data[start] & 0xc0) == 0x80) {
					start++;
				}
				for (int i = start; i < data.length - 1; i++) {
					if (data[i] == '\n') {
						start = i + 1;
						break;
					}
				}
			}
			String text = new String(data, start, data.length - start, StandardCharsets.UTF_8);
			return offset > 0 ? "[… earlier crash entries omitted …]\n" + text : text;
		}
	}
}
