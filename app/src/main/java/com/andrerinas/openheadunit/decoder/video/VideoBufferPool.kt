package com.andrerinas.openheadunit.decoder.video

import java.util.concurrent.atomic.AtomicInteger

/**
 * Storage shared by the video worker and codec feeder. A queue owns a reference, not a copy.
 * The last reader returns the array; a producer must never write to a retained buffer.
 * TLS storage is still borrowed and must be copied before the next socket read.
 */
internal class VideoBuffer private constructor(
    val data: ByteArray,
    private val recycler: (ByteArray) -> Unit
) {
    private val references = AtomicInteger(1)

    fun retain(): VideoBuffer {
        while (true) {
            val count = references.get()
            check(count > 0) { "Retaining released video storage" }
            if (references.compareAndSet(count, count + 1)) return this
        }
    }

    fun release() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0) { "Video storage released twice" }
        if (remaining == 0) recycler(data)
    }

    companion object {
        internal fun create(data: ByteArray, recycler: (ByteArray) -> Unit) = VideoBuffer(data, recycler)
    }
}

/**
 * Bounded idle storage, in size classes: small inter frames do not inherit a large keyframe's
 * allocation. Limits apply to retained capacity, not payload length. Live frames are owned by
 * their queues and cannot be evicted from this pool. Closing prevents a retiring feeder from
 * repopulating storage after its transport has ended.
 */
internal class VideoBufferPool(
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val maxBuffers: Int = 64
) {
    private val buffers = ArrayList<ByteArray>()
    private var closed = false
    var retainedBytes = 0
        @Synchronized get
        private set

    fun acquire(size: Int): VideoBuffer {
        val capacity = capacityFor(size)
        val pooled = synchronized(this) {
            val index = buffers.indexOfFirst { it.size == capacity }
            if (index >= 0) buffers.removeAt(index).also { retainedBytes -= it.size } else null
        }
        // A large keyframe allocation must not hold the pool lock needed by the socket reader.
        val data = pooled ?: ByteArray(capacity)
        return VideoBuffer.create(data, ::recycle)
    }

    @Synchronized
    private fun recycle(data: ByteArray) {
        if (closed || buffers.size >= maxBuffers || data.size > maxBytes - retainedBytes) return
        buffers.add(data)
        retainedBytes += data.size
    }

    @Synchronized
    fun close() {
        closed = true
        buffers.clear()
        retainedBytes = 0
    }

    companion object {
        fun capacityFor(size: Int): Int {
            require(size >= 0)
            var capacity = 4096
            while (capacity < size && capacity <= Int.MAX_VALUE / 2) capacity *= 2
            return maxOf(capacity, size)
        }
    }
}
