package com.andrerinas.openheadunit.aap.protocol
object AudioConfigs {
    fun get(channel: Int) = com.andrerinas.openheadunit.aap.protocol.proto.Media.AudioConfiguration()
    fun stream(channel: Int, separate: Boolean, media: Int, guidance: Int, system: Int) = media
}
