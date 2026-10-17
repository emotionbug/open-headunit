package com.andrerinas.openheadunit.decoder.video

/**
 * Visits NAL payload ranges in borrowed Annex B storage, excluding their start codes.
 * The callback must finish reading before the caller returns the frame to its pool.
 * In particular, inspecting SPS/PPS must not allocate a copy of the much larger picture
 * that follows them. Only consumers retaining configuration need an independent copy.
 */
internal object AnnexBNalUnits {
    inline fun forEach(data: ByteArray, offset: Int, size: Int, visit: (Int, Int) -> Unit) {
        require(offset >= 0 && size >= 0 && offset <= data.size - size)
        val end = offset + size
        var i = offset
        var payloadStart = -1
        while (i + 2 < end) {
            // A nonzero/non-one third byte rules out a prefix at i, i+1 and i+2.
            val third = data[i + 2].toInt()
            if (third != 0 && third != 1) {
                i += 3
                continue
            }
            val prefixSize = if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                when {
                    third == 1 -> 3
                    third == 0 && i + 3 < end && data[i + 3].toInt() == 1 -> 4
                    else -> 0
                }
            } else 0
            if (prefixSize == 0) {
                i++
                continue
            }
            if (payloadStart >= 0 && i > payloadStart) visit(payloadStart, i - payloadStart)
            i += prefixSize
            payloadStart = i
        }
        // An empty trailing prefix is a delimiter, not a NAL header to index or retain.
        if (payloadStart >= 0 && payloadStart < end) visit(payloadStart, end - payloadStart)
    }

    /** MediaCodec CSD keeps the established four-byte start code regardless of wire prefix size. */
    fun copyWithStartCode(data: ByteArray, offset: Int, size: Int): ByteArray =
        ByteArray(size + 4).also {
            it[3] = 1
            System.arraycopy(data, offset, it, 4, size)
        }
}
