import java.util.Arrays;
import java.util.concurrent.CancellationException;
import net.osmand.util.PhotoPoseDiagnostics;
import net.osmand.util.PhotoPoseSolver;

/** Synthetic image/terrain pairs only. Run alongside PhotoPoseSolverTest with javac/java. */
public class PhotoPoseDiagnosticsTest {
    private static final double[] CAMERA = {0,10,0,0,0,0,Math.log(.9)};
    private static final double[][] WORLD = makeWorld();

    public static void main(String[] args) {
        for (boolean focal : new boolean[]{false, true}) {
            double[][] image = image();
            double[] initial = CAMERA.clone();
            initial[0] += 1; initial[1] -= .4; initial[2] -= .8; initial[3] += .08;
            PhotoPoseSolver.Result clean = PhotoPoseSolver.solve(WORLD, image, initial, 1500, 1000, focal);
            for (int i = 0; i < WORLD.length; i++) {
                PhotoPoseDiagnostics.Exclusion row = PhotoPoseDiagnostics.fitExcluding(
                        WORLD, image, initial, 1500, 1000, focal, clean, i);
                check(row.fit != null && row.fit.rmsPixels < .01, "Clean subset fit, index=" + i
                        + ", focal=" + focal + ", RMS=" + (row.fit == null ? "failed" : row.fit.rmsPixels));
                check(row.excludedErrorPixels != null && row.excludedErrorPixels < .01, "Clean held-out error");
            }
            // A misidentified landmark, not random camera or image noise.
            image[5][0] += .045; image[5][1] -= .035;
            PhotoPoseSolver.Result full = PhotoPoseSolver.solve(WORLD, image, initial, 1500, 1000, focal);
            double[] savedCamera = full.parameters.clone(), savedErrors = full.errorsPixels.clone();
            double[][] savedImage = Arrays.stream(image).map(double[]::clone).toArray(double[][]::new);
            double bestGain = -Double.MAX_VALUE;
            int bestIndex = -1;
            for (int i = 0; i < WORLD.length; i++) {
                PhotoPoseDiagnostics.Exclusion row = PhotoPoseDiagnostics.fitExcluding(
                        WORLD, image, initial, 1500, 1000, focal, full, i);
                check(row.fit != null, "Noisy subset fit");
                double sum = 0;
                for (int k = 0; k < full.errorsPixels.length; k++) if (k != i) sum += Math.pow(full.errorsPixels[k], 2);
                double baselineRms = Math.sqrt(sum / (WORLD.length - 1));
                check(row.fit.rmsPixels <= baselineRms + .00001, "Warm seed must not worsen retained SSE");
                double gain = baselineRms - row.fit.rmsPixels;
                if (gain > bestGain) { bestGain = gain; bestIndex = i; }
                if (i == 5) {
                    check(row.fit.rmsPixels < .01, "Removing wrong point recovers exact geometry");
                    check(row.excludedErrorPixels > 70, "Excluded bad point must disagree with clean geometry");
                }
            }
            check(bestIndex == 5, "Largest impact must identify the injected bad point, got " + bestIndex);
            check(Arrays.equals(savedCamera, full.parameters) && Arrays.equals(savedErrors, full.errorsPixels),
                    "Diagnostics changed full fit");
            check(Arrays.deepEquals(savedImage, image), "Diagnostics changed user observations");
        }
        double[][] image = image();
        PhotoPoseSolver.Result five = PhotoPoseSolver.solve(Arrays.copyOf(WORLD, 5),
                Arrays.copyOf(image, 5), CAMERA, 1500, 1000, true);
        check(PhotoPoseDiagnostics.fitExcluding(Arrays.copyOf(WORLD, 5), Arrays.copyOf(image, 5),
                CAMERA, 1500, 1000, true, five, 0).fit.weakGeometry, "Four retained points must warn");
        PhotoPoseSolver.Result four = PhotoPoseSolver.solve(Arrays.copyOf(WORLD, 4),
                Arrays.copyOf(image, 4), CAMERA, 1500, 1000, true);
        try {
            PhotoPoseDiagnostics.fitExcluding(Arrays.copyOf(WORLD, 4), Arrays.copyOf(image, 4),
                    CAMERA, 1500, 1000, true, four, 0);
            throw new AssertionError("Only three retained points accepted");
        } catch (IllegalArgumentException expected) { }
        // Full geometry is valid, but removing its only off-line point is not.
        double[][] lineImage = {{.2,.5},{.35,.5},{.55,.5},{.7,.5},{.8,.5},{.5,.2}};
        double[][] lineWorld = new double[lineImage.length][3];
        double[] frontCamera = {0,10,0,0,0,0,Math.log(.9)};
        for (int i=0;i<lineImage.length;i++) {
            double depth = 20 + i*2;
            lineWorld[i] = new double[]{(lineImage[i][0]-.5)*depth*1.5/.9,
                    10-(lineImage[i][1]-.5)*depth/.9, -depth};
        }
        PhotoPoseSolver.Result full = PhotoPoseSolver.solve(lineWorld, lineImage, frontCamera, 1500, 1000, false);
        check(PhotoPoseDiagnostics.fitExcluding(lineWorld, lineImage, frontCamera, 1500, 1000,
                false, full, 5).fit == null, "Degenerate exclusion should be reported, not thrown");
        check(PhotoPoseDiagnostics.fitExcluding(lineWorld, lineImage, frontCamera, 1500, 1000,
                false, full, 0).fit != null, "Other exclusions must still run");
        Thread.currentThread().interrupt();
        try {
            PhotoPoseDiagnostics.fitExcluding(lineWorld, lineImage, frontCamera, 1500, 1000, false, full, 0);
            throw new AssertionError("Cancellation swallowed");
        } catch (CancellationException expected) {
        } finally { Thread.interrupted(); }
        System.out.println("Leave-one-out: clean/bad landmarks, fixed/free focal, immutable full fit, weak/degenerate subsets, cancellation passed");
    }

    private static double[][] image() {
        return Arrays.stream(WORLD).map(p -> PhotoPoseSolver.project(CAMERA, p, 1.5)).toArray(double[][]::new);
    }

    private static double[][] makeWorld() {
        double[][] pixels = {{.15,.2},{.7,.18},{.35,.7},{.82,.82},{.5,.4},{.2,.85},{.9,.5}};
        double[][] world = new double[pixels.length][3];
        for (int i=0; i<pixels.length; i++) {
            double depth = 20 + i*3;
            world[i] = new double[]{(pixels[i][0]-.5)*depth*1.5/.9,
                    10-(pixels[i][1]-.5)*depth/.9, -depth};
        }
        return world;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
