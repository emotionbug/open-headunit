package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Common
import com.andrerinas.openheadunit.aap.protocol.proto.Control

/**
 * The version response the phone sends before the SSL handshake, which this app used to discard.
 *
 * Payload after the 6 byte AAP header is major, minor and status as big-endian shorts, then an
 * optional VersionResponseOptions proto carrying the connection parameters the phone wants us to
 * use. This is the phone's reported version, not the final HU request. The sender retains the
 * request for its feature gates; a 1.7 reply to a 1.2 request does not enable 1.6 features.
 */
object AapVersionNegotiation {

    data class Result(
        val major: Int,
        val minor: Int,
        val status: Int,
        val requestedConfig: Control.ConnectionConfiguration?
    ) {
        /** A failed version exchange must never advance to TLS authentication. */
        val accepted: Boolean get() = status == 0

        internal val version: GalVersion get() = GalVersion(major, minor)

        val statusName: String
            get() = Common.MessageStatus.forNumber(status)?.name ?: "UNKNOWN($status)"
    }

    /**
     * [frame] is the whole received frame including the 6 byte header, [length] the bytes read.
     * Returns null for an incomplete response or a mismatched envelope.
     */
    fun parse(frame: ByteArray, length: Int): Result? {
        if (length < 12 || length > frame.size) return null
        fun u16(i: Int) = ((frame[i].toInt() and 0xFF) shl 8) or (frame[i + 1].toInt() and 0xFF)
        // Length covers type + body, excluding the four-byte transport header. A read may
        // contain several frames; callers must pass exactly one complete response.
        if (frame[0] != 0.toByte() || frame[1] != 3.toByte() ||
            u16(4) != 2 || u16(2) != length - 4) return null
        val status = u16(10)
        var config: Control.ConnectionConfiguration? = null
        if (length > 12) {
            config = try {
                Control.VersionResponseOptions
                    .parseFrom(frame.copyOfRange(12, length))
                    .takeIf { it.hasConnectionConfiguration() }
                    ?.connectionConfiguration
            } catch (_: Exception) {
                // A malformed options tail cannot define a trustworthy final session contract.
                return null
            }
        }
        // status is a signed 16-bit MessageStatus, so -1 arrives as 0xFFFF.
        val signed = if (status >= 0x8000) status - 0x10000 else status
        return Result(u16(6), u16(8), signed, config)
    }
}
