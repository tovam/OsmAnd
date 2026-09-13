import net.osmand.util.PhotoPlaneGeometry;

/** Synthetic geometry checks, runnable with javac/java without the Android SDK. */
public final class PhotoPlaneGeometryTest {
	private static final float[] EYE = {0, 0, 0};
	private static final float[] FORWARD = {0, 0, -1};
	private static final float[] RIGHT = {1, 0, 0};
	private static final float[] UP = {0, 1, 0};

	public static void main(String[] args) {
		float[] base = rectangle(EYE, 1f, 0, 0, 0);
		// At 100 m and a 90-degree FOV the half-height is 100 m.
		close(-150, base[0]); close(100, base[1]); close(-100, base[2]);
		close(150, base[9]); close(-100, base[10]);
		System.out.println("PASS fixed world-space rectangle");

		float[] shifted = rectangle(EYE, 1f, .2f, -.1f, 0);
		float[] rotated = rectangle(EYE, 1f, .2f, -.1f, 37);
		for (int axis = 0; axis < 3; axis++) close(center(shifted, axis), center(rotated, axis));
		close(length(shifted, 0, 1), length(rotated, 0, 1));
		System.out.println("PASS continuous rotation preserves centre and dimensions");

		float[] clockwise = rectangle(EYE, 1f, 0, 0, 90);
		// The top-edge midpoint turns towards screen-right, not screen-left.
		close(100, (clockwise[0] + clockwise[6]) / 2f);
		close(0, (clockwise[1] + clockwise[7]) / 2f);
		System.out.println("PASS clockwise gesture convention");

		float[] translated = rectangle(new float[] {17000, 9000, -20000}, 1f, .2f, -.1f, 37);
		float[] originDelta = {17000, 9000, -20000};
		for (int i = 0; i < 12; i++) close(rotated[i] + originDelta[i % 3], translated[i]);
		System.out.println("PASS coordinate-origin translation");

		float[] scaled = rectangle(EYE, 2f, .2f, -.1f, 37);
		for (int axis = 0; axis < 3; axis++) close(center(rotated, axis), center(scaled, axis));
		close(2f * length(rotated, 0, 1), length(scaled, 0, 1));
		System.out.println("PASS photo zoom preserves centre");

		// A landmark on the same calibrated ray as a photo corner stays registered
		// under a full camera sweep and changing FOV. No overlay compensation needed.
		for (int yaw = -180; yaw <= 180; yaw += 3) {
			for (float fov : new float[] {25, 60, 90, 120}) {
				double angle = Math.toRadians(yaw);
				float[] right = {(float) Math.cos(angle), 0, (float) Math.sin(angle)};
				float[] forward = {(float) Math.sin(angle), 0, -(float) Math.cos(angle)};
				for (int corner = 0; corner < 4; corner++) {
					float[] point = {base[corner * 3], base[corner * 3 + 1], base[corner * 3 + 2]};
					float[] landmark = {point[0] * 70, point[1] * 70, point[2] * 70};
					float depth = dot(point, forward);
					if (depth <= 1f) continue;
					double focal = 1.0 / Math.tan(Math.toRadians(fov / 2.0));
					close((float) (dot(point, right) / depth * focal),
							(float) (dot(landmark, right) / dot(landmark, forward) * focal));
					close((float) (point[1] / depth * focal),
							(float) (landmark[1] / dot(landmark, forward) * focal));
				}
			}
		}
		System.out.println("PASS photo/landmark registration over camera yaw and FOV sweep");
		System.out.println("6/6 geometry checks passed");
	}

	private static float[] rectangle(float[] eye, float scale, float x, float y, float rotation) {
		return PhotoPlaneGeometry.vertices(eye, FORWARD, RIGHT, UP, 100, 90, 1.5f, .6f,
				scale, x, y, rotation);
	}
	private static float dot(float[] a, float[] b) { return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]; }
	private static float center(float[] a, int axis) {
		return (a[axis] + a[3 + axis] + a[6 + axis] + a[9 + axis]) / 4f;
	}
	private static float length(float[] a, int from, int to) {
		double squared = 0;
		for (int axis = 0; axis < 3; axis++) {
			double delta = a[from * 3 + axis] - a[to * 3 + axis];
			squared += delta * delta;
		}
		return (float) Math.sqrt(squared);
	}
	private static void close(float expected, float actual) {
		if (!Float.isFinite(actual) || Math.abs(expected - actual) > .01f) {
			throw new AssertionError("Expected " + expected + ", got " + actual);
		}
	}
}
