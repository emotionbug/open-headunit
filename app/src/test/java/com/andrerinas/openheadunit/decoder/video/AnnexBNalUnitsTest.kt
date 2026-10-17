package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class AnnexBNalUnitsTest {
    private fun ranges(data: ByteArray, offset: Int = 0, size: Int = data.size): List<Pair<Int, Int>> =
        ArrayList<Pair<Int, Int>>().also { result ->
            AnnexBNalUnits.forEach(data, offset, size) { start, length -> result.add(start to length) }
        }

    @Test fun `mixed prefixes and empty tails never expose a missing header`() {
        val bytes = byteArrayOf(99, 0, 0, 1, 0x67, 12, 0, 0, 0, 1, 0x68, 13, 0, 0, 1, 99)
        assertEquals(listOf(4 to 2, 10 to 2), ranges(bytes, 1, bytes.size - 2))
        assertTrue(ranges(byteArrayOf(0, 0, 0, 1)).isEmpty())
        assertTrue(ranges(byteArrayOf(0, 0, 1)).isEmpty())
        assertEquals(listOf(3 to 1), ranges(byteArrayOf(0, 0, 1, 0x65)))
    }

    @Test fun `normalization owns only selected configuration and leaves picture borrowed`() {
        val picture = ByteArray(1024 * 1024) { 85 }
        val data = byteArrayOf(0, 0, 1, 0x67, 12, 0, 0, 0, 1, 0x65) + picture
        var copied = 0
        var csd: ByteArray? = null
        AnnexBNalUnits.forEach(data, 0, data.size) { start, size ->
            if (data[start].toInt() and 31 == 7) {
                csd = AnnexBNalUnits.copyWithStartCode(data, start, size)
                copied += size
            } else assertEquals(picture.size + 1, size)
        }
        assertEquals(2, copied)
        data.fill(99)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 12), csd)
    }

    @Test fun `skip scan agrees with bytewise delimiter walk across bounded random slices`() {
        val random = Random(178)
        repeat(5000) {
            val offset = random.nextInt(12)
            val size = random.nextInt(2048)
            val data = ByteArray(offset + size + 8)
            random.nextBytes(data)
            repeat(10) {
                if (size >= 4) {
                    val start = offset + random.nextInt(size - 3)
                    byteArrayOf(0, 0, 1).copyInto(data, start)
                }
            }
            val expected = ArrayList<Pair<Int, Int>>()
            val end = offset + size
            var start = -1
            var i = offset
            while (i + 2 < end) {
                val prefix = when {
                    data[i].toInt() != 0 || data[i + 1].toInt() != 0 -> 0
                    data[i + 2].toInt() == 1 -> 3
                    i + 3 < end && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> 4
                    else -> 0
                }
                if (prefix == 0) { i++; continue }
                if (start >= 0 && i > start) expected.add(start to i - start)
                i += prefix
                start = i
            }
            if (start >= 0 && start < end) expected.add(start to end - start)
            assertEquals(expected, ranges(data, offset, size))
        }
    }
}
