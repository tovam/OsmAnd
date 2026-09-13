package net.osmand.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Owns candidate resources until all replacements are ready; rollback preserves residents. */
public final class ResourceTransaction<K, V> implements AutoCloseable {
	private final Map<K, V> resident;
	private final Map<K, V> staged = new LinkedHashMap<>();
	private final Consumer<V> dispose;
	private boolean finished;

	public ResourceTransaction(Map<K, V> resident, Consumer<V> dispose) {
		this.resident = resident;
		this.dispose = dispose;
	}

	public void stage(K key, V value) {
		if (finished || staged.containsKey(key)) throw new IllegalStateException("Duplicate/closed transaction");
		staged.put(key, value);
	}

	public void commit() {
		if (finished) throw new IllegalStateException("Closed transaction");
		finished = true;
		for (Map.Entry<K, V> entry : staged.entrySet()) {
			V previous = resident.put(entry.getKey(), entry.getValue());
			if (previous != null && previous != entry.getValue()) dispose.accept(previous);
		}
		staged.clear();
	}

	@Override public void close() {
		if (finished) return;
		finished = true;
		staged.values().forEach(dispose);
		staged.clear();
	}
}
