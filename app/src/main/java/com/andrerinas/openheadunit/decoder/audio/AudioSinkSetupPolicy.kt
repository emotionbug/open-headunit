package com.andrerinas.openheadunit.decoder.audio

/**
 * Whether a Media Sink Setup has to rebuild this channel's sink, or can keep the one already there.
 *
 * The phone re-sends setup for every channel mid-session, which rebuilding cost the media sink
 * 298 ms of its capacity: the framework gave the first track a deep buffer and the replacement a
 * low-latency one, and every underrun and shed window of a 23 minute capture came after it.
 *
 * This policy compares codec ownership. AapAudio also compares the selected discovery format;
 * a different rate/channel count needs a new sink, while an identical Start retains its bank.
 */
object AudioSinkSetupPolicy {

    /**
     * [builtCodec] is the codec the live track was actually built with, and [setupCodec] the one this
     * setup names. A null [setupCodec] is a setup that named no audio codec, which leaves the sink
     * alone rather than rebuilding it on no information.
     */
    fun rebuilds(hasLiveTrack: Boolean, builtCodec: AudioSinkCodec?, setupCodec: AudioSinkCodec?): Boolean {
        if (!hasLiveTrack || builtCodec == null) return true
        if (setupCodec == null) return false
        return builtCodec != setupCodec
    }
}
