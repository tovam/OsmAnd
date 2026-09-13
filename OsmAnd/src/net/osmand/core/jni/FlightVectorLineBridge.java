package net.osmand.core.jni;

/**
 * Opts a flight line into the fork's native tube mesh. This package grants access to SWIG's
 * protected pointer accessor without reflection or replacing the upstream Java wrapper AAR.
 */
public final class FlightVectorLineBridge {
    private FlightVectorLineBridge() {
    }

    public static void enableTube(VectorLine line) {
        // Passing the object as well keeps its native shared_ptr alive throughout the JNI call.
        enableTubeNative(VectorLine.getCPtr(line), line);
    }

    private static native void enableTubeNative(long pointer, VectorLine keepAlive);
}
