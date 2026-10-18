package com.andrerinas.openheadunit.aap

/** GAL is independent of Android's API level, the AA APK version and the wireless bootstrap. */
internal data class GalVersion(val major: Int, val minor: Int) : Comparable<GalVersion> {
    override fun compareTo(other: GalVersion): Int =
        compareValuesBy(this, other, GalVersion::major, GalVersion::minor)
    override fun toString(): String = "$major.$minor"
}

/**
 * Only the listed profiles can be selected; higher profiles are experimental. A phone's high reply is evidence about the peer,
 * not permission to turn on every feature up to that number. 6.1 shares the 6.0 media contract;
 * the inspected AA18 paths have no separate 6.1 media gate.
 */
internal enum class GalProfile(val version: GalVersion) {
    COMPATIBILITY(GalVersion(1, 2)),
    GAL_1_6(GalVersion(1, 6)),
    GAL_5_0(GalVersion(5, 0)),
    GAL_6_0(GalVersion(6, 0)),
    GAL_6_1(GalVersion(6, 1));

    companion object {
        fun fromSetting(value: String): GalProfile = entries.firstOrNull { it.version.toString() == value }
            ?: COMPATIBILITY

        fun select(maximum: GalProfile, reported: GalVersion): GalProfile =
            entries.lastOrNull { it.version <= maximum.version && it.version <= reported }
                ?: COMPATIBILITY
    }
}

/** Immutable once the final response is accepted; all service handlers share this snapshot. */
internal data class GalSessionProfile(
    val profile: GalProfile,
    val baselineResponse: AapVersionNegotiation.Result,
    val probeResponse: AapVersionNegotiation.Result?,
    val finalResponse: AapVersionNegotiation.Result
) {
    val requestedVersion get() = profile.version
}
