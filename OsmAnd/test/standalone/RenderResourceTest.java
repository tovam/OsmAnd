import net.osmand.util.ResourceTransaction;
import java.util.*;

/** Synthetic resource ownership/failure tests; no GL context or application data. */
public final class RenderResourceTest {
	public static void main(String[] args) {
		Map<String, String> resident = new LinkedHashMap<>();
		List<String> disposed = new ArrayList<>();
		resident.put("near", "old-near");
		resident.put("far", "old-far");
		try (ResourceTransaction<String, String> transaction = new ResourceTransaction<>(resident, disposed::add)) {
			transaction.stage("near", "new-near");
			check("old-near".equals(resident.get("near")), "early publication");
			// A later allocation fails; closing must roll back, not blank the world.
		}
		check(resident.size() == 2 && "old-near".equals(resident.get("near")), "lost residents");
		check(disposed.equals(Arrays.asList("new-near")), "wrong rollback disposal");
		disposed.clear();
		try (ResourceTransaction<String, String> transaction = new ResourceTransaction<>(resident, disposed::add)) {
			transaction.stage("near", "replacement");
			transaction.commit();
		}
		check("replacement".equals(resident.get("near")), "replacement not published");
		check("old-far".equals(resident.get("far")), "unrelated tile lost");
		check(disposed.equals(Arrays.asList("old-near")), "wrong commit disposal");
		System.out.println("PASS transactional replacement: failure, success, ownership, unrelated coverage");
	}
	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
