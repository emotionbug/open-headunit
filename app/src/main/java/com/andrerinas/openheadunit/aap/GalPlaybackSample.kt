package com.andrerinas.openheadunit.aap

/** A receiver observation, not a phone timestamp or a claim about audible playback. */
internal data class GalPlaybackSample(
    val owner: Any,
    val audioQueueUs: Long? = null,
    val audioOverflows: Long? = null,
    val audioUnderruns: Long? = null,
    val videoFrames: Long? = null
)
