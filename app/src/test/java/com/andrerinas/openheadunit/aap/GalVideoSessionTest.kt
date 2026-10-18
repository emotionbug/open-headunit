package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.decoder.video.VideoFaultInjector
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*

class GalVideoSessionTest {
    @Test fun `decoder uses advertised codec and frozen backend after settings change`() =
        mockStatic(SystemClock::class.java).use {
            val settings = mock<Settings> {
                on { videoCodec } doReturn "H.264"
                on { forceSoftwareDecoding } doReturn true
                on { softwareVideoDecoder } doReturn Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG
                on { debugVideoFaultInjection } doReturn VideoFaultInjector.Mode.OFF
            }
            val decoder = mock<VideoDecoder>()
            val video = AapVideo(decoder, settings) { fail("Unexpected corruption") }
            video.noteAnnouncedCodec(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265_VALUE)
            whenever(settings.videoCodec).thenReturn("H.264")
            whenever(settings.forceSoftwareDecoding).thenReturn(false)
            whenever(settings.softwareVideoDecoder).thenReturn(Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC)
            assertTrue(video.usesBundledHevc())
            val packet = byteArrayOf(0, 1, 0, 0, 0, 1, 0x40, 1, 2)
            video.process(AapMessage(Channel.ID_VID, 11, 1, 2, packet.size, packet))
            verify(decoder).decode(any(), eq(2), eq(packet.size - 2), eq(true), eq("H.265"),
                eq(Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG))
        }
}
