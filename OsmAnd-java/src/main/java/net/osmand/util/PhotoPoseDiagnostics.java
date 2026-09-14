package net.osmand.util;

/** Leave-one-out sensitivity, not automatic outlier rejection or ground-truth accuracy. */
public final class PhotoPoseDiagnostics {
    private PhotoPoseDiagnostics() { }

    public static final class Exclusion {
        /** Null when the remaining points cannot constrain a fit. */
        public final PhotoPoseSolver.Result fit;
        /** Null when fitting failed or the excluded point projects behind the new camera. */
        public final Double excludedErrorPixels;

        private Exclusion(PhotoPoseSolver.Result fit, Double excludedErrorPixels) {
            this.fit = fit;
            this.excludedErrorPixels = excludedErrorPixels;
        }
    }

    public static Exclusion fitExcluding(double[][] world, double[][] image, double[] initial,
            int width, int height, boolean fitFocal, PhotoPoseSolver.Result full, int excluded) {
        if (world.length < 5 || image.length != world.length || full.errorsPixels.length != world.length
                || excluded < 0 || excluded >= world.length) {
            throw new IllegalArgumentException("Exclusion requires at least five complete pairs and a matching full fit");
        }
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        double[][] remainingWorld = new double[world.length - 1][];
        double[][] remainingImage = new double[image.length - 1][];
        for (int i = 0, j = 0; i < world.length; i++) {
            if (i != excluded) {
                remainingWorld[j] = world[i];
                remainingImage[j++] = image[i];
            }
        }
        PhotoPoseSolver.Result fit;
        try {
            fit = PhotoPoseSolver.solve(remainingWorld, remainingImage, initial,
                    width, height, fitFocal, full.parameters);
        } catch (IllegalArgumentException unconstrained) {
            // One degenerate subset must not discard the valid full fit or other diagnostics.
            return new Exclusion(null, null);
        }
        double[] projected = PhotoPoseSolver.project(fit.parameters, world[excluded], (double) width / height);
        Double error = projected == null ? null : Math.hypot(
                (projected[0] - image[excluded][0]) * width,
                (projected[1] - image[excluded][1]) * height);
        return new Exclusion(fit, error != null && Double.isFinite(error) ? error : null);
    }
}
