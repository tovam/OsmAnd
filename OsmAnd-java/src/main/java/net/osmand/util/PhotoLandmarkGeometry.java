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

    /** A rotated Mercator map keeps the same ground coordinate under the moving finger centroid.
     * Coordinates are in world fractions; finger positions are relative to the viewport centre. */
    public static double[] transformMapCenter(double worldX, double worldY,
            double previousX, double previousY, double focusX, double focusY,
            double oldWorldSize, double newWorldSize, double oldRotation, double newRotation) {
        double[] before = rotate(previousX, previousY, 0, 0, -oldRotation);
        double[] after = rotate(focusX, focusY, 0, 0, -newRotation);
        return new double[] {worldX + before[0] / oldWorldSize - after[0] / newWorldSize,
                worldY + before[1] / oldWorldSize - after[1] / newWorldSize};
    }

    /** Unrotated bounds of a screen viewport: tile planning must also cover its rotated corners. */
    public static double[] mapViewportHalfExtents(double width, double height, double degrees) {
        double a = Math.toRadians(degrees), c = Math.abs(Math.cos(a)), s = Math.abs(Math.sin(a));
        return new double[] {(width * c + height * s) / 2, (width * s + height * c) / 2};
    }
}
