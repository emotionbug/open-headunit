package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class CallbackBufferSizingTest {
    @Test fun `the combined budget includes callback staging instead of duplicating latency`() {
        for (burst in intArrayOf(96, 192, 240, 480)) {
            for (total in burst * 3..2880 step burst) {
                val device = CallbackBufferSizing.deviceFrames(total, burst)
                val queue = CallbackBufferSizing.queueFrames(total, device, burst)
                assertTrue(device >= burst * 2)
                assertTrue(queue >= burst)
                assertEquals(total, device + queue)
            }
        }
    }

    @Test fun `a device grant larger than requested keeps only the minimum staging queue`() {
        assertEquals(192, CallbackBufferSizing.queueFrames(576, 1920, 192))
        assertEquals(384, CallbackBufferSizing.deviceFrames(576, 192))
    }

    @Test fun `non integral budgets round to complete bursts`() {
        val device = CallbackBufferSizing.deviceFrames(961, 192)
        val queue = CallbackBufferSizing.queueFrames(961, device, 192)
        assertEquals(0, device % 192)
        assertEquals(0, queue % 192)
        assertEquals(1152, device + queue)
    }
}
