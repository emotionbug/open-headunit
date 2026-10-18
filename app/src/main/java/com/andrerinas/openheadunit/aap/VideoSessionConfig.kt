package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.utils.Settings

/** Decoder preference is fixed with discovery; editing settings applies to the next link. */
internal data class VideoSessionConfig(
    val codec: String,
    val forceSoftware: Boolean,
    val softwareDecoder: Settings.SoftwareVideoDecoder
) {
    companion object {
        fun from(settings: Settings) = VideoSessionConfig(settings.videoCodec,
            settings.forceSoftwareDecoding, settings.softwareVideoDecoder)
    }
}
