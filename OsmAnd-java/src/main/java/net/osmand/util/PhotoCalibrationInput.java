package net.osmand.util;

/** Input policy shared by the photo picker and its action controls. */
public final class PhotoCalibrationInput {
	private PhotoCalibrationInput() { }

	public enum Readiness { READY, IMAGE_MISSING, ASSOCIATION_MISSING, ALTITUDE_MISSING, PAIRS_MISSING }

	/** -1 disarms placement when every slot is filled; never implicitly overwrite a point. */
	public static int nextUnplaced(boolean[] placed, int after) {
		for (int offset = 1; offset <= placed.length; offset++) {
			int index = Math.floorMod(after + offset, placed.length);
			if (!placed[index]) return index;
		}
		return -1;
	}

	public static Readiness readiness(boolean imageLoaded, boolean associated,
			boolean altitudeKnown, int completePairs) {
		if (!imageLoaded) return Readiness.IMAGE_MISSING;
		if (!associated) return Readiness.ASSOCIATION_MISSING;
		if (!altitudeKnown) return Readiness.ALTITUDE_MISSING;
		return completePairs >= 5 ? Readiness.READY : Readiness.PAIRS_MISSING;
	}

	/** Photo Plus follows finger separation directly. Hublot retains its separate damped gesture. */
	public static float scalePhoto(float current, float factor) {
		if (!Float.isFinite(factor) || factor <= 0) return current;
		return Math.max(1f, Math.min(20f, current * factor));
	}
}
