package net.osmand.util;

/** View matrix without subtracting two large, nearly equal float positions. */
public final class DirectionalViewMatrix {
    private DirectionalViewMatrix() { }

    public static float[] create(float[] eye, float[] forward, float[] up) {
        double[] f = normalize(forward[0], forward[1], forward[2]);
        double[] s = normalize(f[1] * up[2] - f[2] * up[1],
                f[2] * up[0] - f[0] * up[2], f[0] * up[1] - f[1] * up[0]);
        double[] u = {s[1] * f[2] - s[2] * f[1], s[2] * f[0] - s[0] * f[2],
                s[0] * f[1] - s[1] * f[0]};
        return new float[] {
                (float) s[0], (float) u[0], (float) -f[0], 0,
                (float) s[1], (float) u[1], (float) -f[1], 0,
                (float) s[2], (float) u[2], (float) -f[2], 0,
                (float) -dot(s, eye), (float) -dot(u, eye), (float) dot(f, eye), 1
        };
    }

    private static double dot(double[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] normalize(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (!(length > 1e-12) || !Double.isFinite(length)) {
            throw new IllegalArgumentException("Invalid camera basis");
        }
        return new double[] {x / length, y / length, z / length};
    }
}
