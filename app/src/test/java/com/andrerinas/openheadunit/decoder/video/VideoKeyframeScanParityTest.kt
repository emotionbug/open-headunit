package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class VideoKeyframeScanParityTest {
    // Original byte-at-a-time walk, retained as an independent oracle for the skip optimization.
    private fun reference(data: ByteArray, offset: Int, size: Int, hevc: Boolean): Boolean {
        if (size < 4) return false
        val end = minOf(offset + size, data.size)
        var i = offset
        var count = 0
        while (i + 3 < end && count < 8) {
            if (data[i].toInt() != 0 || data[i + 1].toInt() != 0) { i++; continue }
            val header = when {
                data[i + 2].toInt() == 1 -> i + 3
                data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> i + 4
                else -> { i++; continue }
            }
            if (header >= end) { i++; continue }
            count++
            val b = data[header].toInt()
            if (if (hevc) ((b and 0x7e) shr 1) in 16..21 else (b and 0x1f) == 5) return true
            i = header + 1
        }
        return false
    }

    @Test fun `skip walk preserves old result across offsets partial prefixes and mixed NALs`() {
        val random = Random(1047)
        repeat(10_000) {
            val bytes = ByteArray(1024)
            random.nextBytes(bytes)
            repeat(12) {
                val at = random.nextInt(1018)
                val prefix = if (random.nextBoolean()) byteArrayOf(0, 0, 1) else byteArrayOf(0, 0, 0, 1)
                prefix.copyInto(bytes, at)
                bytes[at + prefix.size] = random.nextInt(128).toByte()
            }
            val offset = random.nextInt(32)
            val size = random.nextInt(bytes.size - offset + 1)
            for (hevc in listOf(false, true)) {
                assertEquals(reference(bytes, offset, size, hevc),
                    VideoKeyframeScanner.containsKeyframe(bytes, offset, size, hevc))
            }
        }
    }

    @Test fun `keyframe beyond a long SEI is not lost by a scan budget`() {
        for (hevc in listOf(false, true)) {
            val sei = byteArrayOf(0, 0, 1, if (hevc) 78 else 6) + ByteArray(1024 * 1024) { 0x55 }
            val frame = sei + byteArrayOf(0, 0, 0, 1, if (hevc) 38 else 0x65)
            assertEquals(true, VideoKeyframeScanner.containsKeyframe(frame, 0, frame.size, hevc))
            assertEquals(false, VideoKeyframeScanner.containsKeyframe(frame, 0, frame.size - 1, hevc))
        }
    }
}
