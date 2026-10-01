import net.osmand.util.PhotoPoseSolver;
public class PhotoPoseSolverTest {
    public static void main(String[] args) {
        double[][] points={{-7,1,-20},{-2,2,-23},{8,0,-30},{-4,3,-36},{6,1,-40},{2,4,-27},{-8,2,-33}};
        double[] truth={1,10,2,0.03,-0.28,0.04,Math.log(0.9)};
        double[][] image=new double[points.length][];
        for(int i=0;i<points.length;i++)image[i]=PhotoPoseSolver.project(truth,points[i],1.5);
        double[] initial=truth.clone();initial[0]+=1;initial[1]-=0.4;initial[2]-=0.8;initial[3]+=0.08;
        for(boolean focal:new boolean[]{false,true}) {
            PhotoPoseSolver.Result r=PhotoPoseSolver.solve(points,image,initial,1500,1000,focal);
            if(r.rmsPixels>0.01)throw new AssertionError("RMS "+r.rmsPixels);
            for(int k=0;k<3;k++)if(Math.abs(r.parameters[k]-truth[k])>0.001)throw new AssertionError("Camera position");
        }
        double[] wrongFocal=initial.clone();wrongFocal[6]=Math.log(0.7);
        PhotoPoseSolver.Result four=PhotoPoseSolver.solve(java.util.Arrays.copyOf(points,4),
                java.util.Arrays.copyOf(image,4),initial,1500,1000,true);
        if(four.rmsPixels>0.02 || !four.weakGeometry) throw new AssertionError("Four-point fit must work but warn about ambiguity");
        PhotoPoseSolver.Result five=PhotoPoseSolver.solve(java.util.Arrays.copyOf(points,5),
                java.util.Arrays.copyOf(image,5),wrongFocal,1500,1000,true);
        if(five.rmsPixels>0.02 || Math.abs(five.parameters[6]-truth[6])>0.001) throw new AssertionError("Five-point unknown focal fit");
        for(int i=0;i<image.length;i++){image[i][0]+=((i%3)-1)*0.0003;image[i][1]+=((i%2)-0.5)*0.0005;}
        PhotoPoseSolver.Result noisy=PhotoPoseSolver.solve(points,image,initial,1500,1000,true);
        if(noisy.rmsPixels>1)throw new AssertionError("Noisy fit");
        double[][] line={{0.1,0.1},{0.2,0.2},{0.3,0.3},{0.4,0.4},{0.5,0.5},{0.6,0.6},{0.7,0.7}};
        try { PhotoPoseSolver.solve(points,line,initial,1500,1000,true); throw new AssertionError("Collinear accepted"); }
        catch(IllegalArgumentException expected) { }
        // Telephoto cases previously saturated at 8 degrees. Ordinary photos use the same solver.
        for (double fov : new double[]{2, 5, 14.5, 60}) {
            double focal = 0.5 / Math.tan(Math.toRadians(fov / 2));
            double[] camera = {0, 10, 0, 0, 0, 0, Math.log(focal)};
            double[][] pixels = {{.15,.2},{.7,.18},{.35,.7},{.82,.82},{.5,.4},{.2,.85},{.9,.5},{.6,.65}};
            double[][] world = new double[pixels.length][3];
            for (int i=0;i<pixels.length;i++) {
                double depth = 20 + i*3;
                world[i] = new double[]{(pixels[i][0]-.5)*depth*1.5/focal,
                        10-(pixels[i][1]-.5)*depth/focal, -depth};
            }
            double[] guess = camera.clone(); guess[0] += .02; guess[1] -= .03;
            guess[6] = Math.log(0.5 / Math.tan(Math.toRadians(Math.max(fov,14) / 2)));
            PhotoPoseSolver.Result fit = PhotoPoseSolver.solve(world,pixels,guess,1500,1000,true);
            if (fit.rmsPixels > .02 || Math.abs(fit.parameters[6]-camera[6]) > .001)
                throw new AssertionError("Telephoto FOV " + fov + ": " + fit.rmsPixels);
        }
        double[] previous = noisy.parameters.clone();
        double[][] changed = java.util.Arrays.stream(image).map(double[]::clone).toArray(double[][]::new);
        changed[0][0] += .003; changed[0][1] -= .002;
        double before = error(previous, points, changed);
        long started = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            double[] preview = PhotoPoseSolver.refine(points, changed, initial, 1500, 1000, true, previous);
            if (error(preview, points, changed) > before + 1e-10) throw new AssertionError("Preview increased error");
        }
        double[] locked = PhotoPoseSolver.refine(points, changed, initial, 1500, 1000, false, previous);
        if (locked[6] != initial[6]) throw new AssertionError("Locked focal changed");
        if (!java.util.Arrays.equals(previous, noisy.parameters)) throw new AssertionError("Validated pose was mutated");
        Thread.currentThread().interrupt();
        try {
            PhotoPoseSolver.refine(points, changed, initial, 1500, 1000, true, previous);
            throw new AssertionError("Preview swallowed cancellation");
        } catch (java.util.concurrent.CancellationException expected) {
        } finally { Thread.interrupted(); }
        System.out.println("50 bounded local previews: " + ((System.nanoTime() - started) / 1_000_000.0) + " ms (synthetic JVM fixture)");
        System.out.println("Photo resection: exact, noisy, fixed/free focal and degenerate fixtures passed");
    }
    private static double error(double[] camera, double[][] world, double[][] image) {
        double cost = 0;
        for (int i = 0; i < world.length; i++) {
            double[] p = PhotoPoseSolver.project(camera, world[i], 1.5);
            if (p == null) return Double.POSITIVE_INFINITY;
            cost += Math.pow((p[0] - image[i][0]) * 1.5, 2) + Math.pow(p[1] - image[i][1], 2);
        }
        return cost;
    }
}
