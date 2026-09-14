package android.os

/** Monotonic clock only; no Android services or user data in standalone tests. */
object SystemClock {
    @JvmStatic fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
}
