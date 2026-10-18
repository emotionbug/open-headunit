package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media

/** The connection's display policy supplies a complete, version-appropriate update. */
class UpdateUiConfigRequest(uiConfig: Media.UiConfig) : AapMessage(
    Channel.ID_VID,
    Media.MsgType.MEDIA_MESSAGE_UPDATE_UI_CONFIG_REQUEST_VALUE,
    Media.UpdateUiConfigRequest.newBuilder().setUiConfig(uiConfig).build()
)
