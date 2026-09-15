package net.osmand.util;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

/** Irrelevant utility boundary; tests never call the real tile-file writer. */
public class Algorithms {
    public static boolean isEmpty(String value) { return value == null || value.isEmpty(); }
    public static void streamCopy(InputStream input, OutputStream output) throws IOException {
        throw new IOException("File writes are forbidden in the downloader fixture");
    }
    public static void closeStream(Closeable stream) {
        if (stream != null) try { stream.close(); } catch (IOException ignored) { }
    }
}
