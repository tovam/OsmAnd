package net.osmand.util;

/** Display rotation never changes the canonical image coordinates used by pose estimation. */
public final class PhotoLandmarkGeometry {
    private PhotoLandmarkGeometry() { }

    public static double[] rotate(double x, double y, double cx, double cy, double degrees) {
        double a = Math.toRadians(degrees), c = Math.cos(a), s = Math.sin(a);
        return new double[] {cx + (x-cx)*c - (y-cy)*s, cy + (x-cx)*s + (y-cy)*c};
    }

    /** Continuous screen-space pan/pinch/rotation, anchored to the moving finger centroid. */
    public static double[] transformCenter(double x, double y, double previousX, double previousY,
            double focusX, double focusY, double scale, double radians) {
        double dx = x - previousX, dy = y - previousY;
        double c = Math.cos(radians), s = Math.sin(radians);
        return new double[] {focusX + (dx * c - dy * s) * scale,
                focusY + (dx * s + dy * c) * scale};
    }

}
