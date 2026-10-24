package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AudioTimeCorrectionModeTest {
    @Test fun `saved values and JNI engine IDs identify the independent algorithms`() {
        assertEquals(listOf("ohu", "wsola", "phase-vocoder", "phase-vocoder-short", "phase-gradient"),
            AudioTimeCorrectionMode.entries.map { it.preferenceValue })
        assertEquals(listOf(0, 1, 2, 3, 4), AudioTimeCorrectionMode.entries.map { it.nativeEngine })
        for (mode in AudioTimeCorrectionMode.entries) {
            assertEquals(mode, AudioTimeCorrectionMode.fromPreference(mode.preferenceValue))
        }
    }

    @Test fun `missing unknown and removed preferences reset to OHU`() {
        for (saved in listOf(null, "", "auto", "unknown", "soundtouch", "r3-standard", "r3-short")) {
            assertEquals("saved=$saved", AudioTimeCorrectionMode.OHU,
                AudioTimeCorrectionMode.fromPreference(saved))
        }
    }

    @Test fun `native engines apply only to supported PCM media sinks`() {
        for (selected in AudioTimeCorrectionMode.entries) {
            for (media in listOf(false, true)) for (pcm in listOf(false, true)) {
                for (api in listOf(16, 20, 21, 23, 37)) for (available in listOf(false, true)) {
                    var probes = 0
                    val effective = selected.forSink(media, pcm, api) { probes++; available }
                    val eligible = selected != AudioTimeCorrectionMode.OHU && media && pcm && api >= 21
                    assertEquals("selected=$selected media=$media pcm=$pcm api=$api available=$available",
                        if (eligible && available) selected else AudioTimeCorrectionMode.OHU, effective)
                    assertEquals("ineligible sinks must not load the native library", if (eligible) 1 else 0, probes)
                }
            }
        }
    }

}
