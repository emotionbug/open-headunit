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
        val gateway = AapControlGateway(transport,
            AapControlService(transport, audio, mock<Settings>(), mock()), control, mock(), mock())
        init {
            fun field(name: String, value: Any) = AapTransport::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(transport, value)
            field("aapAudio", audio)
            field("aapVideo", mock<AapVideo>())
            field("mediaSessions", sessions)
            field("galContract", galTestContract(profile))
            whenever(audio.selectFormat(any(), any())).thenReturn(true)
            doAnswer { sessions.current(it.getArgument(0))?.active == true }
                .whenever(transport).isAudioSinkStarted(any())
            doNothing().whenever(transport).noteAudioSinkStarted(any())
            doNothing().whenever(transport).noteAudioSinkStopped(any())
            doNothing().whenever(transport).quit(any())
            doNothing().whenever(transport).onMicAck(any())
            doNothing().whenever(transport).closeMicSession(any())
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
        // A complete encrypted CONTROL message keeps its originating media channel ID.
        fun close(channel: Int) = gateway.execute(AapMessage(channel, 15,
            Control.ControlMsgType.MESSAGE_CHANNEL_CLOSE_NOTIFICATION_VALUE, 0, 0, byteArrayOf()))
    }

    @Test fun `control flagged channel close clears runtime but preserves tail ACK ownership and other sinks`() {
        val options = Media.MediaOptions.newBuilder().setAudioBufferLimit(GalMediaStatistics.durationMs(60))
            .setEnableMediaStats(true).setMediaStatsReportInterval(GalMediaStatistics.durationMs(500)).build()
        for (profile in GalProfile.entries) {
            for (channel in listOf(Channel.ID_AUD, Channel.ID_AU1, Channel.ID_AU2, Channel.ID_VID)) {
                for (mode in listOf(Media.Start.FlowControl.MODE_0, Media.Start.FlowControl.ACKLESS)) {
                    val f = Fixture(profile)
                    val otherChannel = if (channel == Channel.ID_AUD) Channel.ID_AU1 else Channel.ID_AUD
                    val start = Media.Start.newBuilder().setSessionId(19).setConfigurationIndex(0)
                        .setFlowControl(mode).setOptions(options).build()
                    f.control.execute(AapMessage(channel, Media.MsgType.MEDIA_MESSAGE_START_VALUE, start))
                    f.control.execute(AapMessage(otherChannel, Media.MsgType.MEDIA_MESSAGE_START_VALUE, start))
                    if (Channel.isAudio(channel) && profile in listOf(
                            GalProfile.GAL_5_0, GalProfile.GAL_6_0, GalProfile.GAL_6_1)) {
                        verify(f.audio).applyGalBufferLimit(channel, 60L)
                    }
                    val other = f.sessions.current(otherChannel)
                    val ack = f.transport.captureMediaAck(channel)
                    clearInvocations(f.audio, f.transport)

                    assertEquals(0, f.close(channel))
                    val closed = f.sessions.current(channel)!!
                    assertFalse(closed.active)
                    assertNull(closed.options)
                    assertEquals(GalRuntimeOptions(), closed.runtime)
                    assertEquals(ack, f.transport.captureMediaAck(channel))
                    assertSame(other, f.sessions.current(otherChannel))
                    if (Channel.isAudio(channel)) {
                        verify(f.audio).applyGalBufferLimit(channel, null)
                        verify(f.audio).stopAudio(channel)
                        verify(f.transport).noteAudioSinkStopped(channel)
                    }
                    verifyNoMoreInteractions(f.audio)
                    verify(f.transport, never()).quit(any())
                }
            }
        }
    }

    @Test fun `close rejects late options and same ID restart applies only new runtime`() {
        val f = Fixture(GalProfile.GAL_6_1)
        val channel = Channel.ID_AUD
        val options = Media.MediaOptions.newBuilder().setAudioBufferLimit(GalMediaStatistics.durationMs(60))
            .setEnableMediaStats(true).build()
        val start = Media.Start.newBuilder().setSessionId(12).setConfigurationIndex(0)
            .setFlowControl(Media.Start.FlowControl.ACKLESS).setOptions(options).build()
        f.control.execute(AapMessage(channel, Media.MsgType.MEDIA_MESSAGE_START_VALUE, start))
        val previous = f.sessions.current(channel)!!
        clearInvocations(f.audio)

        f.close(channel)
        f.close(channel)
        f.control.execute(AapMessage(channel, Media.MsgType.MEDIA_MESSAGE_OPTIONS_VALUE, options))
        assertFalse(f.sessions.current(channel)!!.active)
        assertNull(f.sessions.current(channel)!!.options)
        verify(f.audio, times(2)).applyGalBufferLimit(channel, null)
        verify(f.audio).stopAudio(channel)
        verifyNoMoreInteractions(f.audio)

        f.control.execute(AapMessage(channel, Media.MsgType.MEDIA_MESSAGE_START_VALUE,
            start.toBuilder().clearOptions().setFlowControl(Media.Start.FlowControl.MODE_0).build()))
        val restarted = f.sessions.current(channel)!!
        assertTrue(restarted.active)
        assertNotEquals(previous.generation, restarted.generation)
        assertEquals(GalRuntimeOptions(), restarted.runtime)
        assertEquals(12, f.transport.captureMediaAck(channel))
        verify(f.audio, times(3)).applyGalBufferLimit(channel, null)
        verify(f.audio, never()).applyGalBufferLimit(channel, 60L)
    }

    @Test fun `close before Start does not manufacture a session or change microphone credits`() {
        val f = Fixture(GalProfile.GAL_6_1)
        for (channel in listOf(Channel.ID_AUD, Channel.ID_VID, Channel.ID_MIC, Channel.ID_BTH)) {
            assertEquals(0, f.close(channel))
            assertNull(f.sessions.current(channel))
        }
        assertEquals(0, f.transport.captureMediaAck(Channel.ID_AUD))
        verify(f.audio).applyGalBufferLimit(Channel.ID_AUD, null)
        verifyNoMoreInteractions(f.audio)
        verify(f.transport, never()).onMicAck(any())
        verify(f.transport).closeMicSession(false)
        verify(f.transport, never()).quit(any())
    }

    @Test fun `close excludes a sink from active statistics while retaining the other observation window`() {
        val f = Fixture(GalProfile.GAL_6_1)
        val stats = GalMediaStatistics()
        val options = Media.MediaOptions.newBuilder().setEnableMediaStats(true)
            .setMediaStatsReportInterval(GalMediaStatistics.durationMs(500)).build()
        val start = Media.Start.newBuilder().setSessionId(7).setConfigurationIndex(0).setOptions(options).build()
        for (channel in listOf(Channel.ID_AUD, Channel.ID_AU1)) {
            f.control.execute(AapMessage(channel, Media.MsgType.MEDIA_MESSAGE_START_VALUE, start))
        }
        val mediaSample = GalPlaybackSample(Any(), 100L, 0, 0)
        val guidanceSample = GalPlaybackSample(Any(), 200L, 0, 0)
        stats.observe(Channel.ID_AUD, f.sessions.current(Channel.ID_AUD)!!, mediaSample, 0)
        stats.observe(Channel.ID_AU1, f.sessions.current(Channel.ID_AU1)!!, guidanceSample, 0)

        f.close(Channel.ID_AU1)
        val active = f.sessions.active()
        assertEquals(setOf(Channel.ID_AUD), active.keys)
        // Exercise the writer's active-set filtering and aggregation, not Android Handler timing.
        stats.retain(active.keys)
        assertNull(stats.observe(Channel.ID_AU1, f.sessions.current(Channel.ID_AU1)!!, guidanceSample, 500))
        val report = stats.observe(Channel.ID_AUD, active.getValue(Channel.ID_AUD),
            mediaSample.copy(audioUnderruns = 2), 500)!!
        assertEquals(2L, report.audioUnderrunCount)
        assertEquals(500_000_000L, GalRuntimeOptions.nanos(report.observationWindow))
    }

    @Test fun `Setup refuses wrong wire codecs before READY and never guesses the user setting`() {
        val cases = listOf(Channel.ID_AUD to 99, Channel.ID_AUD to Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP_VALUE,
            Channel.ID_MIC to Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE,
            Channel.ID_VID to Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265_VALUE)
        for ((channel, type) in cases) {
            val f = Fixture(GalProfile.GAL_6_1)
            assertEquals(-1, f.control.execute(AapMessage(channel, Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE,
                Media.MediaSetupRequest.newBuilder().setType(type).build())))
            verify(f.transport).quit(any())
            verify(f.transport, never()).send(any<AapMessage>())
            verifyNoInteractions(f.audio)
        }
    }

    @Test fun `selected guidance format reaches playback and repeated Setup retains that selection`() {
        val discovery = galTestDiscovery().toBuilder()
        val index = discovery.servicesList.indexOfFirst { it.id == Channel.ID_AU1 }
        val service = discovery.getServices(index).toBuilder()
        val high = service.mediaSinkService.getAudioConfigs(0).toBuilder().setSampleRate(48000).build()
        service.mediaSinkService = service.mediaSinkService.toBuilder().addAudioConfigs(high).build()
        discovery.setServices(index, service)
        val f = Fixture(GalProfile.GAL_6_1, discovery.build())
        val start = Media.Start.newBuilder().setSessionId(8).setConfigurationIndex(1).build()
        f.control.execute(AapMessage(Channel.ID_AU1, Media.MsgType.MEDIA_MESSAGE_START_VALUE, start))
        verify(f.audio).selectFormat(Channel.ID_AU1, high)
        clearInvocations(f.audio)
        f.control.execute(AapMessage(Channel.ID_AU1, Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE,
            Media.MediaSetupRequest.newBuilder().setType(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM_VALUE).build()))
        verify(f.audio).selectFormat(Channel.ID_AU1, high)
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
