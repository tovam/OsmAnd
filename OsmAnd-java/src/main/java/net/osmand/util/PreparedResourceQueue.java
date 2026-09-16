package net.osmand.util;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Persistent, reprioritizable preparation queue with bounded running + ready bytes. */
public final class PreparedResourceQueue<K, V> implements AutoCloseable {
	public static final class Request<K, V> {
		public final K key;
		/** Upper bound reserved before calling the loader, including temporary buffers. */
		public final long bytes;
		public final Callable<V> load;
		public Request(K key, long bytes, Callable<V> load) {
			if (bytes <= 0) throw new IllegalArgumentException("Positive reservation required");
			this.key = key; this.bytes = bytes; this.load = load;
		}
	}
	private static final class Work<K, V> {
		final Request<K, V> request;
		Thread thread;
		boolean cancelled;
		Work(Request<K, V> request) { this.request = request; }
	}
	private final long budget;
	private final Consumer<V> dispose;
	private final Runnable changed;
	private final LinkedHashMap<K, Request<K, V>> wanted = new LinkedHashMap<>();
	private final Map<K, Work<K, V>> running = new HashMap<>();
	private final Map<K, V> ready = new HashMap<>();
	private final Map<K, Long> reservations = new HashMap<>();
	private final Map<K, Long> retryAfter = new HashMap<>();
	private final List<Thread> workers = new ArrayList<>();
	private long reserved;
	private long failures;
	private boolean closed;

	public PreparedResourceQueue(int count, long budget, Consumer<V> dispose, Runnable changed) {
		if (count < 1 || budget < 1) throw new IllegalArgumentException();
		this.budget = budget; this.dispose = dispose; this.changed = changed;
		for (int i = 0; i < count; i++) {
			Thread thread = new Thread(this::work, "flight-prepare-" + i);
			thread.setDaemon(true);
			workers.add(thread);
			thread.start();
		}
	}

	/** Input order is current priority. Useful in-flight and ready results survive reconciliation. */
	public synchronized void reconcile(List<Request<K, V>> requests) {
		if (closed) return;
		wanted.clear();
		for (Request<K, V> request : requests) {
			if (request.bytes > budget) throw new IllegalArgumentException("Resource exceeds staging budget");
			wanted.put(request.key, request);
		}
		Iterator<Map.Entry<K, V>> iterator = ready.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<K, V> entry = iterator.next();
			if (!wanted.containsKey(entry.getKey())) {
				dispose.accept(entry.getValue());
				release(entry.getKey()); iterator.remove();
			}
		}
		for (Map.Entry<K, Work<K, V>> entry : running.entrySet()) {
			if (!wanted.containsKey(entry.getKey())) {
				entry.getValue().cancelled = true;
				entry.getValue().thread.interrupt();
			}
		}
		retryAfter.keySet().retainAll(wanted.keySet());
		notifyAll();
	}

	/** Nonblocking transfer of ownership to the render thread. */
	public synchronized V take(K key) {
		V value = ready.remove(key);
		if (value != null) {
			wanted.remove(key); release(key); notifyAll();
		}
		return value;
	}
	public synchronized long reservedBytes() { return reserved; }
	public synchronized int pendingCount() { return wanted.size(); }
	public synchronized long failureCount() { return failures; }
	public synchronized boolean isClosed() { return closed; }
	public synchronized boolean isReady(K key) { return ready.containsKey(key); }

	private void work() {
		while (true) {
			Work<K, V> job;
			synchronized (this) {
				while ((job = next()) == null && !closed) {
					try {
						long retryMillis = nextRetryWaitMillis();
						if (retryMillis > 0) wait(retryMillis); else wait();
					} catch (InterruptedException ignored) { /* Reconcile cancellation. */ }
				}
				if (closed) return;
			}
			V value = null;
			boolean failed = false;
			try { value = job.request.load.call(); failed = value == null; }
			catch (Exception | OutOfMemoryError error) { failed = true; }
			Thread.interrupted();
			synchronized (this) {
				running.remove(job.request.key);
				if (closed || job.cancelled || !wanted.containsKey(job.request.key) || failed) {
					if (value != null) dispose.accept(value);
					release(job.request.key);
					if (!closed && !job.cancelled && wanted.containsKey(job.request.key) && failed) {
						failures++;
						retryAfter.put(job.request.key, System.nanoTime() + 2_000_000_000L);
					}
				} else ready.put(job.request.key, value);
				notifyAll();
			}
			changed.run();
		}
	}
	/** Idle/budget-blocked workers sleep until notified; only failed work needs a timed retry. */
	private long nextRetryWaitMillis() {
		long now = System.nanoTime();
		long earliest = Long.MAX_VALUE;
		for (Request<K, V> request : wanted.values()) {
			if (running.containsKey(request.key) || ready.containsKey(request.key) || request.bytes > budget - reserved) continue;
			Long at = retryAfter.get(request.key);
			if (at != null) earliest = Math.min(earliest, Math.max(1L, at - now));
		}
		return earliest == Long.MAX_VALUE ? 0 : Math.max(1L, (earliest + 999_999L) / 1_000_000L);
	}
	private Work<K, V> next() {
		if (closed) return null;
		long now = System.nanoTime();
		for (Request<K, V> request : wanted.values()) {
			if (running.containsKey(request.key) || ready.containsKey(request.key)
					|| retryAfter.getOrDefault(request.key, 0L) > now || request.bytes > budget - reserved) continue;
			Work<K, V> job = new Work<>(request);
			job.thread = Thread.currentThread();
			running.put(request.key, job);
			reservations.put(request.key, request.bytes); reserved += request.bytes;
			return job;
		}
		return null;
	}
	private void release(K key) {
		Long bytes = reservations.remove(key);
		if (bytes != null) reserved -= bytes;
	}
	@Override public synchronized void close() {
		if (closed) return;
		closed = true;
		wanted.clear();
		for (Map.Entry<K, V> entry : ready.entrySet()) {
			dispose.accept(entry.getValue()); release(entry.getKey());
		}
		ready.clear(); retryAfter.clear();
		for (Thread worker : workers) worker.interrupt();
		notifyAll();
	}
}
