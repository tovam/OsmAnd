package net.osmand.util;

/** Display rotation never changes the canonical image coordinates used by pose estimation. */
public final class PhotoLandmarkGeometry {
    private PhotoLandmarkGeometry() { }

    public static double[] rotate(double x, double y, double cx, double cy, double degrees) {
        double a = Math.toRadians(degrees), c = Math.cos(a), s = Math.sin(a);
        return new double[] {cx + (x-cx)*c - (y-cy)*s, cy + (x-cx)*s + (y-cy)*c};
    }

    public static int hit(double[][] positions, double x, double y, double radius) {
        int closest = -1;
        double distance = radius * radius;
        for (int i = 0; i < positions.length; i++) {
            if (positions[i] == null) continue;
            double dx = positions[i][0]-x, dy = positions[i][1]-y, d = dx*dx+dy*dy;
            if (d <= distance) { closest = i; distance = d; }
        }
        return closest;
    }
}
