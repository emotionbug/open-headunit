package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.decoder.audio.MicRecorder
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.kotlin.*

class GalMediaControlTest {
    private class Fixture(profile: GalProfile, discovery: Control.ServiceDiscoveryResponse = galTestDiscovery()) {
        val transport = mock<AapTransport>(defaultAnswer = CALLS_REAL_METHODS)
        val sessions = GalMediaSessions().apply { advertise(galTestContract(profile).media, GalMediaOffer.from(discovery)) }
        val audio = mock<AapAudio>()
        val control = AapControlMedia(transport, mock<MicRecorder>(), audio)
        init {
            fun field(name: String, value: Any) = AapTransport::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(transport, value)
            field("mediaSessions", sessions)
            field("galContract", galTestContract(profile))
            doNothing().whenever(transport).noteAudioSinkStarted(any())
            doNothing().whenever(transport).noteAudioSinkStopped(any())
            doNothing().whenever(transport).quit(any())
            doNothing().whenever(transport).onMicAck(any())
            doNothing().whenever(transport).send(any<AapMessage>())
            doNothing().whenever(transport).gainVideoFocus()
            doReturn(mock<Settings>()).whenever(transport).settings
        }
        fun start(index: Int = 0): Int = control.execute(AapMessage(Channel.ID_AUD,
            Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder()
                .setSessionId(12).setConfigurationIndex(index).setFlowControl(Media.Start.FlowControl.ACKLESS)
                .setOptions(Media.MediaOptions.newBuilder().setEnableAvSync(false)).build()))
        fun update() = control.execute(AapMessage(Channel.ID_AUD, Media.MsgType.MEDIA_MESSAGE_OPTIONS_VALUE,
            Media.MediaOptions.newBuilder().setAverageLagWeight(7).build()))
    }

    @Test fun `Setup advertises the frozen configuration indices rather than global fallback state`() {
        val f = Fixture(GalProfile.GAL_6_1, galTestDiscovery(videoFallback = true))
        val setup = Media.MediaSetupRequest.newBuilder().setType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP_VALUE).build()
        assertEquals(0, f.control.execute(AapMessage(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE, setup)))
        val packet = argumentCaptor<AapMessage>()
        verify(f.transport).send(packet.capture())
        val response = packet.firstValue.parse(Media.Config.newBuilder()).build()
        assertEquals(listOf(0, 1), response.configurationIndicesList)
        assertEquals(Media.Config.ConfigStatus.STATUS_READY, response.status)
    }

    @Test fun `microphone Setup keeps its source path outside the sink offer`() {
        val f = Fixture(GalProfile.GAL_6_1)
        val setup = Media.MediaSetupRequest.newBuilder().setType(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM_VALUE).build()
        assertEquals(0, f.control.execute(AapMessage(Channel.ID_MIC, Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE, setup)))
        val packet = argumentCaptor<AapMessage>()
        verify(f.transport).send(packet.capture())
        assertEquals(listOf(0), packet.firstValue.parse(Media.Config.newBuilder()).build().configurationIndicesList)
        assertNull(f.sessions.current(Channel.ID_MIC))
        verifyNoInteractions(f.audio)
        verify(f.transport, never()).quit(any())
    }

    @Test fun `Start applies ackless and embedded options before playback`() {
        val f = Fixture(GalProfile.GAL_5_0)
        doAnswer {
            assertTrue(f.sessions.current(Channel.ID_AUD)!!.ackless)
            assertTrue(f.sessions.current(Channel.ID_AUD)!!.options!!.hasEnableAvSync())
            null
        }.whenever(f.audio).preparePlayback(Channel.ID_AUD)
        assertEquals(0, f.start())
        assertNull(f.transport.captureMediaAck(Channel.ID_AUD))
        assertEquals(0, f.transport.captureMediaAck(Channel.ID_AU1))
        verify(f.audio).preparePlayback(Channel.ID_AUD)
    }

    @Test fun `unadvertised audio configuration retires connection without preparing wrong format`() {
        val f = Fixture(GalProfile.GAL_6_1)
        assertEquals(-1, f.start(1))
        verify(f.transport).quit(false)
        verifyNoInteractions(f.audio)
        assertNull(f.sessions.current(Channel.ID_AUD))
    }

    @Test fun `standalone options are version gated and cannot revive stopped stream`() {
        for (profile in listOf(GalProfile.GAL_5_0, GalProfile.GAL_6_0, GalProfile.GAL_6_1)) {
            val f = Fixture(profile)
            f.update()
            assertNull(f.sessions.current(Channel.ID_AUD))
            f.start()
            f.update()
            assertEquals(profile != GalProfile.GAL_5_0, f.sessions.current(Channel.ID_AUD)!!.options!!.hasAverageLagWeight())
            f.control.execute(AapMessage(Channel.ID_AUD, 11, Media.MsgType.MEDIA_MESSAGE_STOP_VALUE, 0, 0, byteArrayOf()))
            f.update()
            assertFalse(f.sessions.current(Channel.ID_AUD)!!.active)
            assertNull(f.sessions.current(Channel.ID_AUD)!!.options)
        }
    }

    @Test fun `keyframe-cycle Stop closes options but preserves queued ACK ownership`() {
        for (mode in listOf(Media.Start.FlowControl.MODE_0, Media.Start.FlowControl.ACKLESS)) {
            val f = Fixture(GalProfile.GAL_6_1)
            val start = Media.Start.newBuilder().setSessionId(19).setConfigurationIndex(0)
                .setFlowControl(mode).setOptions(Media.MediaOptions.newBuilder().setAverageLagWeight(2)).build()
            f.control.execute(AapMessage(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_START_VALUE, start))
            val captured = f.transport.captureMediaAck(Channel.ID_VID)
            f.transport.ignoreNextStopRequest = true
            f.control.execute(AapMessage(Channel.ID_VID, 11, Media.MsgType.MEDIA_MESSAGE_STOP_VALUE, 0, 0, byteArrayOf()))
            assertFalse(f.transport.ignoreNextStopRequest)
            assertFalse(f.sessions.current(Channel.ID_VID)!!.active)
            assertNull(f.sessions.current(Channel.ID_VID)!!.options)
            assertEquals(captured, f.transport.captureMediaAck(Channel.ID_VID))
            f.control.execute(AapMessage(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_OPTIONS_VALUE,
                Media.MediaOptions.newBuilder().setAverageLagWeight(7).build()))
            assertNull(f.sessions.current(Channel.ID_VID)!!.options)
            f.control.execute(AapMessage(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_START_VALUE, start))
            assertTrue(f.sessions.acceptsUpdate(Channel.ID_VID))
            assertEquals(2, f.sessions.current(Channel.ID_VID)!!.options!!.averageLagWeight)
        }
    }

    @Test fun `ackless sink leaves microphone ACK credits intact`() {
        val f = Fixture(GalProfile.GAL_6_1)
        f.start()
        val ack = Media.Ack.newBuilder().setSessionId(33).setAck(2).build()
        f.control.execute(AapMessage(Channel.ID_MIC, Media.MsgType.MEDIA_MESSAGE_ACK_VALUE, ack))
        verify(f.transport).onMicAck(ack)
    }
}
