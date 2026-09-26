package com.andrerinas.openheadunit.decoder.audio

/** AAudio is runtime-loaded and used only for the ordinary media route. Vendor/DSP and speech
 * routes keep AudioTrack's legacy stream mapping. No JNI calls are made on unsupported Android. */
internal class AAudioPcmOutput : PcmOutput {
    private var handle = NativeAAudio.open().also { check(it != 0L) { "AAudio open failed" } }
    override val name = "AAudio"
    override val capacityFrames: Int get() = NativeAAudio.stat(handle, 0)
    override val bufferFrames: Int get() = NativeAAudio.stat(handle, 1)
    override val burstFrames: Int get() = NativeAAudio.stat(handle, 2)
    override val underruns: Int get() = NativeAAudio.stat(handle, 3).coerceAtLeast(0)
    override fun setBufferFrames(frames: Int): Int = NativeAAudio.setBufferFrames(handle, frames)
    override fun start() { check(NativeAAudio.start(handle) >= 0) { "AAudio start failed" } }
    override fun pause() { check(NativeAAudio.pause(handle) >= 0) { "AAudio pause failed" } }
    override fun write(data: ShortArray, offset: Int, count: Int): Int = NativeAAudio.write(handle, data, offset, count)
    override fun close() {
        val old = handle
        handle = 0L
        if (old != 0L) NativeAAudio.close(old)
    }
}

internal object NativeAAudio {
    init { System.loadLibrary("hur_aaudio") }
    external fun open(): Long
    external fun start(handle: Long): Int
    external fun pause(handle: Long): Int
    external fun write(handle: Long, data: ShortArray, offset: Int, count: Int): Int
    external fun setBufferFrames(handle: Long, frames: Int): Int
    external fun stat(handle: Long, kind: Int): Int
    external fun close(handle: Long)
}
