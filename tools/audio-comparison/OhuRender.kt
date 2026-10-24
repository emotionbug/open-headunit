package com.andrerinas.openheadunit.decoder.audio

import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Production overlap selector, isolated with the mixer's 5ms blend and recovery pacing.
 * Deliberately not a simulation of network admission, startup, PLC or output hardware.
 */
private data class OhuResult(val samples: ShortArray, val count: Int, val cpu: Long,
                             val wall: Long, val calls: LongArray)

private fun render(source: ShortArray, scenario: String): OhuResult {
    val selector = PcmOverlap(optimized = true)
    val output = ShortArray(source.size)
    val calls = ArrayList<Long>()
    val clock = ManagementFactory.getThreadMXBean()
    var head = 0
    var written = 0
    var next = 0L
    var deferrals = 0
    var cpu = 0L
    var wall = 0L
    while (head + 1056 <= source.size) {
        val now = written / 2 / 48L
        val allowed = when (scenario) {
            "constant" -> true
            "unity" -> false
            "toggle" -> (head / 2 / 4800) % 2 != 0
            else -> error("Expected unity, constant or toggle")
        }
        val c0 = clock.currentThreadCpuTime
        val w0 = System.nanoTime()
        var shift = if (allowed && now >= next) selector.shift(source, head, 2, 240, 48) else 0
        if (shift > 0 && selector.protectAttack && deferrals < 3) {
            deferrals++
            shift = 0
        } else if (shift > 0 || !allowed || now < next) deferrals = 0
        System.arraycopy(source, head + shift * 2, output, written, 960)
        if (shift > 0) {
            for (frame in 0 until 240) {
                val mix = (frame + 1).toFloat() / 240
                for (channel in 0..1) {
                    val offset = frame * 2 + channel
                    output[written + offset] = (source[head + offset] * (1f - mix) +
                        output[written + offset] * mix).toInt().toShort()
                }
            }
            val pacing = if (selector.matched) shift else 48
            next = now + maxOf(1L, (maxOf(shift, pacing) * 100_000L + 47999) / 48000)
        }
        val elapsed = System.nanoTime() - w0
        cpu += clock.currentThreadCpuTime - c0
        wall += elapsed
        calls.add(elapsed)
        head += 960 + shift * 2
        written += 960
    }
    System.arraycopy(source, head, output, written, source.size - head)
    written += source.size - head
    return OhuResult(output, written, cpu, wall, calls.toLongArray())
}

fun main(args: Array<String>) {
    require(args.size == 3) { "input.s16le output.s16le scenario" }
    val bytes = File(args[0]).readBytes()
    require(bytes.isNotEmpty() && bytes.size % 4 == 0)
    val source = ShortArray(bytes.size / 2)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(source)
    val clock = ManagementFactory.getThreadMXBean()
    check(clock.isCurrentThreadCpuTimeSupported)
    clock.isThreadCpuTimeEnabled = true
    render(source, args[2])
    val runs = List(3) { render(source, args[2]) }
    val first = runs.first()
    check(runs.all { it.count == first.count && it.samples.contentEquals(first.samples) })
    val encoded = ByteBuffer.allocate(first.count * 2).order(ByteOrder.LITTLE_ENDIAN)
    encoded.asShortBuffer().put(first.samples, 0, first.count)
    File(args[1]).writeBytes(encoded.array())
    fun percentile(a: LongArray, q: Double): Long =
        if (a.isEmpty()) 0L else a.sorted()[kotlin.math.ceil((a.size - 1) * q).toInt()]
    val cpu = percentile(runs.map { it.cpu }.toLongArray(), .5) / 1e6
    val wall = percentile(runs.map { it.wall }.toLongArray(), .5) / 1e6
    val p99 = percentile(runs.map { percentile(it.calls, .99) }.toLongArray(), .5) / 1e3
    val max = runs.maxOf { percentile(it.calls, 1.0) } / 1e3
    println(String.format(Locale.ROOT,
        "{\"input_frames\":%d,\"output_frames\":%d,\"thread_cpu_ms_median\":%.6f," +
            "\"wall_ms_median\":%.6f,\"block_wall_p99_us_median\":%.6f," +
            "\"block_wall_max_us_worst\":%.6f,\"repeats\":3}",
        source.size / 2, first.count / 2, cpu, wall, p99, max))
}
