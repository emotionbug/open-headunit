package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media

/** Parsing a request is not a promise to execute the feature it asks for. */
internal enum class GalFeatureSupport { UNSUPPORTED, INSPECT_ONLY, IMPLEMENTED }

internal data class GalOptionsPolicy(
    val embedded: GalFeatureSupport,
    val standalone: GalFeatureSupport
)

internal class GalMediaPolicy internal constructor(
    private val audio: GalOptionsPolicy,
    private val video: GalOptionsPolicy,
    val avSynchronization: GalFeatureSupport = GalFeatureSupport.UNSUPPORTED,
    val mediaStatistics: GalFeatureSupport = GalFeatureSupport.UNSUPPORTED
) {
    fun optionsFor(channel: Int): GalOptionsPolicy = when {
        Channel.isAudio(channel) -> audio
        channel == Channel.ID_VID -> video
        else -> NO_OPTIONS
    }

    fun unimplementedRequests(options: Media.MediaOptions): List<String> = buildList {
        if (options.enableAvSync && avSynchronization != GalFeatureSupport.IMPLEMENTED) add("A/V synchronization")
        if (options.enableMediaStats && mediaStatistics != GalFeatureSupport.IMPLEMENTED) add("media statistics")
    }

    companion object {
        val NO_OPTIONS = GalOptionsPolicy(GalFeatureSupport.UNSUPPORTED, GalFeatureSupport.UNSUPPORTED)
    }
}

/**
 * Built once after the final version response. Only this factory maps versions to receiver
 * behavior. Handlers consume policies, while the actual Start still owns ACK mode and format.
 * Display settings are captured before negotiation so opening settings cannot alter this contract.
 */
internal class GalConnectionContract private constructor(
    val negotiation: GalSessionProfile,
    val media: GalMediaPolicy,
    val display: GalDisplayContract
) {
    companion object {
        fun resolve(negotiation: GalSessionProfile, display: GalDisplaySettings): GalConnectionContract {
            require(negotiation.finalResponse.accepted &&
                negotiation.finalResponse.version >= negotiation.requestedVersion)
            val none = GalMediaPolicy.NO_OPTIONS
            val embedded = GalOptionsPolicy(GalFeatureSupport.INSPECT_ONLY, GalFeatureSupport.UNSUPPORTED)
            val updates = GalOptionsPolicy(GalFeatureSupport.INSPECT_ONLY, GalFeatureSupport.INSPECT_ONLY)
            // 6.1 has no distinct media/display branch in the inspected sender. Share its policy
            // with 6.0, but retain its negotiated identity. New versions need an explicit entry.
            val (media, modernDisplay) = when (negotiation.profile) {
                GalProfile.COMPATIBILITY, GalProfile.GAL_1_6 -> GalMediaPolicy(none, none) to false
                GalProfile.GAL_5_0 -> GalMediaPolicy(embedded, none) to true
                GalProfile.GAL_6_0, GalProfile.GAL_6_1 -> GalMediaPolicy(updates, updates) to true
            }
            return GalConnectionContract(negotiation, media, GalDisplayContract(display, modernDisplay))
        }
    }
}

/** Format entries come from the protobuf actually sent, never from settings read again at Start. */
internal sealed class GalMediaFormat {
    data class Audio(val value: Media.AudioConfiguration) : GalMediaFormat()
    data class Video(val value: Control.Service.MediaSinkService.VideoConfiguration) : GalMediaFormat()
}

internal class GalMediaOffer private constructor(private val sinks: Map<Int, List<GalMediaFormat>>) {
    fun indices(channel: Int): List<Int> = sinks[channel]?.indices?.toList() ?: emptyList()
    fun format(channel: Int, index: Int): GalMediaFormat? = sinks[channel]?.getOrNull(index)

    companion object {
        fun from(discovery: Control.ServiceDiscoveryResponse): GalMediaOffer {
            val sinks = mutableMapOf<Int, List<GalMediaFormat>>()
            for (service in discovery.servicesList) {
                if (!service.hasMediaSinkService()) continue // Includes microphone source exclusion.
                val sink = service.mediaSinkService
                val formats = when {
                    Channel.isAudio(service.id) -> sink.audioConfigsList.map { GalMediaFormat.Audio(it) }
                    service.id == Channel.ID_VID -> sink.videoConfigsList.map { GalMediaFormat.Video(it) }
                    else -> continue
                }
                require(!sinks.containsKey(service.id)) { "Duplicate media sink ${service.id}" }
                sinks[service.id] = formats
            }
            return GalMediaOffer(sinks.toMap())
        }
    }
}
