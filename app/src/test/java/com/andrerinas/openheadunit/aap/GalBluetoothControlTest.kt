package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Bluetooth
import com.andrerinas.openheadunit.aap.protocol.proto.Common.MessageStatus
import com.andrerinas.openheadunit.aap.protocol.proto.Control.BluetoothPairingMethod
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class GalBluetoothControlTest {
    private class Queue : AbstractExecutorService() {
        val tasks = java.util.ArrayDeque<Runnable>()
        var stopped = false
        override fun execute(command: Runnable) { tasks.add(command) }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun run() { while (tasks.isNotEmpty()) tasks.remove().run() }
    }
    private fun request() = AapMessage(Channel.ID_BTH, 0x8001,
        Bluetooth.BluetoothPairingRequest.newBuilder().setPhoneAddress("AA:BB:CC:DD:EE:02")
            .setPairingMethod(BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN).build())

    @Test fun `one pending lookup and close prevents both stale lookup and stale response`() {
        val queue = Queue()
        val sent = mutableListOf<AapMessage>()
        var lookups = 0
        val handler = AapControlBluetooth(mock(), { message, current -> if (current()) sent.add(message) }, queue) { _, _ ->
            lookups++
            GalBluetoothPairing.Result(MessageStatus.STATUS_SUCCESS, true)
        }
        handler.advertise("AA:BB:CC:DD:EE:01")
        repeat(20) { handler.execute(request()) }
        assertEquals(1, queue.tasks.size)
        assertEquals(0, lookups)
        handler.closeChannel()
        queue.run()
        assertTrue(sent.isEmpty())
        assertEquals(0, lookups)
        handler.execute(request())
        queue.run()
        assertEquals(1, lookups)
        assertEquals(1, sent.size)
        assertTrue(sent.single().parse(Bluetooth.BluetoothPairingResponse.newBuilder()).build().alreadyPaired)
        handler.shutdown()
    }

    @Test fun `close invalidates a completed reply still queued on the transport writer`() {
        val queue = Queue()
        val writer = mutableListOf<() -> Unit>()
        val sent = mutableListOf<AapMessage>()
        val handler = AapControlBluetooth(mock(), { message, current ->
            writer.add { if (current()) sent.add(message) }
        }, queue) { _, _ -> GalBluetoothPairing.Result(MessageStatus.STATUS_SUCCESS, true) }
        handler.advertise("AA:BB:CC:DD:EE:01")
        handler.execute(request())
        queue.run()
        assertEquals(1, writer.size)
        handler.closeChannel()
        writer.forEach { it() }
        assertTrue(sent.isEmpty())
        handler.shutdown()
    }

    @Test fun `permission denial becomes unavailable and teardown during lookup cannot reply`() {
        val queue = Queue()
        val sent = mutableListOf<AapMessage>()
        var closeDuringLookup = false
        lateinit var handler: AapControlBluetooth
        handler = AapControlBluetooth(mock(), { message, current -> if (current()) sent.add(message) }, queue) { _, _ ->
            if (closeDuringLookup) handler.closeChannel()
            throw SecurityException("Permission denied")
        }
        handler.advertise("AA:BB:CC:DD:EE:01")
        handler.execute(request()); queue.run()
        assertEquals(MessageStatus.STATUS_BLUETOOTH_UNAVAILABLE,
            sent.single().parse(Bluetooth.BluetoothPairingResponse.newBuilder()).build().status)
        sent.clear()
        closeDuringLookup = true
        handler.execute(request()); queue.run()
        assertTrue(sent.isEmpty())
        handler.shutdown()
    }
}
