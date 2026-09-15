package net.osmand;

/** No Android initialization, native libraries, preferences or application files. */
public class PlatformUtil {
    public static org.apache.commons.logging.Log getLog(Class<?> type) {
        return new org.apache.commons.logging.Log();
    }
}
