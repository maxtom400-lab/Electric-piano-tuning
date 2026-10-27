package com.example.bluetoothmotor;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Reproducible host comparison. Timings are observations, never a hardware claim/test gate. */
public final class PitchBenchmark {
    private static volatile double sink;
    private static double error(double actual, double expected) {
        return actual > 0 ? Math.abs(1200 * Math.log(actual / expected) / Math.log(2)) : Double.POSITIVE_INFINITY;
    }
    private static double percentile(double[] values, double fraction) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.floor((sorted.length - 1) * fraction)];
    }
    private static void accuracy(FftPitchDetector detector) {
        double[] oldErrors = new double[264], newErrors = new double[264];
        int index = 0, oldRejected = 0, newRejected = 0, oldOctaves = 0, newOctaves = 0;
        for (int midi = 21; midi <= 108; midi++) for (int kind = 0; kind < 3; kind++) {
            double expected = 440 * Math.pow(2, (midi - 69) / 12.0);
            short[] pcm = FftPitchDetectorTest.tone(expected, 0.73,
                    kind == 0 ? new double[]{1} : new double[]{0.25, 1, 0.6, 0.3, 0.16},
                    kind == 2 ? 0.008 : 0, kind == 0 ? 0 : 2, 0);
            double oldHz = LegacyYinBaseline.detect(pcm, pcm.length);
            double newHz = detector.detect(pcm, pcm.length);
            oldErrors[index] = error(oldHz, expected);
            newErrors[index++] = error(newHz, expected);
            if (oldHz == 0) oldRejected++;
            else if (error(oldHz, expected) > 600) oldOctaves++;
            if (newHz == 0) newRejected++;
            else if (error(newHz, expected) > 600) newOctaves++;
        }
        System.out.printf(Locale.US, "264-frame synthetic comparison (88 keys x pure/harmonic/noisy):%n"
                + "  frozen old YIN: median %.3f cents, p95 %.3f cents, rejected %d, >600-cent errors %d%n"
                + "  new FFT core:   median %.3f cents, p95 %.3f cents, rejected %d, >600-cent errors %d%n",
                percentile(oldErrors, .5), percentile(oldErrors, .95), oldRejected, oldOctaves,
                percentile(newErrors, .5), percentile(newErrors, .95), newRejected, newOctaves);
    }
    public static void main(String[] args) {
        FftPitchDetector detector = new FftPitchDetector();
        short[][] frames = new short[24][];
        for (int i = 0; i < frames.length; i++) {
            double f = 27.5 * Math.pow(2, (i % 8) * 12 / 12.0);
            frames[i] = FftPitchDetectorTest.tone(f, i * .27,
                    new double[]{0.3, 1, 0.6, 0.25}, 0.005, 2, 0);
        }
        for (int i = 0; i < 240; i++) {
            short[] pcm = frames[i % frames.length];
            sink += detector.detect(pcm, pcm.length) + LegacyYinBaseline.detect(pcm, pcm.length);
        }
        double[] oldMicros = new double[240], newMicros = new double[240];
        for (int i = 0; i < 240; i++) {
            short[] pcm = frames[i % frames.length];
            // Alternate order to reduce systematic JIT/scheduler bias.
            for (int pass = 0; pass < 2; pass++) {
                boolean old = ((i + pass) & 1) == 0;
                long start = System.nanoTime();
                sink += old ? LegacyYinBaseline.detect(pcm, pcm.length) : detector.detect(pcm, pcm.length);
                double micros = (System.nanoTime() - start) / 1000.0;
                if (old) oldMicros[i] = micros; else newMicros[i] = micros;
            }
        }
        System.out.printf(Locale.US, "Host %s / Java %s; 240 timed 4096-sample frames per implementation%n"
                + "  old YIN median %.1f us / p95 %.1f us; FFT median %.1f us / p95 %.1f us; median speed ratio %.2fx%n",
                System.getProperty("os.arch"), System.getProperty("java.version"),
                percentile(oldMicros, .5), percentile(oldMicros, .95), percentile(newMicros, .5), percentile(newMicros, .95),
                percentile(oldMicros, .5) / percentile(newMicros, .5));
        Object bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean) {
            com.sun.management.ThreadMXBean allocation = (com.sun.management.ThreadMXBean) bean;
            if (allocation.isThreadAllocatedMemorySupported()) {
                allocation.setThreadAllocatedMemoryEnabled(true);
                long id = Thread.currentThread().getId();
                long before = allocation.getThreadAllocatedBytes(id);
                for (int i = 0; i < 240; i++) sink += detector.detect(frames[i % frames.length], 4096);
                long bytes = allocation.getThreadAllocatedBytes(id) - before;
                System.out.printf(Locale.US, "  new core warmed allocation %.1f bytes/frame (HotSpot counter)%n", bytes / 240.0);
            }
        }
        accuracy(detector);
    }
}
