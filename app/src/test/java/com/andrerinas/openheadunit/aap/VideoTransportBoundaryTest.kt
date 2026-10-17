package com.andrerinas.openheadunit.aap

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.decoder.video.VideoBuffer
import com.andrerinas.openheadunit.decoder.video.VideoBufferPool
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.decoder.video.VideoQueueTiming
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class VideoTransportBoundaryTest {
    @Test fun `every split including a bare four byte start code reaches the decoder once`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
        val context = mock<Context>()
        val prefs = mock<SharedPreferences>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.getInt(any(), any())).thenAnswer { it.arguments[1] }
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { it.arguments[1] }
        for (type in 0..1) {
            val header = if (type == 0) byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 7) else byteArrayOf(0, 1)
            val nal = byteArrayOf(0, 0, 0, 1, 0x65, 0x12, 0x34, 0x56)
            val bytes = header + nal
            for (split in 1 until bytes.size) {
                val decoder = mock<VideoDecoder>()
                val decoded = mutableListOf<ByteArray>()
                doAnswer { call ->
                    val data = call.arguments[0] as ByteArray
                    val offset = call.arguments[1] as Int
                    decoded.add(data.copyOfRange(offset, offset + (call.arguments[2] as Int)))
                    null
                }.whenever(decoder).decode(any<ByteArray>(), any(), any(), any(), any())
                doAnswer { call ->
                    val storage = call.getArgument<VideoBuffer>(0)
                    val offset = call.getArgument<Int>(1)
                    decoded.add(storage.data.copyOfRange(offset, offset + call.getArgument<Int>(2)))
                    null
                }.whenever(decoder).decodeOwned(any(), any(), any(), any(), any(), any())
                val video = AapVideo(decoder, Settings(context)) { fail("healthy split $split requested recovery") }
                val reassembler = AapMessageReassembler()
                var acks = 0
                listOf(9 to bytes.copyOfRange(0, split), 10 to bytes.copyOfRange(split, bytes.size)).forEach { (flags, data) ->
                    val fragment = AapMessage(Channel.ID_VID, flags.toByte(), if (flags == 9 && data.size >= 2) type else -1,
                        if (flags == 9) 2 else 0, data.size, data)
                    reassembler.accept(fragment, if (flags == 9) bytes.size else 0)?.let {
                        if (AapMessageFraming.completesMediaData(it.type, it.flags.toInt())) acks++
                        video.process(it)
                    }
                }
                assertEquals("type=$type split=$split", 1, decoded.size)
                assertArrayEquals(nal, decoded.single())
                assertEquals(if (type == 0) 1 else 0, acks)
                verify(decoder, never()).noteStreamCorrupted(any())
            }
        }
    }

    @Test fun `delayed video completion acknowledges the session captured on the poll thread`() {
        val transport = mock<AapTransport>(defaultAnswer = org.mockito.Mockito.CALLS_REAL_METHODS)
        val video = mock<AapVideo>()
        val handler = mock<Handler>()
        val jobs = mutableListOf<Runnable>()
        whenever(handler.post(any())).thenAnswer { jobs.add(it.arguments[0] as Runnable); true }
        fun field(name: String, value: Any) = AapTransport::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(transport, value)
        field("aapVideo", video)
        field("videoHandler", handler)
        val backlog = AtomicInteger()
        val queuedBytes = AtomicLong()
        val pool = VideoBufferPool()
        field("videoBacklog", backlog)
        field("videoQueuedBytes", queuedBytes)
        field("videoQueueTiming", VideoQueueTiming())
        field("videoBufferPool", pool)
        whenever(video.isPayload(any())).thenReturn(true)
        doReturn(101).whenever(transport).getSessionId(Channel.ID_VID)
        doNothing().whenever(transport).sendMediaAck(any(), any())
        val data = ByteArray(16) { it.toByte() }
        val expected = data.copyOf()
        var retained: VideoBuffer? = null
        doAnswer { call ->
            val message = call.getArgument<AapMessage>(0)
            val storage = call.getArgument<VideoBuffer>(1)
            assertSame(storage.data, message.data)
            assertEquals(expected.size, message.size)
            assertArrayEquals(expected, message.data.copyOf(message.size))
            assertEquals(1, backlog.get())
            assertEquals(storage.data.size.toLong(), queuedBytes.get())
            retained = storage.retain() // Simulate the asynchronous codec feeder.
            true
        }.whenever(video).process(any(), any(), any())
        transport.dispatchVideo(AapMessage(Channel.ID_VID, 11, 0, 2, data.size, data))
        data.fill(99) // The next TLS read may overwrite its borrowed storage immediately.
        verify(transport, never()).sendMediaAck(any(), any())
        doReturn(202).whenever(transport).getSessionId(Channel.ID_VID)
        jobs.single().run()
        inOrder(video, transport) {
            verify(video).process(any(), any(), any())
            verify(transport).sendMediaAck(Channel.ID_VID, 101)
        }
        verify(transport, never()).sendMediaAck(Channel.ID_VID, 202)
        assertEquals(0, backlog.get())
        assertEquals(0L, queuedBytes.get())
        assertEquals(0, pool.retainedBytes)
        val feeder = checkNotNull(retained)
        assertArrayEquals(expected, feeder.data.copyOf(expected.size))
        feeder.release()
        val reused = pool.acquire(expected.size)
        assertSame(feeder.data, reused.data)
        reused.release()
    }
}
