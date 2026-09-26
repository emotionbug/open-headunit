package com.andrerinas.openheadunit.decoder.audio

/** Divide one output latency budget between the HAL and the JNI-to-callback queue.
 * At the floor this is two device bursts plus one producer burst, not two full buffers. */
internal object CallbackBufferSizing {
    fun deviceFrames(totalFrames: Int, burst: Int): Int {
        require(burst > 0)
        val bursts = (totalFrames.toLong() + burst - 1) / burst
        return (maxOf(2L, (bursts + 1) / 2) * burst).toInt()
    }

    fun queueFrames(totalFrames: Int, grantedDeviceFrames: Int, burst: Int): Int {
        require(burst > 0)
        val remainder = (totalFrames - grantedDeviceFrames).coerceAtLeast(burst)
        return ((remainder.toLong() + burst - 1) / burst * burst).toInt()
    }
}
