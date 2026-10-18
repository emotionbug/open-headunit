package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.ProjectionMargins
import org.junit.Assert.*
import org.junit.Test

class GalDisplayGeometryTest {
    @Test fun `initial and updated odd margins keep the same pixels at every edge`() {
        val contract = galTestContract().display
        for (w in listOf(0, 1, 2, 101, Int.MAX_VALUE)) {
            for (h in listOf(0, 1, 2, 103, Int.MAX_VALUE)) {
                val initial = contract.initial(w, h)!!
                val update = contract.margins(ProjectionMargins.left(w), ProjectionMargins.top(h),
                    ProjectionMargins.right(w), ProjectionMargins.bottom(h))
                assertEquals(initial, update)
                assertEquals(w, initial.margins.left + initial.margins.right)
                assertEquals(h, initial.margins.top + initial.margins.bottom)
                assertTrue(initial.margins.top >= initial.margins.bottom)
            }
        }
    }

    @Test fun `return to primary restores geometry and notifies layout once`() {
        val config = galTestDiscovery(videoFallback = true).servicesList.last().mediaSinkService
        val old = HeadUnitScreenConfig.negotiatedResolutionType
        var notifications = 0
        try {
            HeadUnitScreenConfig.negotiatedResolutionType = config.videoConfigsList[0].codecResolution
            HeadUnitScreenConfig.recordAnnouncedFallback(config.videoConfigsList[1].codecResolution, 11, 13)
            HeadUnitScreenConfig.onFallbackAdopted = { notifications++ }
            assertTrue(HeadUnitScreenConfig.selectAnnouncedVideo(1, config.videoConfigsList[1]))
            assertEquals(800, HeadUnitScreenConfig.getNegotiatedWidth())
            assertTrue(HeadUnitScreenConfig.selectAnnouncedVideo(0, config.videoConfigsList[0]))
            assertEquals(1280, HeadUnitScreenConfig.getNegotiatedWidth())
            assertFalse(HeadUnitScreenConfig.selectAnnouncedVideo(0, config.videoConfigsList[0]))
            assertEquals(2, notifications)
        } finally {
            HeadUnitScreenConfig.onFallbackAdopted = null
            HeadUnitScreenConfig.recordAnnouncedFallback(null, 0, 0)
            HeadUnitScreenConfig.negotiatedResolutionType = old
        }
    }
}
