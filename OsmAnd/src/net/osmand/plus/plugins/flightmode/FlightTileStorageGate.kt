package net.osmand.plus.plugins.flightmode

import java.util.concurrent.locks.ReentrantReadWriteLock

/** Source transfers/imports and cleanup share one gate across repository instances. */
internal object FlightTileStorageGate {
    private val gate = ReentrantReadWriteLock(true)
    fun <T> read(block: () -> T): T {
        val lock = gate.readLock()
        lock.lockInterruptibly()
        return try { block() } finally { lock.unlock() }
    }
    fun <T> write(block: () -> T): T {
        val lock = gate.writeLock()
        lock.lockInterruptibly()
        return try { block() } finally { lock.unlock() }
    }
}
