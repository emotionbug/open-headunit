package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any

/** Exercise the transport entry point, including the handoff to TLS, not just the byte parser. */
class VersionHandshakeFramingTest {
    private lateinit var log: org.mockito.MockedStatic<android.util.Log>
    @org.junit.Before fun stubLog() {
        log = mockStatic(android.util.Log::class.java)
        log.`when`<String> { android.util.Log.getStackTraceString(any<Throwable>()) }.thenReturn("test exception")
    }
    @org.junit.After fun restoreLog() { log.close() }
    private val response = byteArrayOf(0, 3, 0, 8, 0, 2, 0, 1, 0, 7, 0, 0)
    private val tlsFrame = byteArrayOf(0, 3, 0, 3, 0, 3, 22)

    private fun transport(ssl: AapSsl): AapTransport {
        val transport = mock(AapTransport::class.java, CALLS_REAL_METHODS)
        AapTransport::class.java.getDeclaredField("ssl").apply { isAccessible = true }.set(transport, ssl)
        AapTransport::class.java.getDeclaredField("maximumGalProfile").apply { isAccessible = true }.set(transport, GalProfile.COMPATIBILITY)
        AapTransport::class.java.getDeclaredField("displaySettings").apply { isAccessible = true }.set(transport, GalDisplaySettings(false, false, false))
        return transport
    }

    private fun run(transport: AapTransport, connection: ProjectionConnection) {
        val method = AapTransport::class.java.getDeclaredMethod("handshake", ProjectionConnection::class.java)
            .apply { isAccessible = true }
        // A mock TLS engine returns false after we prove it was reached (or not reached).
        assertEquals(false, method.invoke(transport, connection))
    }

    @Test fun `split response survives an empty read and leaves coalesced TLS bytes for SSL`() = mockStatic(SystemClock::class.java).use { clock ->
        for (split in 1 until response.size) {
            var now = 0L
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { now }
            val connection = mock(ProjectionConnection::class.java)
            `when`(connection.isSingleMessage).thenReturn(true)
            `when`(connection.isConnected).thenReturn(true)
            `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenReturn(10)
            val stream = response + tlsFrame
            var position = 0
            var timedOut = false
            `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                assertFalse(it.getArgument<Boolean>(3))
                if (position == split && !timedOut) {
                    timedOut = true
                    now += 100
                    0
                } else {
                    val count = minOf(it.getArgument<Int>(1),
                        if (position < split) split - position else stream.size - position)
                    stream.copyInto(it.getArgument<ByteArray>(0), 0, position, position + count)
                    position += count
                    count
                }
            }
            val ssl = mock(AapSsl::class.java)
            val transport = transport(ssl)
            run(transport, connection)
            verify(ssl).performHandshake(connection)
            assertEquals(response.size, position)
            assertEquals(7, transport.galContract!!.negotiation.finalResponse.minor)
            verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
        }
    }

    @Test fun `rejected truncated and malformed responses never reach TLS`() = mockStatic(SystemClock::class.java).use {
        val invalid = listOf(
            response.copyOf().also { it[10] = -1; it[11] = -1 },
            byteArrayOf(0, 3, 0, 2, 0, 2),
            byteArrayOf(0, 3, 0, 2, -1, -1),
            byteArrayOf(0, 3, 0, 2, 0, 15),
            response.copyOf().also { it[1] = 11 }
        )
        for (frame in invalid) {
            val connection = mock(ProjectionConnection::class.java)
            `when`(connection.isSingleMessage).thenReturn(true)
            `when`(connection.isConnected).thenReturn(true)
            `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenReturn(10)
            var position = 0
            `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                val count = minOf(it.getArgument<Int>(1), frame.size - position)
                frame.copyInto(it.getArgument<ByteArray>(0), 0, position, position + count)
                position += count
                count
            }
            val ssl = mock(AapSsl::class.java)
            val transport = transport(ssl)
            run(transport, connection)
            verifyNoInteractions(ssl)
            assertNull(transport.galContract)
            assertEquals(AapTransport.HandshakeFailure.OTHER, transport.lastHandshakeFailure)
        }
    }

    @Test fun `partial request write fails without sending a second header`() = mockStatic(SystemClock::class.java).use {
        val connection = mock(ProjectionConnection::class.java)
        `when`(connection.isSingleMessage).thenReturn(true)
        `when`(connection.isConnected).thenReturn(true)
        `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenReturn(5)
        val ssl = mock(AapSsl::class.java)
        val transport = transport(ssl)
        run(transport, connection)
        assertEquals(AapTransport.HandshakeFailure.TRANSPORT_ERROR, transport.lastHandshakeFailure)
        verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
        verify(connection, never()).recvBlocking(any(), anyInt(), anyInt(), anyBoolean())
        verifyNoInteractions(ssl)
    }

    @Test fun `recovered zero byte write followed by silence is not a transport failure`() = mockStatic(SystemClock::class.java).use { clock ->
        for (writeRecovers in listOf(true, false)) {
            var now = 0L
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { now }
            val connection = mock(ProjectionConnection::class.java)
            `when`(connection.isSingleMessage).thenReturn(true)
            `when`(connection.isConnected).thenReturn(true)
            var writes = 0
            `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer {
                writes++
                if (writeRecovers && writes > 1) 10 else 0
            }
            `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                now += it.getArgument<Int>(2)
                0
            }
            val ssl = mock(AapSsl::class.java)
            val transport = transport(ssl)
            run(transport, connection)
            assertEquals(if (writeRecovers) AapTransport.HandshakeFailure.PEER_SILENT
                else AapTransport.HandshakeFailure.TRANSPORT_ERROR, transport.lastHandshakeFailure)
            assertEquals(if (writeRecovers) 2 else 3, writes)
            assertNull(transport.galContract)
            verifyNoInteractions(ssl)
        }
    }
}
