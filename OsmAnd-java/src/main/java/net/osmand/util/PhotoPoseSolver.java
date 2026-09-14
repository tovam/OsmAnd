package net.osmand.util;

/** Small dependency-free pinhole resection, initialized near the recorded camera.
 * Positions use local east/up/south kilometres; angles are radians. Principal
 * point is the image centre, pixels are square, lens distortion is not fitted.
 */
public final class PhotoPoseSolver {
    private PhotoPoseSolver() { }

    public static final class Result {
        public final double[] parameters;
        public final double[] errorsPixels;
        public final double rmsPixels;
        public final boolean weakGeometry;
        Result(double[] p, double[] errors, boolean weak) {
            parameters = p.clone(); errorsPixels = errors; weakGeometry = weak;
            double sum = 0; for (double e : errors) sum += e * e;
            rmsPixels = Math.sqrt(sum / errors.length);
        }
    }

    /** p = east km, up km, south km, yaw, pitch, roll, log(focal/imageHeight). */
    public static double[] project(double[] p, double[] point, double aspect) {
        double y = p[3], t = p[4], r = p[5];
        double sy = Math.sin(y), cy = Math.cos(y), st = Math.sin(t), ct = Math.cos(t);
        double x = point[0] - p[0], h = point[1] - p[1], z = point[2] - p[2];
        double depth = x * sy * ct + h * st - z * cy * ct;
        if (depth <= 0.00001) return null;
        double right = x * cy + z * sy;
        double up = -x * sy * st + h * ct + z * cy * st;
        double c = Math.cos(r), s = Math.sin(r), f = Math.exp(p[6]);
        return new double[]{0.5 + f * (right * c + up * s) / (depth * aspect),
                0.5 - f * (-right * s + up * c) / depth};
    }

    public static Result solve(double[][] world, double[][] image, double[] initial,
            int width, int height, boolean fitFocal) {
        return solve(world, image, initial, width, height, fitFocal, null);
    }

    // Diagnostics may also start at the full fit. Bounds and all ordinary seeds
    // still refer to the original camera, so exclusions have identical constraints.
    static Result solve(double[][] world, double[][] image, double[] initial,
            int width, int height, boolean fitFocal, double[] warmStart) {
        if (world.length < 4 || world.length != image.length || width <= 0 || height <= 0
                || initial.length != 7) throw new IllegalArgumentException("Four complete pairs required");
        for (int i = 0; i < world.length; i++) {
            if (world[i].length != 3 || image[i].length != 2) throw new IllegalArgumentException("Invalid point dimensions");
            for (double v : world[i]) if (!Double.isFinite(v)) throw new IllegalArgumentException("Invalid terrain point");
            for (double v : image[i]) if (!Double.isFinite(v) || v < 0 || v > 1) throw new IllegalArgumentException("Invalid image point");
        }
        for (double v : initial) if (!Double.isFinite(v)) throw new IllegalArgumentException("Invalid initial camera");
        if (spread(image) < 0.00002) throw new IllegalArgumentException("Image points are collinear or too clustered");
        double aspect = (double) width / height;
        double[] best = null; double bestCost = Double.POSITIVE_INFINITY;
        double[] centre = new double[3];
        for (double[] v : world) for (int j = 0; j < 3; j++) centre[j] += v[j] / world.length;
        for (int seed = 0; seed < (warmStart == null ? 9 : 10); seed++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            double[] p = initial.clone();
            if (seed == 9) {
                p = warmStart.clone();
            } else if (seed > 0) {
                double a = (seed - 1) * Math.PI / 4;
                p[0] += 2 * Math.cos(a); p[2] += 2 * Math.sin(a);
                double dx = centre[0] - p[0], dy = centre[1] - p[1], dz = centre[2] - p[2];
                p[3] = Math.atan2(dx, -dz); p[4] = Math.atan2(dy, Math.hypot(dx, dz));
            }
            p = optimize(world, image, p, initial, aspect, fitFocal);
            double cost = cost(residuals(world, image, p, aspect));
            if (cost < bestCost) { best = p; bestCost = cost; }
        }
        if (best == null || !Double.isFinite(bestCost)) throw new IllegalArgumentException("No visible, bounded camera solution");
        double[] residual = residuals(world, image, best, aspect), errors = new double[world.length];
        for (int i = 0; i < errors.length; i++) errors[i] = Math.hypot(residual[2*i], residual[2*i+1]) * height;
        double[][] j = jacobian(world, image, best, aspect, fitFocal ? 7 : 6);
        double[][] normal = normal(j);
        // Normalize columns before condition testing: metres, radians and focal
        // scale must not be compared as if they shared a unit.
        double[] scale = new double[normal.length];
        for (int k = 0; k < scale.length; k++) scale[k] = Math.sqrt(Math.max(1e-30, normal[k][k]));
        for (int k = 0; k < scale.length; k++) for (int l = 0; l < scale.length; l++) normal[k][l] /= scale[k] * scale[l];
        boolean weak = world.length == 4 || eigenRatio(normal) < 1e-6 || Math.hypot(best[0]-initial[0], best[2]-initial[2]) > 99
                || best[1] <= -0.49 || best[1] >= 29.99;
        return new Result(best, errors, weak);
    }

    private static double[] optimize(double[][] w, double[][] im, double[] p, double[] origin,
            double aspect, boolean focal) {
        double damping = 0.001;
        int n = focal ? 7 : 6;
        for (int iteration = 0; iteration < 160; iteration++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            double[] r = residuals(w, im, p, aspect);
            if (r == null) return p;
            double[][] j = jacobian(w, im, p, aspect, n), a = normal(j);
            double[] b = new double[n];
            for (int k = 0; k < n; k++) {
                for (int i = 0; i < r.length; i++) b[k] -= j[i][k] * r[i];
                a[k][k] += damping * Math.max(1e-8, a[k][k]);
            }
            double[] step = linear(a, b);
            if (step == null) break;
            double[] next = p.clone();
            for (int k = 0; k < n; k++) next[k] += step[k];
            boolean bounded = Math.hypot(next[0]-origin[0], next[2]-origin[2]) <= 100
                    && next[1] >= -0.5 && next[1] <= 30
                    && Math.abs(next[4]) < Math.PI * 0.499
                    && next[6] > Math.log(0.5 / Math.tan(Math.toRadians(85)))
                    && next[6] < Math.log(0.5 / Math.tan(Math.toRadians(0.5)));
            double nextCost = bounded ? cost(residuals(w, im, next, aspect)) : Double.POSITIVE_INFINITY;
            double current = cost(r);
            if (nextCost < current) {
                p = next; damping = Math.max(1e-10, damping / 3);
                if (current - nextCost < 1e-16) break;
            } else damping = Math.min(1e12, damping * 10);
        }
        return p;
    }

    private static double[] residuals(double[][] w, double[][] im, double[] p, double aspect) {
        double[] r = new double[w.length * 2];
        for (int i = 0; i < w.length; i++) {
            double[] uv = project(p, w[i], aspect);
            if (uv == null) return null;
            r[i*2] = (uv[0] - im[i][0]) * aspect; r[i*2+1] = uv[1] - im[i][1];
        }
        return r;
    }

    private static double[][] jacobian(double[][] w, double[][] im, double[] p, double aspect, int n) {
        double[][] j = new double[w.length*2][n];
        double[] base = residuals(w, im, p, aspect);
        for (int k = 0; k < n; k++) {
            double h = 1e-5; double[] next = p.clone(); next[k] += h;
            double[] r = residuals(w, im, next, aspect);
            if (r == null) continue;
            for (int i = 0; i < r.length; i++) j[i][k] = (r[i]-base[i])/h;
        }
        return j;
    }

    private static double cost(double[] r) {
        if (r == null) return Double.POSITIVE_INFINITY;
        double sum = 0; for (double v : r) sum += v*v; return sum;
    }
    private static double[][] normal(double[][] j) {
        int n = j[0].length; double[][] a = new double[n][n];
        for (double[] row : j) for (int k=0;k<n;k++) for (int l=0;l<n;l++) a[k][l]+=row[k]*row[l];
        return a;
    }
    private static double[] linear(double[][] a, double[] b) {
        int n = b.length;
        for (int i=0;i<n;i++) {
            int pivot=i; for (int k=i+1;k<n;k++) if (Math.abs(a[k][i])>Math.abs(a[pivot][i])) pivot=k;
            if (Math.abs(a[pivot][i])<1e-18) return null;
            double[] row=a[i]; a[i]=a[pivot]; a[pivot]=row;
            double v=b[i]; b[i]=b[pivot]; b[pivot]=v;
            v=a[i][i]; for(int k=i;k<n;k++) a[i][k]/=v; b[i]/=v;
            for(int k=0;k<n;k++) if(k!=i) {
                v=a[k][i]; for(int l=i;l<n;l++) a[k][l]-=v*a[i][l]; b[k]-=v*b[i];
            }
        }
        return b;
    }
    private static double spread(double[][] points) {
        double x=0,y=0; for(double[] p:points){x+=p[0]/points.length;y+=p[1]/points.length;}
        double xx=0,yy=0,xy=0;
        for(double[] p:points){double a=p[0]-x,b=p[1]-y;xx+=a*a;yy+=b*b;xy+=a*b;}
        return (xx*yy-xy*xy)/(points.length*points.length);
    }
    private static double eigenRatio(double[][] a) {
        int n=a.length;
        for(int iter=0;iter<100;iter++) {
            int p=0,q=1; for(int i=0;i<n;i++) for(int j=i+1;j<n;j++) if(Math.abs(a[i][j])>Math.abs(a[p][q])){p=i;q=j;}
            if(Math.abs(a[p][q])<1e-12) break;
            double theta=0.5*Math.atan2(2*a[p][q],a[q][q]-a[p][p]);
            double c=Math.cos(theta),s=Math.sin(theta),pp=a[p][p],qq=a[q][q],pq=a[p][q];
            for(int i=0;i<n;i++) if(i!=p&&i!=q){double ap=a[i][p],aq=a[i][q];a[i][p]=a[p][i]=c*ap-s*aq;a[i][q]=a[q][i]=s*ap+c*aq;}
            a[p][p]=c*c*pp-2*s*c*pq+s*s*qq;a[q][q]=s*s*pp+2*s*c*pq+c*c*qq;a[p][q]=a[q][p]=0;
        }
        double min=Double.POSITIVE_INFINITY,max=0;
        for(int i=0;i<n;i++){min=Math.min(min,a[i][i]);max=Math.max(max,a[i][i]);}
        return min/Math.max(1e-30,max);
    }
}
