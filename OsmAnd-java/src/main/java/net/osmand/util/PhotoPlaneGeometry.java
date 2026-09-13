package net.osmand.util;

/** World-space photo rectangle. No dependency on the current viewing camera or viewport. */
public final class PhotoPlaneGeometry {
	private PhotoPlaneGeometry() { }

	/**
	 * Returns TL, BL, TR, BR corners for an OpenGL triangle strip. The input basis
	 * is orthonormal, with up pointing towards the top of the calibrated image.
	 * Offsets are fractions of the saved calibration viewport, not the live viewport.
	 */
	public static float[] vertices(float[] eye, float[] forward, float[] cameraRight, float[] cameraUp,
			float distance, float verticalFovDegrees, float imageAspect, float referenceAspect,
			float scale, float offsetX, float offsetY, float clockwiseRotationDegrees) {
		double roll = Math.toRadians(-clockwiseRotationDegrees);
		float rollCos = (float) Math.cos(roll);
		float rollSin = (float) Math.sin(roll);
		float baseHalfHeight = distance * (float) Math.tan(Math.toRadians(verticalFovDegrees / 2.0));
		float halfHeight = baseHalfHeight * scale;
		float halfWidth = halfHeight * imageAspect;
		float[] positions = new float[12];
		for (int axis = 0; axis < 3; axis++) {
			// Rotate around the rectangle's centre, not around the optical axis.
			float center = eye[axis] + forward[axis] * distance
					+ cameraRight[axis] * offsetX * baseHalfHeight * referenceAspect * 2f
					- cameraUp[axis] * offsetY * baseHalfHeight * 2f;
			float right = cameraRight[axis] * rollCos + cameraUp[axis] * rollSin;
			float up = -cameraRight[axis] * rollSin + cameraUp[axis] * rollCos;
			for (int corner = 0; corner < 4; corner++) {
				float horizontalSign = corner < 2 ? -1f : 1f;
				float verticalSign = corner % 2 == 0 ? 1f : -1f;
				positions[corner * 3 + axis] = center + right * halfWidth * horizontalSign
						+ up * halfHeight * verticalSign;
			}
		}
		return positions;
	}
}
