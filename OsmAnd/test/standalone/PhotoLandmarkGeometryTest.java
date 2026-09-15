import net.osmand.util.PhotoLandmarkGeometry;

public class PhotoLandmarkGeometryTest {
    public static void main(String[] args) {
        for (int angle=-180; angle<=180; angle++) {
            double[] rotated=PhotoLandmarkGeometry.rotate(320,170,250,250,angle);
            double[] restored=PhotoLandmarkGeometry.rotate(rotated[0],rotated[1],250,250,-angle);
            if(Math.hypot(restored[0]-320,restored[1]-170)>1e-8) throw new AssertionError("Inverse rotation");
        }
        double[] clockwise=PhotoLandmarkGeometry.rotate(1,0,0,0,90);
        if(Math.abs(clockwise[1]-1)>1e-8) throw new AssertionError("Clockwise Android screen rotation");
        // Sub-pixel finger movement must apply immediately, without a gesture threshold or notch.
        for (int i=1;i<=1000;i++) {
            double d=i/1000.0;
            double[] p=PhotoLandmarkGeometry.transformCenter(200,300,100,100,100+d,100-d,1,0);
            close(p[0],200+d); close(p[1],300-d);
        }
        double[] pinch=PhotoLandmarkGeometry.transformCenter(200,300,100,100,100,100,2,0);
        close(pinch[0],300); close(pinch[1],500); // Full 2x, never sqrt(2).
        double[] turn=PhotoLandmarkGeometry.transformCenter(200,100,100,100,100,100,1,Math.PI/2);
        close(turn[0],100); close(turn[1],200); // Clockwise screen coordinates.
        double[] held=PhotoLandmarkGeometry.transformCenter(100,100,100,100,120,130,3,1.2);
        close(held[0],120); close(held[1],130); // The centroid remains under the fingers.
        checkMapTransforms();
        System.out.println("PASS: 361 photo rotation/inverse cases, 1000 sub-pixel moves, full-strength pinch; 7581 anchored map pan/pinch/rotation cases and rotated tile coverage");
    }

    private static void checkMapTransforms() {
        for (int bearing = -180; bearing <= 180; bearing++) {
            // Non-square half-screen panes: rotate all four screen corners back into tile space.
            double[] bounds = PhotoLandmarkGeometry.mapViewportHalfExtents(411, 297, bearing);
            for (int x : new int[] {-1, 1}) for (int y : new int[] {-1, 1}) {
                double[] corner = PhotoLandmarkGeometry.rotate(x*411/2.0, y*297/2.0, 0, 0, -bearing);
                if (Math.abs(corner[0]) > bounds[0]+1e-8 || Math.abs(corner[1]) > bounds[1]+1e-8)
                    throw new AssertionError("Missing rotated viewport corner");
            }
            for (double scale : new double[] {0.5, 1.0, 2.0}) {
                for (double turn : new double[] {-181, -15, -0.001, 0, 0.001, 20, 181}) {
                    double before = 256*Math.pow(2, 14.3), after = before*scale;
                    double px = 112.7, py = -53.8, x = 114.71, y = -55.901;
                    double oldX = 0.49999, oldY = 0.35; // Close to the date line, away from the equator.
                    double[] groundBefore = PhotoLandmarkGeometry.rotate(px, py, 0, 0, -bearing);
                    double groundX = oldX + groundBefore[0]/before;
                    double groundY = oldY + groundBefore[1]/before;
                    double[] center = PhotoLandmarkGeometry.transformMapCenter(oldX, oldY, px, py,
                            x, y, before, after, bearing, bearing+turn);
                    double[] underFinger = PhotoLandmarkGeometry.rotate(
                            (groundX-center[0])*after, (groundY-center[1])*after, 0, 0, bearing+turn);
                    close(underFinger[0], x); close(underFinger[1], y);
                    // Tapping this marker after rotation yields the original canonical ground point.
                    double[] picked = PhotoLandmarkGeometry.rotate(x, y, 0, 0, -(bearing+turn));
                    close(center[0]+picked[0]/after, groundX);
                    close(center[1]+picked[1]/after, groundY);
                    double[] restored = PhotoLandmarkGeometry.transformMapCenter(center[0], center[1],
                            x, y, px, py, after, before, bearing+turn, bearing);
                    close(restored[0], oldX); close(restored[1], oldY);
                }
            }
        }
        double[] north = PhotoLandmarkGeometry.rotate(0, -10, 0, 0, 90);
        close(north[0], 10); close(north[1], 0); // Clockwise fingers rotate north toward screen right.
        double[] noTurn = PhotoLandmarkGeometry.mapViewportHalfExtents(411, 297, 0);
        close(noTurn[0], 205.5); close(noTurn[1], 148.5);
        double[] quarterTurn = PhotoLandmarkGeometry.mapViewportHalfExtents(411, 297, 90);
        close(quarterTurn[0], 148.5); close(quarterTurn[1], 205.5);
    }
    private static void close(double value,double expected) {
        if(Math.abs(value-expected)>1e-8)throw new AssertionError(value+" != "+expected);
    }
}
