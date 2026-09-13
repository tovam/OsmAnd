import net.osmand.util.PhotoCalibrationInput;
import net.osmand.util.PhotoCalibrationInput.Readiness;

public class PhotoCalibrationInputTest {
	private static void require(boolean value) {
		if (!value) throw new AssertionError();
	}

	public static void main(String[] args) {
		for (int count = 0; count < 1000; count++) {
			require(PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.ADD, 0, count, true) == count);
			require(PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.ADD, 0, count, false) == -1);
			require(PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.EXPLORE, 0, count, true) == -1);
			if (count > 0) {
				require(PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.MOVE, count - 1, count, true) == count - 1);
				require(PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.MOVE, count - 1, count, false) == count - 1);
			}
			require(PhotoCalibrationInput.placementIndex(PhotoCalibrationInput.Action.MOVE, count, count, true) == -1);
		}
		require(PhotoCalibrationInput.backTab(1) == 0); // Satellite back returns to photo, not gallery.
		require(PhotoCalibrationInput.backTab(2) == 1);
		require(PhotoCalibrationInput.backTab(3) == 1);
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
		System.out.println("Photo Plus: explicit toolbar actions through 1000 points, back navigation, readiness and undamped zoom passed");
	}
}
