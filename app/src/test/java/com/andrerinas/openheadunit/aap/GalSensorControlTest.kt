package com.andrerinas.openheadunit.aap

import android.content.Context
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.messages.SensorEvent
import com.andrerinas.openheadunit.aap.protocol.proto.Common
import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class GalSensorControlTest {
    @Test fun `stop acknowledges success without broadcasting or priming another sensor value`() {
        val context = mock<Context>()
        val transport = mock<AapTransport>()
        val state = GalSensorSubscriptions<SensorEvent>().apply {
            advertise(setOf(Sensors.SensorType.NIGHT_VALUE))
            request(Sensors.SensorType.NIGHT_VALUE, 0)
        }
        whenever(transport.sensorSubscriptions).thenReturn(state)
        val control = AapControlSensor(transport, context, mock<Settings>())
        val request = Sensors.SensorRequest.newBuilder().setType(Sensors.SensorType.NIGHT).setMinUpdatePeriod(-1).build()
        control.execute(AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_STARTREQUEST_VALUE, request))
        assertTrue(state.activeTypes().isEmpty())
        val response = argumentCaptor<AapMessage>()
        verify(transport).send(response.capture())
        assertEquals(Common.MessageStatus.STATUS_SUCCESS,
            response.firstValue.parse(Sensors.SensorResponse.newBuilder()).build().status)
        verify(transport, never()).send(any<SensorEvent>())
        verifyNoInteractions(context)
        clearInvocations(transport)
        control.execute(AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_STARTREQUEST_VALUE,
            request.toBuilder().setType(Sensors.SensorType.LOCATION).setMinUpdatePeriod(0).build()))
        verify(transport).send(response.capture())
        assertEquals(Common.MessageStatus.STATUS_INVALID_SENSOR,
            response.lastValue.parse(Sensors.SensorResponse.newBuilder()).build().status)
        verifyNoInteractions(context)
    }
}
