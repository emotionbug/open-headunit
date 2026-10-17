package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VideoBufferPipelineTest {
    @Test fun `retained frame cannot be overwritten by the next message`() {
        val pool = VideoBufferPool()
        val received = pool.acquire(100)
        received.data.fill(42)
        val feeder = received.retain()
        received.release()
        val next = pool.acquire(100)
        assertNotSame(feeder.data, next.data)
        next.data.fill(7)
        assertEquals(42, feeder.data[0].toInt())
        feeder.release()
        val reused = pool.acquire(100)
        assertSame(received.data, reused.data)
        next.release()
        reused.release()
    }

    @Test fun `payload slice copies only valid bytes into codec input at zero`() {
        val pool = VideoBufferPool()
        val input = pool.acquire(100)
        input.data.fill(99)
        val payload = byteArrayOf(0, 0, 0, 1, 65, 12, 13)
        payload.copyInto(input.data, 10)
        val codec = ByteBuffer.allocateDirect(32)
        codec.put(ByteBuffer.wrap(input.data, 10, payload.size))
        input.release() // codec owns its independent bytes now
        val recycled = pool.acquire(100)
        recycled.data.fill(7)
        val actual = ByteArray(payload.size)
        codec.flip()
        codec.get(actual)
        assertArrayEquals(payload, actual)
        assertEquals(payload.size, codec.limit())
        recycled.release()
    }

    @Test fun `assembly detach and growth preserve a queued frame`() {
        val pool = VideoBufferPool()
        val assembly = VideoAssemblyBuffer(pool, 16384)
        val bytes = ByteArray(6000) { (it % 251).toByte() }
        assertTrue(assembly.append(bytes, 0, 4000))
        assertTrue(assembly.append(bytes, 4000, 2000))
        val completed = assembly.detach()
        assertTrue(assembly.append(ByteArray(6000) { 9 }, 0, 6000))
        assertArrayEquals(bytes, completed.data.copyOf(6000))
        assertFalse(assembly.append(ByteArray(16384), 0, 16384))
        assertEquals(6000, assembly.size)
        assembly.clear()
        completed.release()
    }

    @Test fun `large keyframe storage does not inflate small frame allocations`() {
        val pool = VideoBufferPool(maxBytes = 1024 * 1024)
        pool.acquire(900_000).release()
        val small = pool.acquire(100)
        assertEquals(4096, small.data.size)
        small.release()
        assertTrue(pool.retainedBytes <= 1024 * 1024)
    }

    @Test fun `late feeder release cannot refill a closed pool`() {
        val pool = VideoBufferPool()
        val frame = pool.acquire(100).retain()
        frame.release()
        pool.close()
        frame.release()
        assertEquals(0, pool.retainedBytes)
    }

    @Test fun `byte ceiling paces a producer before the frame count ceiling`() {
        val queue = VideoFrameQueue<ByteArray>(30, 16) { it.size }
        val first = ByteArray(12)
        assertTrue(queue.offer(first))
        assertFalse(queue.offer(ByteArray(8)))
        assertEquals(12L, queue.retainedBytes)
        assertSame(first, queue.poll())
        assertTrue(queue.offer(ByteArray(8)))
        assertEquals(8L, queue.retainedBytes)
    }

    @Test fun `close wakes blocked producer and never admits into replacement`() {
        val queue = VideoFrameQueue<ByteArray>(1, 16) { it.size }
        val first = ByteArray(8)
        assertTrue(queue.offer(first))
        val entered = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val producer = Thread {
            entered.countDown()
            accepted.set(queue.offer(ByteArray(8), 10, TimeUnit.SECONDS))
        }
        producer.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val drained = queue.closeAndDrain()
        producer.join(1000)
        assertFalse(producer.isAlive)
        assertFalse(accepted.get())
        assertEquals(listOf(first), drained)
        assertEquals(0L, queue.retainedBytes)
        assertFalse(queue.offer(ByteArray(1)))
        assertNull(queue.poll())
        val replacement = VideoFrameQueue<ByteArray>(1, 16) { it.size }
        assertTrue(replacement.offer(ByteArray(1)))
    }

    @Test fun `FIFO pressure preserves every compressed reference frame`() {
        val queue = VideoFrameQueue<ByteArray>(2, 2) { it.size }
        val received = ArrayList<Int>()
        val consumer = Thread {
            repeat(500) {
                received.add(checkNotNull(queue.poll(5, TimeUnit.SECONDS))[0].toInt() and 255)
            }
        }
        consumer.start()
        repeat(500) { assertTrue(queue.offer(byteArrayOf((it % 251).toByte()), 5, TimeUnit.SECONDS)) }
        consumer.join(5000)
        assertFalse(consumer.isAlive)
        assertEquals((0 until 500).map { it % 251 }, received)
        assertEquals(0L, queue.retainedBytes)
    }

    @Test fun `queue report measures time before video worker starts`() {
        val timing = VideoQueueTiming()
        assertNull(timing.onStart(0, 100_000_000, 4096))
        val report = checkNotNull(timing.onStart(5_000_000_000, 5_600_000_000, 8192))
        assertTrue(report.contains("waitMaxMs=600"))
        assertTrue(report.contains("waitAvgMs=350"))
        assertTrue(report.contains("retainedMaxBytes=8192"))
    }
}
