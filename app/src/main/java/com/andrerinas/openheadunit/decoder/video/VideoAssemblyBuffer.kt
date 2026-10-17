package com.andrerinas.openheadunit.decoder.video

/**
 * Builds one Annex B access unit. detach() transfers its storage to the consumer, so the next
 * frame cannot overwrite bytes that MediaCodec's feeder has not consumed yet. Only growth
 * copies previously assembled bytes; completing a frame does not copy it again.
 */
internal class VideoAssemblyBuffer(private val pool: VideoBufferPool, private val maxBytes: Int) {
    private var storage: VideoBuffer? = null
    private var expectedSize = 0
    var size = 0
        private set

    fun append(data: ByteArray, offset: Int, length: Int): Boolean {
        require(offset >= 0 && length >= 0 && offset <= data.size - length)
        if (length > maxBytes - size) return false
        val needed = size + length
        var target = storage
        if (target == null || target.data.size < needed) {
            // Similar-sized consecutive frames should not repeat the same geometric growth
            // copies. Remember the last completed size, not the largest keyframe ever seen.
            val replacement = pool.acquire(if (target == null) maxOf(needed, expectedSize) else needed)
            target?.let {
                System.arraycopy(it.data, 0, replacement.data, 0, size)
                it.release()
            }
            target = replacement
            storage = target
        }
        System.arraycopy(data, offset, target.data, size, length)
        size = needed
        return true
    }

    fun detach(): VideoBuffer {
        val result = checkNotNull(storage)
        expectedSize = size
        storage = null
        size = 0
        return result
    }

    fun clear() {
        storage?.release()
        storage = null
        size = 0
    }
}
