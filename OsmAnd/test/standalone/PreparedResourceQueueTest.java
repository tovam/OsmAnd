import net.osmand.util.PreparedResourceQueue;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class PreparedResourceQueueTest {
	public static void main(String[] args) throws Exception {
		List<String> disposed = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		try (PreparedResourceQueue<String, String> queue = new PreparedResourceQueue<>(2, 10, disposed::add, () -> {})) {
			PreparedResourceQueue.Request<String, String> a = new PreparedResourceQueue.Request<>("a", 6, () -> {
				calls.incrementAndGet(); started.countDown(); finish.await(); return "A";
			});
			PreparedResourceQueue.Request<String, String> b = new PreparedResourceQueue.Request<>("b", 6, () -> "B");
			queue.reconcile(Arrays.asList(a, b));
			check(started.await(2, TimeUnit.SECONDS), "worker did not start");
			queue.reconcile(Arrays.asList(b, a)); // priority changes must not restart useful A
			check(queue.reservedBytes() == 6, "running-byte budget exceeded");
			finish.countDown();
			check("A".equals(await(queue, "a")), "missing useful result");
			check("B".equals(await(queue, "b")), "blocked queue did not resume");
			check(calls.get() == 1, "useful work was restarted");
			check(queue.reservedBytes() == 0, "reservation leak");
			CountDownLatch staleStarted = new CountDownLatch(1);
			queue.reconcile(Collections.singletonList(new PreparedResourceQueue.Request<>("stale", 6, () -> {
				staleStarted.countDown();
				try { new CountDownLatch(1).await(); } catch (InterruptedException ignored) { }
				return "STALE";
			})));
			check(staleStarted.await(2, TimeUnit.SECONDS), "stale job not started");
			queue.reconcile(Collections.singletonList(b));
			check("B".equals(await(queue, "b")), "cancelled work blocked new work");
			check(queue.take("stale") == null, "stale result published");
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (!disposed.contains("STALE") && System.nanoTime() < deadline) Thread.yield();
			check(disposed.contains("STALE"), "stale result leaked");
		}
		System.out.println("PASS queue: budget, reordering, useful-job retention, stale cancellation, resource disposal");
	}
	private static String await(PreparedResourceQueue<String, String> queue, String key) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (System.nanoTime() < deadline) {
			String result = queue.take(key);
			if (result != null) return result;
			Thread.sleep(1);
		}
		throw new AssertionError("Timed out: " + key);
	}
	private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
