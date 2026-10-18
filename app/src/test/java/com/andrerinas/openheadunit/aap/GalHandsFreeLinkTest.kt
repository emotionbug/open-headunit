package com.andrerinas.openheadunit.aap

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class GalHandsFreeLinkTest {
    @Test fun `reads head unit client peers and releases proxy after disconnection callback`() {
        val context = mock<Context>()
        val adapter = mock<BluetoothAdapter>()
        val device = mock<BluetoothDevice> { on { address } doReturn "AA:BB:CC:DD:EE:FF" }
        val profile = mock<BluetoothProfile> { on { connectedDevices } doReturn listOf(device) }
        whenever(adapter.getProfileProxy(eq(context), any(), eq(16))).thenAnswer {
            val listener = it.getArgument<BluetoothProfile.ServiceListener>(1)
            listener.onServiceConnected(16, profile)
            listener.onServiceDisconnected(16)
            true
        }
        assertNull(GalHandsFreeLink().connectedPhones(context, adapter))
        verify(profile, never()).connectedDevices
        verify(adapter).closeProfileProxy(16, profile)
        verify(adapter, never()).getProfileProxy(any(), any(), eq(BluetoothProfile.HEADSET))
    }

    @Test fun `missing callback is attempted only once per transport`() {
        val context = mock<Context>()
        val adapter = mock<BluetoothAdapter>()
        whenever(adapter.getProfileProxy(any(), any(), eq(16))).thenReturn(true)
        val link = GalHandsFreeLink()
        assertNull(link.connectedPhones(context, adapter))
        assertNull(link.connectedPhones(context, adapter))
        verify(adapter, times(1)).getProfileProxy(eq(context), any(), eq(16))
    }

    @Test fun `late callback releases proxy rather than publishing a retired lookup`() {
        val context = mock<Context>()
        val adapter = mock<BluetoothAdapter>()
        val profile = mock<BluetoothProfile>()
        val listener = argumentCaptor<BluetoothProfile.ServiceListener>()
        whenever(adapter.getProfileProxy(eq(context), listener.capture(), eq(16))).thenReturn(false)
        assertNull(GalHandsFreeLink().connectedPhones(context, adapter))
        listener.firstValue.onServiceConnected(16, profile)
        verify(adapter).closeProfileProxy(16, profile)
        verify(profile, never()).connectedDevices
    }
}
