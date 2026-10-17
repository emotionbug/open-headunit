package com.andrerinas.openheadunit.decoder.video

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * FIFO for reference-dependent compressed frames. Both count and backing-array capacity are
 * bounded; pressure waits on the video worker, never the shared socket reader. A close wakes
 * producers and transfers every queued item to the caller for disposal. It is permanent: a
 * restarted codec gets another queue, so a late producer cannot seed its successor with old data.
 */
internal class VideoFrameQueue<T>(
    private val capacity: Int,
    private val byteCapacity: Long,
    private val storageBytes: (T) -> Int
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val items = ArrayDeque<T>()
    private var bytes = 0L
    private var closed = false
    val isClosed: Boolean get() = lock.withLock { closed }
    val size: Int get() = lock.withLock { items.size }
    val retainedBytes: Long get() = lock.withLock { bytes }

    fun offer(item: T, timeout: Long = 0, unit: TimeUnit = TimeUnit.MILLISECONDS): Boolean {
        val cost = storageBytes(item).toLong()
        require(cost >= 0)
        if (cost > byteCapacity) return false
        var nanos = unit.toNanos(timeout)
        lock.lockInterruptibly()
        try {
            while (!closed && (items.size >= capacity || cost > byteCapacity - bytes)) {
                if (nanos <= 0) return false
                nanos = changed.awaitNanos(nanos)
            }
            if (closed) return false
            items.addLast(item)
            bytes += cost
            changed.signalAll()
            return true
        } finally {
            lock.unlock()
        }
    }

    fun poll(timeout: Long = 0, unit: TimeUnit = TimeUnit.MILLISECONDS): T? {
        var nanos = unit.toNanos(timeout)
        lock.lockInterruptibly()
        try {
            while (items.isEmpty() && !closed) {
                if (nanos <= 0) return null
                nanos = changed.awaitNanos(nanos)
            }
            val item = items.pollFirst() ?: return null
            bytes -= storageBytes(item)
            changed.signalAll()
            return item
        } finally {
            lock.unlock()
        }
    }

    fun closeAndDrain(): List<T> = lock.withLock {
        closed = true
        val pending = items.toList()
        items.clear()
        bytes = 0L
        changed.signalAll()
        pending
    }
}
