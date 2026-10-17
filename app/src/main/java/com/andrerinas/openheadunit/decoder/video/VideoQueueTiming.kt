package com.andrerinas.openheadunit.decoder.video

/** Video-worker-only accounting, separate from the shared reader's dispatch duration. */
internal class VideoQueueTiming(private val label: String = "Video queue") {
    private var startNanos: Long? = null
    private var count = 0L
    private var totalWaitNanos = 0L
    private var maxWaitNanos = 0L
    private var maxBytes = 0L

    fun onStart(receivedNanos: Long, nowNanos: Long, bytes: Long): String? {
        val start = startNanos ?: nowNanos.also { startNanos = it }
        val wait = (nowNanos - receivedNanos).coerceAtLeast(0)
        count++
        totalWaitNanos += wait
        maxWaitNanos = maxOf(maxWaitNanos, wait)
        maxBytes = maxOf(maxBytes, bytes)
        if (nowNanos - start < 5_000_000_000L) return null
        val report = "$label: messages=$count, waitAvgMs=${totalWaitNanos / count / 1_000_000}, " +
            "waitMaxMs=${maxWaitNanos / 1_000_000}, retainedMaxBytes=$maxBytes"
        startNanos = nowNanos
        count = 0
        totalWaitNanos = 0
        maxWaitNanos = 0
        maxBytes = 0
        return report
    }
}
