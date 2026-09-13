import net.osmand.util.DirectionalViewMatrix;

public class DirectionalViewMatrixTest {
    public static void main(String[] args) {
        float[] far = {300000f, 12000f, -200000f};
        float previous = -1;
        for (int i = 0; i < 1000; i++) {
            double angle = i * 0.00001;
            float[] direction = {(float) Math.sin(angle), 0, (float) -Math.cos(angle)};
            float[] a = DirectionalViewMatrix.create(far, direction, new float[]{0, 1, 0});
            float[] b = DirectionalViewMatrix.create(new float[3], direction, new float[]{0, 1, 0});
            for (int j = 0; j < 12; j++) if (a[j] != b[j]) throw new AssertionError("Origin affects rotation");
            if (i > 0 && a[8] <= previous) throw new AssertionError("Quantized small yaw");
            previous = a[8];
        }
        for (int i = 0; i < 1000; i++) {
            float pitch = i * 0.00001f;
            float[] a = DirectionalViewMatrix.create(far, new float[]{0, pitch, -1}, new float[]{0, 1, 0});
            if (i > 0 && a[9] <= previous) throw new AssertionError("Quantized small pitch");
            previous = a[9];
        }
        System.out.println("1000 sub-pixel yaw and pitch steps remain continuous at a distant origin");
    }
}
