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
		idleAndFailedWorkersSleep();
		cancelledSameKeyMustNotPoisonResume();
		System.out.println("PASS queue: budget, reordering, useful-job retention, stale cancellation, resource disposal");
	}
	@SuppressWarnings("unchecked")
	private static void idleAndFailedWorkersSleep() throws Exception {
		try (PreparedResourceQueue<String, String> queue = new PreparedResourceQueue<>(1, 10, v -> {}, () -> {})) {
			// Only inspect threads created by this synthetic queue, never application/process state.
			java.lang.reflect.Field field = PreparedResourceQueue.class.getDeclaredField("workers");
			field.setAccessible(true);
			Thread worker = ((List<Thread>) field.get(queue)).get(0);
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (worker.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1);
			check(worker.getState() == Thread.State.WAITING, "idle worker still uses timed polling");
			AtomicInteger attempts = new AtomicInteger();
			CountDownLatch retried = new CountDownLatch(1);
			queue.reconcile(Collections.singletonList(new PreparedResourceQueue.Request<>("retry", 6, () -> {
				if (attempts.incrementAndGet() == 1) throw new IllegalStateException("synthetic failure");
				retried.countDown(); return "OK";
			})));
			check(retried.await(4, TimeUnit.SECONDS), "timed retry no longer wakes worker");
			check("OK".equals(await(queue, "retry")), "retry result missing");
			check(attempts.get() == 2, "failure caused a busy retry loop");
		}
	}
	private static void cancelledSameKeyMustNotPoisonResume() throws Exception {
		try (PreparedResourceQueue<String, String> queue = new PreparedResourceQueue<>(1, 10, v -> {}, () -> {})) {
			CountDownLatch started = new CountDownLatch(1), cancelled = new CountDownLatch(1), finish = new CountDownLatch(1);
			queue.reconcile(Collections.singletonList(new PreparedResourceQueue.Request<>("same", 6, () -> {
				started.countDown();
				try { new CountDownLatch(1).await(); } catch (InterruptedException expected) { cancelled.countDown(); }
				finish.await(); return "STALE";
			})));
			check(started.await(2, TimeUnit.SECONDS), "old job not running");
			queue.reconcile(Collections.emptyList());
			check(cancelled.await(2, TimeUnit.SECONDS), "old job not cancelled");
			queue.reconcile(Collections.singletonList(new PreparedResourceQueue.Request<>("same", 6, () -> "FRESH")));
			finish.countDown();
			check("FRESH".equals(await(queue, "same")), "cancelled work poisoned resumed demand");
		}
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
