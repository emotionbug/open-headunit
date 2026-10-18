package com.andrerinas.openheadunit.aap

import android.media.AudioManager
import android.os.Looper
import android.os.Handler
import org.mockito.Mockito
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.decoder.audio.*
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class GalAudioFormatTest {
    private class Fixture {
        val session = AudioDecoder.PlaybackSession(AudioDecoder.PlaybackCallbacks())
        val decoder = mock<AudioDecoder>()
        var track: AudioTrackWrapper? = null
        var codec = AudioSinkCodec.PCM
        val rates = mutableListOf<Int>()
        val settings = mock<Settings> {
            on { playbackFocusMode } doReturn PlaybackFocusPolicy.Mode.AUTO
        }
        val audio: AapAudio
        init {
            whenever(decoder.openSession(any())).thenReturn(session)
            whenever(decoder.getTrack(any(), eq(session))).thenAnswer { track }
            whenever(decoder.sinkCodecFor(any(), eq(session))).thenAnswer { if (track == null) null else codec }
            doAnswer { track = null; null }.whenever(decoder).stop(any<Int>(), eq(session))
            doAnswer {
                rates.add(it.getArgument(2))
                codec = if (it.getArgument<Boolean>(5)) AudioSinkCodec.AAC_LC else AudioSinkCodec.PCM
                track = mock()
                null
            }.whenever(decoder).start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(session))
            audio = Mockito.mockStatic(Looper::class.java).use {
                Mockito.mockConstruction(Handler::class.java).use {
                    AapAudio(decoder, mock<AudioManager>(), settings)
                }
            }
        }
        fun format(rate: Int) = Media.AudioConfiguration.newBuilder().setSampleRate(rate)
            .setNumberOfBits(16).setNumberOfChannels(1).build()
    }

    @Test fun `changing selected rate rebuilds once and identical Start retains the sink`() {
        val f = Fixture()
        val channel = Channel.ID_AU1
        f.audio.noteSinkCodec(channel, Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM_VALUE)
        assertTrue(f.audio.selectFormat(channel, f.format(16000)))
        assertTrue(f.audio.selectFormat(channel, f.format(48000)))
        assertTrue(f.audio.selectFormat(channel, f.format(48000)))
        assertEquals(listOf(16000, 48000), f.rates)
        verify(f.decoder, times(1)).stop(channel, f.session)
        assertFalse(f.audio.selectFormat(channel, f.format(48000).toBuilder().setNumberOfBits(24).build()))
        assertEquals(listOf(16000, 48000), f.rates)
    }

    @Test fun `invalid CSD retires the pending copy before a later format switch`() {
        for (size in listOf(0, 1, 65)) {
            val f = Fixture()
            val channel = Channel.ID_AU1
            f.audio.noteSinkCodec(channel, Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE)
            f.audio.selectFormat(channel, f.format(16000))
            val csd = AacCodecConfig.default(48000, 1).copyBytes()
            f.audio.process(AapMessage(channel, 11, 1, 0, csd.size, csd))
            f.audio.process(AapMessage(channel, 11, 1, 0, size, ByteArray(size)))
            f.audio.selectFormat(channel, f.format(48000))
            verify(f.decoder, never()).configure(any(), any(), any(), any(), any())
        }
    }

    @Test fun `CSD before Start is copied and reaches only a matching selected format`() {
        val f = Fixture()
        val channel = Channel.ID_AU1
        f.audio.noteSinkCodec(channel, Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE)
        f.audio.selectFormat(channel, f.format(16000))
        val csd = AacCodecConfig.default(48000, 1).copyBytes()
        val expected = csd.copyOf()
        f.audio.process(AapMessage(channel, 11, Media.MsgType.MEDIA_MESSAGE_CODEC_CONFIG_VALUE, 0, csd.size, csd))
        verify(f.decoder, never()).configure(any(), any(), any(), any(), any())
        csd.fill(0)
        f.audio.selectFormat(channel, f.format(48000))
        val data = argumentCaptor<ByteArray>()
        verify(f.decoder).configure(eq(channel), data.capture(), eq(0), eq(expected.size), eq(f.session))
        assertArrayEquals(expected, data.firstValue)
    }
}
