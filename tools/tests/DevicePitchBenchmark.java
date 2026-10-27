package com.example.bluetoothmotor;

import java.util.Arrays;
import java.util.Locale;

/** app_process diagnostic: synthetic PCM only, no Android UI/audio/BLE/motor APIs. */
public final class DevicePitchBenchmark {
    private static volatile double sink;
    private static double percentile(double[] samples, double fraction) {
        double[] sorted = samples.clone(); Arrays.sort(sorted);
        return sorted[(int) ((sorted.length - 1) * fraction)];
    }
    public static void main(String[] args) {
        FftPitchDetectorTest.main(args);
        PcmFrameBufferTest.main(args);
        FftPitchDetector detector = new FftPitchDetector();
        short[][] frames = new short[16][];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = FftPitchDetectorTest.tone(27.5 * Math.pow(2, i % 8), i * .27,
                    new double[]{0.3, 1, 0.6, 0.25}, 0.005, 2, 0);
        }
        for (int i = 0; i < 160; i++) {
            short[] pcm = frames[i % frames.length];
            sink += detector.detect(pcm, pcm.length) + LegacyYinBaseline.detect(pcm, pcm.length);
        }
        double[] oldTime = new double[96], newTime = new double[96];
        for (int i = 0; i < oldTime.length; i++) {
            short[] pcm = frames[i % frames.length];
            for (int pass = 0; pass < 2; pass++) {
                boolean old = ((i + pass) & 1) == 0;
                long start = System.nanoTime();
                sink += old ? LegacyYinBaseline.detect(pcm, pcm.length) : detector.detect(pcm, pcm.length);
                double micros = (System.nanoTime() - start) / 1000.0;
                if (old) oldTime[i] = micros; else newTime[i] = micros;
            }
        }
        System.out.printf(Locale.US, "Android ART synthetic core: 96 timed frames each%n"
                + "old median %.1f us / p95 %.1f us; FFT median %.1f us / p95 %.1f us; ratio %.2fx%n",
                percentile(oldTime, .5), percentile(oldTime, .95), percentile(newTime, .5), percentile(newTime, .95),
                percentile(oldTime, .5) / percentile(newTime, .5));
        System.out.println("PASS: synthetic device core diagnostic; microphone/BLE/motor NOT accessed");
    }
}
