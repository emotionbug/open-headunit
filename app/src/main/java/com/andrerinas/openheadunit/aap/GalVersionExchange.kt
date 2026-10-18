package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Serial, pre-TLS exchange. Responses have no request ID: never retransmit a timed-out request
 * and then interpret its late answer as the next phase. A failed exchange retires this connection.
 *
 * GMS 12.8's cold GAL handler accepts only major 1; AA9.7/12.4/18 expose their higher response
 * constant for requests above 1.7. Thus 1.8 probes that branch without the legacy major mismatch.
 * Both implementations retain the REQUEST for service setup. A probe must be followed by the
 * final request, even when the final profile is the same as our initial compatibility request.
 */
internal class GalVersionExchange(
    private val frames: HandshakeFrameReader,
    private val now: () -> Long,
    private val send: (ByteArray, Int) -> Int,
    private val log: (String) -> Unit,
    private val pause: (Long) -> Unit = Thread::sleep
) {
    var phase: String = "baseline"
        private set
    var sentRequest: Boolean = false
        private set
    var requestWriteIncomplete: Boolean = false
        private set

    fun negotiate(maximum: GalProfile, deadline: Long): GalSessionProfile {
        val baseline = exchange("baseline", GalProfile.COMPATIBILITY.version, deadline)
        var probe: AapVersionNegotiation.Result? = null
        // Old replies already expose their 1.x ceiling. Do not probe them, or add a round trip
        // when the requested profile only needs 1.x. The compatibility default sends one request.
        if (maximum.version.major > 1 && baseline.version >= GalVersion(1, 7)) {
            probe = exchange("probe", GalVersion(1, 8), deadline)
        }
        val selected = GalProfile.select(maximum, (probe ?: baseline).version)
        val final = if (probe != null || selected != GalProfile.COMPATIBILITY) {
            exchange("final", selected.version, deadline)
        } else baseline
        // STATUS_SUCCESS is not sufficient: several sender implementations warn, but still accept
        // an unsupported request. Do not start TLS with a request above the final reported ceiling.
        if (final.version < selected.version) throw IOException("GAL final response is below $selected")
        phase = "complete"
        log("GAL profile=${selected.version} baseline=${baseline.version} probe=${probe?.version} finalReply=${final.version}")
        return GalSessionProfile(selected, baseline, probe, final)
    }

    private fun exchange(stage: String, requested: GalVersion, deadline: Long): AapVersionNegotiation.Result {
        phase = stage
        val bytes = Messages.versionRequest(requested.major, requested.minor)
        var attempts = 0
        while (true) {
            val remaining = deadline - now()
            if (remaining <= 0) throw IOException("GAL $stage deadline")
            sentRequest = true
            val written = send(bytes, minOf(remaining, 2000).toInt())
            // A later complete write clears the transient zero-byte failure. If the response
            // then times out, it is peer silence rather than an outstanding write failure.
            requestWriteIncomplete = written != bytes.size
            if (written == bytes.size) break
            // A positive short write has already sent a header prefix. A negative result hides
            // the transferred count (Android USB also uses -1 for timeout), so neither may be
            // retransmitted. Only an explicit zero can retry without creating duplicate replies.
            if (written != 0 || ++attempts >= 3) {
                throw IOException("GAL $stage request write failed: $written/${bytes.size} bytes")
            }
            val waitMs = minOf(200L, deadline - now())
            if (waitMs <= 0) throw IOException("GAL $stage deadline")
            log("GAL $stage wrote no bytes; retrying the same request")
            try {
                pause(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("GAL $stage retry interrupted").apply { initCause(e) }
            }
        }
        var otherMessages = 0
        while (now() < deadline) {
            val frame = frames.readFrame(deadline) ?: throw IOException("GAL $stage response incomplete or timed out")
            if (HandshakeMessagePolicy.isTerminalResponse(frame)) throw IOException("GAL peer ended $stage exchange")
            if (!HandshakeMessagePolicy.isLateVersionResponse(frame)) {
                if (++otherMessages > 8) throw IOException("GAL unexpected message limit")
                continue
            }
            val response = AapVersionNegotiation.parse(frame, frame.size)
                ?: throw IOException("GAL invalid $stage response")
            log("GAL $stage requested=$requested reply=${response.version} status=${response.statusName}")
            if (!response.accepted || response.major !in 1..32767 || response.minor !in 0..32767) {
                throw IOException("GAL rejected $stage response")
            }
            return response
        }
        throw IOException("GAL $stage deadline")
    }
}
