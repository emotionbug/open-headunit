package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import org.junit.Assert.*
import org.junit.Test

class GalMediaContractTest {
    private fun start(id: Int, mode: Media.Start.FlowControl? = null) = Media.Start.newBuilder()
        .setSessionId(id).setConfigurationIndex(0).apply { mode?.let { setFlowControl(it) } }.build()

    @Test fun `wire Start mode2 enables ackless without changing another channel`() {
        // Required fields1/2, then enum field3. Absence and modes0/1 retain legacy credit handling.
        val wire = byteArrayOf(8, 9, 16, 0, 24, 2)
        val sessions = galTestSessions()
        val audio = sessions.start(Channel.ID_AUD, Media.Start.parseFrom(wire))!!
        val video = sessions.start(Channel.ID_VID, start(10))!!
        assertTrue(audio.ackless)
        assertFalse(video.ackless)
        for (mode in listOf(Media.Start.FlowControl.MODE_0, Media.Start.FlowControl.MODE_1)) {
            assertFalse(sessions.start(Channel.ID_AUD, start(11, mode))!!.ackless)
        }
        // Worker retains its original policy even after the next Start switches mode.
        assertTrue(audio.ackless)
        assertNotSame(audio, sessions.current(Channel.ID_AUD))
        assertSame(video, sessions.current(Channel.ID_VID))
    }

    @Test fun `stop permits received tail but options cannot reactivate or leak between sessions`() {
        val sessions = galTestSessions()
        val options = Media.MediaOptions.newBuilder().setEnableAvSync(true).build()
        val old = sessions.start(Channel.ID_VID, start(4))!!
        assertTrue(sessions.update(Channel.ID_VID, options))
        sessions.stop(Channel.ID_VID)
        assertEquals(old.id, sessions.current(Channel.ID_VID)!!.id)
        assertFalse(sessions.update(Channel.ID_VID, options))
        assertNull(sessions.current(Channel.ID_VID)!!.options)
        val next = sessions.start(Channel.ID_VID, start(4))!! // repeated wire ID starts with fresh options
        assertNotSame(old, sessions.current(Channel.ID_VID))
        assertSame(next, sessions.current(Channel.ID_VID))
        assertNull(next.options)
    }

    @Test fun `option snapshots preserve absence and signed Duration wire encoding`() {
        val negativeOffset = Media.MediaDuration.newBuilder().setNanos(-1000000).build()
        val options = Media.MediaOptions.newBuilder().setSyncOffsetOverride(negativeOffset)
            .setEnableAvSync(false).setAverageLagWeight(4).build()
        val roundtrip = Media.MediaOptions.parseFrom(options.toByteArray())
        assertTrue(roundtrip.hasEnableAvSync())
        assertFalse(roundtrip.enableAvSync)
        assertEquals(-1000000, roundtrip.syncOffsetOverride.nanos)
        assertTrue(negativeOffset.toByteArray().size > 5) // int32, not ZigZag sint32
        val sessions = galTestSessions()
        sessions.start(Channel.ID_AUD, start(1).toBuilder().setOptions(options).build())
        assertTrue(sessions.current(Channel.ID_AUD)!!.options!!.hasSyncOffsetOverride())
        sessions.update(Channel.ID_AUD, Media.MediaOptions.getDefaultInstance())
        assertFalse(sessions.current(Channel.ID_AUD)!!.options!!.hasSyncOffsetOverride())
    }

    @Test fun `modern UI keeps legacy odd margins and hidden widgets across replacement updates`() {
        val display = GalDisplayContract(GalDisplaySettings(true, false, true), true)
        val initial = display.initial(5, 7)!!
        assertEquals(2, initial.margins.left)
        assertEquals(3, initial.margins.right)
        assertEquals(4, initial.margins.top)
        assertEquals(3, initial.margins.bottom)
        assertEquals(listOf(Media.UiConfig.UiElement.CLOCK, Media.UiConfig.UiElement.PHONE_SIGNAL), initial.uiElementsList)
        val update = display.margins(1, 2, 3, 4)
        assertEquals(initial.uiElementsList, update.uiElementsList)
        assertFalse(update.hasUiTheme())
        assertEquals(0, GalDisplayContract(GalDisplaySettings(false, false, false), true).initial(0, 0)!!.uiElementsCount)
    }
}
