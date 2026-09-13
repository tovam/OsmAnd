import net.osmand.util.PhotoCalibrationInput;
import net.osmand.util.PhotoCalibrationInput.Readiness;

public class PhotoCalibrationInputTest {
	private static void require(boolean value) {
		if (!value) throw new AssertionError();
	}

	public static void main(String[] args) {
		boolean[] photo = new boolean[5], map = new boolean[5];
		for (boolean[] surface : new boolean[][] {photo, map}) {
			int selected = PhotoCalibrationInput.nextUnplaced(surface, -1);
			for (int tap = 0; tap < 5; tap++) {
				require(selected == tap);
				surface[selected] = true;
				selected = PhotoCalibrationInput.nextUnplaced(surface, selected);
			}
			require(selected == -1); // Sixth click cannot overwrite the last point.
		}
		photo[1] = false;
		require(PhotoCalibrationInput.nextUnplaced(photo, 4) == 1);
		require(PhotoCalibrationInput.nextUnplaced(new boolean[] {true, false, true, false}, 1) == 3);
		require(PhotoCalibrationInput.nextUnplaced(new boolean[0], -1) == -1);
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
		System.out.println("Photo Plus: sequential independent landmarks, disarming, readiness and undamped zoom passed");
	}
}
