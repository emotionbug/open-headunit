package com.andrerinas.openheadunit.decoder.audio;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/** Offline comparison driver for the production JNI ABI, not an Android device benchmark. */
public final class NativeTimeStretch {
    static { System.loadLibrary("hur_time_stretch"); }
    private static native long open(int engine);
    private static native void bind(long handle, ByteBuffer input, ByteBuffer output);
    private static native void process(long handle, int frames, double tempo);
    private static native int receive(long handle);
    private static native void finish(long handle);
    private static native boolean finished(long handle);
    private static native void close(long handle);

    private static double tempo(String scenario, int sourceFrame) {
        return switch (scenario) {
            case "unity" -> 1.0;
            case "constant" -> 1.01;
            case "toggle" -> (sourceFrame / 4800) % 2 == 0 ? 1.0 : 1.01;
            case "wide-toggle" -> (sourceFrame / 4800) % 2 == 0 ? 0.5 : 2.0;
            default -> {
                if (!scenario.startsWith("matched:"))
                    throw new IllegalArgumentException("Expected unity, constant, toggle or matched:tempo");
                yield Double.parseDouble(scenario.substring(8));
            }
        };
    }
    private record Result(short[] samples, int frames, int firstOutputAfter, int beforeFinish,
                          double targetFrames, long cpuNs, long wallNs, long setupNs,
                          long[] calls) {}

    private static Result render(int engine, short[] source, String scenario, ThreadMXBean clock) {
        long setupStart = System.nanoTime();
        ByteBuffer input = ByteBuffer.allocateDirect(1920).order(ByteOrder.nativeOrder());
        ByteBuffer output = ByteBuffer.allocateDirect(1920).order(ByteOrder.nativeOrder());
        ShortBuffer inputPcm = input.asShortBuffer(), outputPcm = output.asShortBuffer();
        // The old R3 reference emits synthesis padding. Retain it, never truncate to target duration.
        short[] result = new short[Math.addExact(Math.multiplyExact(source.length, 2), 96000)];
        long[] calls = new long[(source.length / 2 + 479) / 480 + 1];
        long handle = open(engine);
        if (handle == 0) throw new IllegalStateException("Engine did not open");
        int written = 0, first = -1, call = 0;
        long cpu = 0, wall = 0;
        double target = 0;
        try {
            bind(handle, input, output);
            long setup = System.nanoTime() - setupStart;
            for (int position = 0; position < source.length / 2; position += 480) {
                int count = Math.min(480, source.length / 2 - position);
                double speed = tempo(scenario, position);
                target += count / speed;
                long cpuStart = clock.getCurrentThreadCpuTime(), wallStart = System.nanoTime();
                inputPcm.clear();
                inputPcm.put(source, position * 2, count * 2);
                process(handle, count, speed);
                int n;
                while ((n = receive(handle)) != 0) {
                    if (n < 0 || n > 480 || written + n * 2 > result.length)
                        throw new IllegalStateException("Invalid or unbounded native output");
                    outputPcm.position(0);
                    outputPcm.get(result, written, n * 2);
                    written += n * 2;
                    if (first < 0) first = position + count;
                }
                long elapsed = System.nanoTime() - wallStart;
                wall += elapsed;
                cpu += clock.getCurrentThreadCpuTime() - cpuStart;
                calls[call++] = elapsed;
            }
            int beforeFinish = written / 2;
            long cpuStart = clock.getCurrentThreadCpuTime(), wallStart = System.nanoTime();
            finish(handle);
            int n;
            while ((n = receive(handle)) != 0) {
                if (n < 0 || n > 480 || written + n * 2 > result.length)
                    throw new IllegalStateException("Invalid or unbounded native tail");
                outputPcm.position(0);
                outputPcm.get(result, written, n * 2);
                written += n * 2;
            }
            long elapsed = System.nanoTime() - wallStart;
            wall += elapsed;
            cpu += clock.getCurrentThreadCpuTime() - cpuStart;
            calls[call] = elapsed;
            if (!finished(handle)) throw new IllegalStateException("Native finalization did not complete");
            return new Result(result, written / 2, first, beforeFinish, target, cpu, wall, setup, calls);
        } finally {
            close(handle);
        }
    }
    private static long percentile(long[] values, double quantile) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[(int)Math.ceil((sorted.length - 1) * quantile)];
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("engine input.s16le output.s16le scenario");
        int engine = Integer.parseInt(args[0]);
        byte[] bytes = Files.readAllBytes(Path.of(args[1]));
        if (bytes.length == 0 || bytes.length % 4 != 0)
            throw new IllegalArgumentException("Expected nonempty interleaved stereo PCM16LE");
        short[] source = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(source);
        ThreadMXBean clock = ManagementFactory.getThreadMXBean();
        if (!clock.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("Thread CPU clock unavailable");
        clock.setThreadCpuTimeEnabled(true);
        render(engine, source, args[3], clock); // Same-signal warm-up is excluded from measurements.
        Result[] measured = new Result[3];
        for (int i = 0; i < measured.length; ++i) measured[i] = render(engine, source, args[3], clock);
        Result first = measured[0];
        for (Result other : measured) {
            if (first.frames != other.frames || !Arrays.equals(first.samples, other.samples))
                throw new IllegalStateException("Non-deterministic PCM across repeated renders");
        }
        ByteBuffer encoded = ByteBuffer.allocate(first.frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        encoded.asShortBuffer().put(first.samples, 0, first.frames * 2);
        Files.write(Path.of(args[2]), encoded.array());
        long[] cpu = Arrays.stream(measured).mapToLong(Result::cpuNs).toArray();
        long[] wall = Arrays.stream(measured).mapToLong(Result::wallNs).toArray();
        long[] p99 = Arrays.stream(measured).mapToLong(r -> percentile(r.calls, .99)).toArray();
        long[] max = Arrays.stream(measured).mapToLong(r -> percentile(r.calls, 1)).toArray();
        System.out.printf(Locale.ROOT,
            "{\"input_frames\":%d,\"output_frames\":%d,\"first_output_after_frames\":%d," +
            "\"frames_before_finish\":%d,\"ideal_output_frames\":%.6f,\"setup_ms\":%.6f," +
            "\"thread_cpu_ms_median\":%.6f,\"wall_ms_median\":%.6f," +
            "\"block_wall_p99_us_median\":%.6f,\"block_wall_max_us_worst\":%.6f,\"repeats\":3}%n",
            source.length / 2, first.frames, first.firstOutputAfter, first.beforeFinish, first.targetFrames,
            first.setupNs / 1e6, percentile(cpu, .5) / 1e6, percentile(wall, .5) / 1e6,
            percentile(p99, .5) / 1e3, percentile(max, 1) / 1e3);
    }
}
