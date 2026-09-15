import net.osmand.util.PhotoCalibrationInput;
import net.osmand.util.PhotoCalibrationInput.Readiness;

public class PhotoCalibrationInputTest {
	private static void require(boolean value) {
		if (!value) throw new AssertionError();
	}

	public static void main(String[] args) {
		for (int count = 0; count < 1000; count++) {
			// A canvas tap cannot create or select a slot. The global + button creates it first.
			require(PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.ADD, count, count) == -1);
			require(PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.EXPLORE, 0, count) == -1);
			if (count > 0) {
				require(PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.ADD, count - 1, count) == count - 1);
				require(PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.MOVE, count - 1, count) == count - 1);
			}
			require(PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.MOVE, count, count) == -1);
		}
		// Both panes edit the same explicitly selected pair, in either order, without auto-advancing.
		int photo = PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.ADD, 4, 5);
		int map = PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.ADD, 4, 5);
		require(photo == 4 && map == photo);
		require(PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.MOVE, -1, 5) == -1);
		for (int tab = 1; tab <= 5; tab++) require(PhotoCalibrationInput.backTab(tab) == 0);
		require(PhotoCalibrationInput.backTab(0) == -1);
		require(PhotoCalibrationInput.readiness(false, true, true, 5) == Readiness.IMAGE_MISSING);
		require(PhotoCalibrationInput.readiness(true, false, true, 5) == Readiness.ASSOCIATION_MISSING);
		require(PhotoCalibrationInput.readiness(true, true, false, 5) == Readiness.ALTITUDE_MISSING);
		require(PhotoCalibrationInput.readiness(true, true, true, 3) == Readiness.PAIRS_MISSING);
		require(PhotoCalibrationInput.readiness(true, true, true, 4) == Readiness.READY);
		require(PhotoCalibrationInput.readiness(true, true, true, 100) == Readiness.READY);
		require(PhotoCalibrationInput.readiness(true, true, true, 5) == Readiness.READY);
		require(PhotoCalibrationInput.scalePhoto(2f, 2f) == 4f);
		require(PhotoCalibrationInput.scalePhoto(4f, 0.5f) == 2f);
		require(PhotoCalibrationInput.scalePhoto(2f, Float.NaN) == 2f);
		require(PhotoCalibrationInput.scalePhoto(2f, 0f) == 2f);
		require(PhotoCalibrationInput.scalePhoto(19f, 2f) == 20f);
		System.out.println("PASS: Photo Plus shared-pair toolbar through 1000 points, no canvas selection, unified back navigation, readiness and undamped zoom");
	}
}
