import net.osmand.util.PhotoLandmarkGeometry;

public class PhotoLandmarkGeometryTest {
    public static void main(String[] args) {
        for (int angle=-180; angle<=180; angle++) {
            double[] rotated=PhotoLandmarkGeometry.rotate(320,170,250,250,angle);
            double[] restored=PhotoLandmarkGeometry.rotate(rotated[0],rotated[1],250,250,-angle);
            if(Math.hypot(restored[0]-320,restored[1]-170)>1e-8) throw new AssertionError("Inverse rotation");
            double[][] markers={null,rotated,new double[]{1000,1000}};
            if(PhotoLandmarkGeometry.hit(markers,rotated[0]+2,rotated[1]+3,24)!=1)
                throw new AssertionError("Existing rotated marker must be selectable");
        }
        double[] clockwise=PhotoLandmarkGeometry.rotate(1,0,0,0,90);
        if(Math.abs(clockwise[1]-1)>1e-8) throw new AssertionError("Clockwise Android screen rotation");
        if(PhotoLandmarkGeometry.hit(new double[0][],0,0,24)!=-1) throw new AssertionError();
        System.out.println("PASS: 361 rotation/inverse/hit-test cases, clockwise gesture, empty editor");
    }
}
