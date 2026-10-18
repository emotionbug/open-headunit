package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.utils.Utils

object Messages {
    const val DEF_BUFFER_LENGTH = 131080

    val versionRequest: ByteArray
        get() = versionRequest(1, 2)

    fun versionRequest(major: Int, minor: Int): ByteArray {
        require(major in 1..32767 && minor in 0..32767)
        return createRawMessage(0, 3, 1,
            byteArrayOf((major ushr 8).toByte(), major.toByte(), (minor ushr 8).toByte(), minor.toByte()))
    }

    // byte ac_buf [] = {0, 3, 0, 4, 0, 4, 8, 0};
    val statusOk: ByteArray
        get() = createRawMessage(0, 3, 4, byteArrayOf(8, 0), 2)

    fun createRawMessage(chan: Int, flags: Int, type: Int, data: ByteArray): ByteArray =
            createRawMessage(chan, flags, type, data, data.size)

    private fun createRawMessage(chan: Int, flags: Int, type: Int, data: ByteArray, size: Int): ByteArray {

        val total = 6 + size
        val buffer = ByteArray(total)

        buffer[0] = chan.toByte()
        buffer[1] = flags.toByte()
        Utils.intToBytes(size + 2, 2, buffer)
        Utils.intToBytes(type, 4, buffer)

        System.arraycopy(data, 0, buffer, 6, size)
        return buffer
    }
}
