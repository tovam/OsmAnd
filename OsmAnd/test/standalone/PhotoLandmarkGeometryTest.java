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
        System.out.println("PASS: 361 rotation/inverse cases, 1000 sub-pixel moves, anchored full-strength pinch and clockwise rotation");
    }
    private static void close(double value,double expected) {
        if(Math.abs(value-expected)>1e-8)throw new AssertionError(value+" != "+expected);
    }
}
