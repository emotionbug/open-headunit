package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class GalAudioReserveTest {
    @Test fun `sender ceiling never shrinks saved preference or a full observed batch`() {
        val preferred = AdaptiveJitterPolicy(48000, 16)
        preferred.requestedLimitMs = 50
        assertTrue(preferred.targetFrames >= 19200)
        val batched = AdaptiveJitterPolicy(48000, 1)
        batched.requestedLimitMs = 60
        batched.onArrival(0, 4800)
        assertTrue(batched.targetFrames >= 5280) // 100ms packet + one 10ms render cycle.
    }

    @Test fun `underrun raises reserve despite a lower sender hint`() {
        val policy = AdaptiveJitterPolicy(48000, 1)
        policy.requestedLimitMs = 60
        policy.onArrival(0, 4800)
        val limited = policy.targetFrames
        policy.onUnderrun(150)
        assertTrue(policy.targetFrames > limited)
        policy.requestedLimitMs = null
        assertTrue(policy.targetFrames >= limited)
    }

    @Test fun `hint follows quiet decay instead of a target step and cannot block deficit recovery`() {
        val policy = AdaptiveJitterPolicy(48000, 1)
        policy.onArrival(0, 4800)
        val initial = policy.targetFrames
        policy.requestedLimitMs = 60
        assertEquals(initial, policy.targetFrames)
        var last = initial
        for (ms in 100L..30_000L step 100) {
            policy.onArrival(ms, 4800)
            assertTrue("target fell too fast at $ms", last - policy.targetFrames <= 240)
            last = policy.targetFrames
        }
        assertTrue(last < initial)
        policy.onArrival(30_400, 4800)
        assertTrue("a supply deficit must immediately override a low hint", policy.targetFrames > last)
    }

    @Test fun `receiving a buffer request does not immediately discard stored PCM`() {
        val buffer = AdaptivePcmBuffer(latencyMultiplier = 1)
        buffer.noteArrival(0, 4800)
        buffer.write(ShortArray(9600) { 1000 }, 9600, 0)
        val before = buffer.depthFrames()
        buffer.setRequestedLimit(60)
        assertEquals(before, buffer.depthFrames())
        assertEquals(0L, buffer.droppedFrames)
        buffer.setRequestedLimit(null)
        assertEquals(before, buffer.depthFrames())
        val (queueUs, overflows, underruns) = buffer.telemetry()
        assertEquals(100_000L, queueUs)
        assertEquals(0L, overflows)
        assertEquals(0L, underruns)
    }
}
