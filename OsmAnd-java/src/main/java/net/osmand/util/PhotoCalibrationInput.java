package net.osmand.util;

/** Input policy shared by the photo picker and its action controls. */
public final class PhotoCalibrationInput {
	private PhotoCalibrationInput() { }

	public enum Readiness { READY, IMAGE_MISSING, ASSOCIATION_MISSING, ALTITUDE_MISSING, PAIRS_MISSING }
	public enum Action { EXPLORE, ADD, MOVE }

	/** Add creates a pair in the toolbar; either canvas then fills that explicitly selected pair. */
	public static int placementIndex(Action action, int selected, int count) {
		if ((action == Action.ADD || action == Action.MOVE) && selected >= 0 && selected < count) return selected;
		return -1;
	}

	public static int backTab(int tab) {
		return tab == 0 ? -1 : 0;
	}

	public static Readiness readiness(boolean imageLoaded, boolean associated,
			boolean altitudeKnown, int completePairs) {
		if (!imageLoaded) return Readiness.IMAGE_MISSING;
		if (!associated) return Readiness.ASSOCIATION_MISSING;
		if (!altitudeKnown) return Readiness.ALTITUDE_MISSING;
		return completePairs >= 4 ? Readiness.READY : Readiness.PAIRS_MISSING;
	}

	/** Photo Plus follows finger separation directly. Hublot retains its separate damped gesture. */
	public static float scalePhoto(float current, float factor) {
		if (!Float.isFinite(factor) || factor <= 0) return current;
		return Math.max(1f, Math.min(20f, current * factor));
	}
}
