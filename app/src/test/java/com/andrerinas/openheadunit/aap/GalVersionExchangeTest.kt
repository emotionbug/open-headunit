package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class GalVersionExchangeTest {
    private fun reply(major: Int, minor: Int, status: Int = 0, tail: ByteArray = byteArrayOf()): ByteArray {
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        return byteArrayOf(0, 3) + u16(8 + tail.size) + byteArrayOf(0, 2) +
            u16(major) + u16(minor) + u16(status) + tail
    }

    private class Wire(val replies: List<ByteArray>, val chunk: Int = 65535) {
        var now = 0L
        var input = byteArrayOf()
        val requests = mutableListOf<ByteArray>()
        var partialWrite = false
        val exchange = GalVersionExchange(
            HandshakeFrameReader({ now }) { bytes, length, timeout ->
                if (input.isEmpty()) { now += timeout; 0 } else {
                    val count = minOf(length, chunk, input.size)
                    input.copyInto(bytes, 0, 0, count)
                    input = input.copyOfRange(count, input.size)
                    count
                }
            }, { now }, { bytes, _ ->
                input += replies.getOrElse(requests.size) { byteArrayOf() }
                requests.add(bytes)
                if (partialWrite) 5 else bytes.size
            }, {})
        fun run(max: GalProfile) = exchange.negotiate(max, 1000)
        fun requested() = requests.map { GalVersion(it[7].toInt() and 255, it[9].toInt() and 255) }
    }

    @Test fun `default is one legacy request even with a high reported ceiling`() {
        val w = Wire(listOf(reply(6, 1)))
        val session = w.run(GalProfile.COMPATIBILITY)
        assertEquals(GalVersion(1, 2), session.requestedVersion)
        assertEquals(GalVersion(6, 1), session.finalResponse.version)
        assertEquals(listOf(GalVersion(1, 2)), w.requested())
        assertNull(session.probeResponse)
    }

    @Test fun `old peer is not probed and stays on compatibility`() {
        for (minor in 2..5) {
            val w = Wire(listOf(reply(1, minor)))
            assertEquals(GalProfile.COMPATIBILITY, w.run(GalProfile.GAL_6_1).profile)
            assertEquals(1, w.requests.size)
        }
    }

    @Test fun `modern ceiling selects only an implemented profile within user maximum`() {
        val cases = listOf(
            GalVersion(4, 2) to GalProfile.GAL_1_6,
            GalVersion(4, 5) to GalProfile.GAL_1_6,
            GalVersion(5, 0) to GalProfile.GAL_5_0,
            GalVersion(6, 0) to GalProfile.GAL_6_0,
            GalVersion(6, 1) to GalProfile.GAL_6_1,
            GalVersion(7, 9) to GalProfile.GAL_6_1
        )
        for ((ceiling, expected) in cases) for (chunk in 1..12) {
            val w = Wire(listOf(reply(1, 7), reply(ceiling.major, ceiling.minor),
                reply(ceiling.major, ceiling.minor)), chunk)
            assertEquals(expected, w.run(GalProfile.GAL_6_1).profile)
            assertEquals(listOf(GalVersion(1, 2), GalVersion(1, 8), expected.version), w.requested())
        }
        val w = Wire(listOf(reply(1, 7), reply(6, 1), reply(6, 1)))
        assertEquals(GalProfile.GAL_5_0, w.run(GalProfile.GAL_5_0).profile)
    }

    @Test fun `probe is followed by explicit final request even if result is compatibility`() {
        val w = Wire(listOf(reply(1, 7), reply(1, 5), reply(1, 5)))
        assertEquals(GalProfile.COMPATIBILITY, w.run(GalProfile.GAL_6_1).profile)
        assertEquals(listOf(GalVersion(1, 2), GalVersion(1, 8), GalVersion(1, 2)), w.requested())
    }

    @Test fun `only final response options belong to the session and TLS bytes stay unread`() {
        fun options(timeout: Int) = Control.VersionResponseOptions.newBuilder()
            .setConnectionConfiguration(Control.ConnectionConfiguration.newBuilder()
                .setPingConfiguration(Control.PingConfiguration.newBuilder().setTimeoutMs(timeout)))
            .build().toByteArray()
        val tls = byteArrayOf(0, 3, 0, 3, 0, 3, 22)
        val w = Wire(listOf(reply(1, 7), reply(6, 1, tail = options(100)),
            reply(6, 1, tail = options(9000)) + tls), 3)
        val session = w.run(GalProfile.GAL_6_0)
        assertEquals(9000, session.finalResponse.requestedConfig!!.pingConfiguration.timeoutMs)
        assertArrayEquals(tls, w.input)
    }

    @Test fun `failed and ambiguous exchanges do not retransmit or advance`() {
        val invalid = listOf(reply(1, 7, -1), reply(0, 0), reply(1, 7, tail = byteArrayOf(-1)),
            reply(1, 7).copyOf(9), byteArrayOf(0, 3, 0, 2, 0, 15), byteArrayOf())
        for (frame in invalid) {
            val w = Wire(listOf(frame))
            assertThrows(IOException::class.java) { w.run(GalProfile.GAL_6_1) }
            assertEquals(1, w.requests.size)
        }
        val partial = Wire(emptyList()).also { it.partialWrite = true }
        assertThrows(IOException::class.java) { partial.run(GalProfile.GAL_6_1) }
        assertEquals(1, partial.requests.size)
        val downgrade = Wire(listOf(reply(1, 7), reply(6, 1), reply(1, 7)))
        assertThrows(IOException::class.java) { downgrade.run(GalProfile.GAL_6_1) }
        assertEquals(3, downgrade.requests.size)
    }

    @Test fun `request uses two byte big endian version components and unknown settings are safe`() {
        assertArrayEquals(byteArrayOf(0, 3, 0, 6, 0, 1, 1, 2, 3, 4), Messages.versionRequest(258, 772))
        assertEquals(GalProfile.COMPATIBILITY, GalProfile.fromSetting("99.9"))
    }

    @Test fun `zero byte writes retry within each phase without duplicating completed requests`() {
        var now = 0L
        var input = byteArrayOf()
        val calls = mutableListOf<ByteArray>()
        val pauses = mutableListOf<Long>()
        val exchange = GalVersionExchange(HandshakeFrameReader({ now }) { out, length, _ ->
            assertTrue(input.size >= length)
            input.copyInto(out, 0, 0, length)
            input = input.copyOfRange(length, input.size)
            length
        }, { now }, { bytes, _ ->
            calls.add(bytes.copyOf())
            if (calls.size % 2 == 1) 0 else {
                input = if (calls.size == 2) reply(1, 7) else reply(6, 1)
                bytes.size
            }
        }, {}, { delay -> pauses.add(delay); now += delay })
        assertEquals(GalProfile.GAL_6_1, exchange.negotiate(GalProfile.GAL_6_1, 1000).profile)
        assertEquals(6, calls.size)
        for (i in 0..2) assertArrayEquals(calls[i * 2], calls[i * 2 + 1])
        assertEquals(listOf(200L, 200L, 200L), pauses)
    }

    @Test fun `zero byte retry count and original deadline remain bounded`() {
        for (deadline in listOf(100L, 1000L)) {
            var now = 0L
            var writes = 0
            var reads = 0
            val exchange = GalVersionExchange(HandshakeFrameReader({ now }) { _, _, _ -> reads++; -1 },
                { now }, { _, _ -> writes++; 0 }, {}, { now += it })
            assertThrows(IOException::class.java) { exchange.negotiate(GalProfile.COMPATIBILITY, deadline) }
            assertEquals(if (deadline == 100L) 1 else 3, writes)
            assertEquals(0, reads)
            assertTrue(now <= deadline)
        }
    }

    @Test fun `ambiguous write and negative read never resend a version request`() {
        for (written in listOf(-1, 5, 10)) {
            var writes = 0
            var reads = 0
            val exchange = GalVersionExchange(HandshakeFrameReader({ 0L }) { _, _, _ -> reads++; -1 },
                { 0L }, { _, _ -> writes++; written }, {}, { fail("Ambiguous I/O must not retry") })
            assertThrows(IOException::class.java) { exchange.negotiate(GalProfile.GAL_6_1, 1000) }
            assertEquals(1, writes)
            assertEquals(if (written == 10) 1 else 0, reads)
        }
    }

    @Test fun `interrupted retry preserves cancellation and does not write again`() {
        var writes = 0
        val exchange = GalVersionExchange(HandshakeFrameReader({ 0L }) { _, _, _ -> fail("No reads"); -1 },
            { 0L }, { _, _ -> writes++; 0 }, {}, { throw InterruptedException("cancelled") })
        try {
            assertThrows(java.io.InterruptedIOException::class.java) {
                exchange.negotiate(GalProfile.COMPATIBILITY, 1000)
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, writes)
        } finally {
            Thread.interrupted()
        }
    }
}
