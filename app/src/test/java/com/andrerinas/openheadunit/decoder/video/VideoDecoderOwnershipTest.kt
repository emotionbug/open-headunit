package com.andrerinas.openheadunit.decoder.video

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodec
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

class VideoDecoderOwnershipTest {
    @Test fun `real feeder uses retained payload offset and returns storage after input submission`() =
        org.mockito.Mockito.mockStatic(SystemClock::class.java).use {
            val context = mock<Context>()
            val prefs = mock<SharedPreferences>()
            whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
            whenever(prefs.getInt(any(), any())).thenAnswer { it.arguments[1] }
            val decoder = VideoDecoder(Settings(context), elapsedRealtime = { System.nanoTime() / 1_000_000 })
            fun set(name: String, value: Any?) {
                VideoDecoder::class.java.getDeclaredField(name).apply { isAccessible = true }.set(decoder, value)
            }
            val codec = mock<MediaCodec>()
            val input = ByteBuffer.allocateDirect(4096)
            set("codec", codec)
            set("inputBuffers", arrayOf(input)) // API 16-20 contract exercised by JVM SDK_INT=0
            set("running", true)
            set("startTime", System.nanoTime())
            whenever(codec.dequeueInputBuffer(any())).thenReturn(0)
            val actual = AtomicReference<ByteArray>()
            doAnswer { call ->
                assertEquals(0, call.getArgument<Int>(1))
                val size = call.getArgument<Int>(2)
                val bytes = ByteArray(size)
                input.duplicate().get(bytes)
                actual.set(bytes)
                // A queued buffer is no longer ours; even its limit must not be read afterward.
                input.limit(0)
                set("running", false)
                null
            }.whenever(codec).queueInputBuffer(any(), any(), any(), any(), any())
            val pool = VideoBufferPool()
            val storage = pool.acquire(100)
            val payload = byteArrayOf(0, 0, 0, 1, 0x65, 12, 13)
            payload.copyInto(storage.data, 10)
            decoder.decodeOwned(storage, 10, payload.size, false, "H.264", 1L)
            storage.release()
            assertEquals(0, pool.retainedBytes)
            val failure = AtomicReference<Throwable>()
            val loop = VideoDecoder::class.java.getDeclaredMethod("feedThreadLoop").apply { isAccessible = true }
            val feeder = Thread {
                try { loop.invoke(decoder) } catch (t: Throwable) { failure.set(t.cause ?: t) }
            }
            set("feedThread", feeder)
            feeder.start()
            feeder.join(3000)
            try {
                assertFalse(feeder.isAlive)
                failure.get()?.let { throw AssertionError("feeder failed", it) }
                assertArrayEquals(payload, actual.get())
                assertEquals(storage.data.size, pool.retainedBytes)
            } finally { decoder.stop("ownership test") }
        }
}
