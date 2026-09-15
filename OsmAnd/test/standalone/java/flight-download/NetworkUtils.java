package net.osmand.osm.io;

import java.net.HttpURLConnection;
import java.io.IOException;

/** Fail closed: standalone checks must never connect to an actual tile server. */
public class NetworkUtils {
    public static HttpURLConnection getHttpURLConnection(String url) throws IOException {
        throw new IOException("Networking is forbidden in the standalone fixture");
    }
}
