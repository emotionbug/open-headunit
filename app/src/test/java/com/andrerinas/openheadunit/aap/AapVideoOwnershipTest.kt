package com.andrerinas.openheadunit.aap

import android.content.Context
import android.content.SharedPreferences
import com.andrerinas.openheadunit.decoder.video.VideoBuffer
import com.andrerinas.openheadunit.decoder.video.VideoBufferPool
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class AapVideoOwnershipTest {
    private class Fixture {
        val pool = VideoBufferPool()
        val decoder = mock(VideoDecoder::class.java)
        val held = ArrayList<VideoBuffer>()
        val payloads = ArrayList<ByteArray>()
        val arrivals = ArrayList<Long>()
        val video: AapVideo
        init {
            val context = mock(Context::class.java)
            val prefs = mock(SharedPreferences::class.java)
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
            `when`(prefs.getString(anyString(), nullable(String::class.java))).thenAnswer { it.arguments[1] }
            `when`(prefs.getInt(anyString(), anyInt())).thenAnswer { it.arguments[1] }
            `when`(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer { it.arguments[1] }
            video = AapVideo(decoder, Settings(context)) {}
            doAnswer {
                val storage = it.arguments[0] as VideoBuffer
                val offset = it.arguments[1] as Int
                val size = it.arguments[2] as Int
                held.add(storage.retain())
                payloads.add(storage.data.copyOfRange(offset, offset + size))
                arrivals.add(it.arguments[5] as Long)
                null
            }.`when`(decoder).decodeOwned(org.mockito.kotlin.any<VideoBuffer>(), anyInt(), anyInt(), anyBoolean(), anyString(), anyLong())
        }
        fun send(flags: Int, data: ByteArray, timestamp: Long): ByteArray {
            val buffer = pool.acquire(data.size)
            data.copyInto(buffer.data)
            try { video.process(AapMessage(3, flags.toByte(), 0, 2, data.size, buffer.data), buffer, timestamp) }
            finally { buffer.release() }
            return buffer.data
        }
        fun close() {
            video.release()
            held.forEach { it.release() }
            pool.close()
        }
    }

    @Test fun `whole DATA retains the received array and excludes its timestamp`() = mockStatic(android.os.SystemClock::class.java).use {
        val f = Fixture()
        try {
            val picture = byteArrayOf(0, 0, 0, 1, 65, 22, 23)
            val source = f.send(11, ByteArray(10) + picture, 123)
            assertEquals(1, f.held.size)
            assertSame(source, f.held.single().data)
            assertArrayEquals(picture, f.payloads.single())
            assertEquals(listOf(123L), f.arrivals)
            f.send(11, ByteArray(10) + byteArrayOf(0, 0, 1, 65, 9), 456)
            assertArrayEquals(picture, source.copyOfRange(10, 10 + picture.size))
        } finally { f.close() }
    }

    @Test fun `fragments detach one access unit while next run reuses other storage`() = mockStatic(android.os.SystemClock::class.java).use {
        val f = Fixture()
        try {
            val head = byteArrayOf(0, 0, 0, 1, 65, 21)
            f.send(9, ByteArray(10) + head, 100)
            f.send(8, byteArrayOf(22, 23), 200)
            f.send(10, byteArrayOf(24, 25), 300)
            val expected = head + byteArrayOf(22, 23, 24, 25)
            assertArrayEquals(expected, f.payloads.single())
            assertEquals(listOf(100L), f.arrivals)
            f.send(9, ByteArray(10) + head, 400)
            f.send(10, byteArrayOf(99), 500)
            assertArrayEquals(expected, f.held.first().data.copyOf(expected.size))
            assertNotSame(f.held[0].data, f.held[1].data)
        } finally { f.close() }
    }
}
