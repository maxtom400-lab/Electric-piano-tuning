package com.example.bluetoothmotor;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Synthetic signal tests, NOT microphone, piano, voice rejection or hardware validation. */
public final class FftPitchDetectorTest {
    private static int checks, failures;
    private static double worstPure, worstHarmonic;
    private static final FftPitchDetector detector = new FftPitchDetector();
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) { if (failures++ < 16) System.err.println(message); }
    }
    private static double cents(double actual, double expected) {
        return actual > 0 ? 1200 * Math.log(actual / expected) / Math.log(2) : Double.POSITIVE_INFINITY;
    }
    private static double hz(int midi) { return 440 * Math.pow(2, (midi - 69) / 12.0); }
    static short[] tone(double f, double phase, double[] harmonics, double noise, double decay, double b) {
        Random random = new Random(1759);
        short[] pcm = new short[4096];
        double scale = 0;
        for (double a : harmonics) scale += a;
        for (int i = 0; i < pcm.length; i++) {
            double t = i / 44100.0, value = 0;
            for (int h = 1; h <= harmonics.length; h++) {
                // Fundamental remains f, stretched partials use a deliberately simple model.
                double partial = f * h * Math.sqrt((1 + b * h * h) / (1 + b));
                if (partial < 21000) value += harmonics[h - 1] * Math.sin(2 * Math.PI * partial * t + phase * h);
            }
            value = 0.55 * value / scale * Math.exp(-decay * t) + noise * random.nextGaussian();
            pcm[i] = (short) Math.round(Math.max(-0.99, Math.min(0.99, value)) * 32767);
        }
        return pcm;
    }
    private static double accuracy(double f, short[] pcm, double tolerance, String group) {
        double actual = detector.detect(pcm, pcm.length), error = Math.abs(cents(actual, f));
        check(error <= tolerance, String.format(Locale.US, "%s %.3f Hz -> %.3f Hz (%.3f cents, confidence %.3f)",
                group, f, actual, error, detector.confidence()));
        check(detector.confidence() >= 0 && detector.confidence() <= 1, "Confidence bounds");
        return error;
    }
    public static void main(String[] args) {
        for (int midi = 21; midi <= 108; midi++) {
            for (double detune : new double[]{-35, 0, 27}) for (double phase : new double[]{0, 0.7, 1.8, 3.0}) {
                double f = hz(midi) * Math.pow(2, detune / 1200);
                worstPure = Math.max(worstPure, accuracy(f, tone(f, phase, new double[]{1}, 0, 0, 0), 1.0, "Pure"));
            }
            for (double phase : new double[]{0.2, 1.4}) {
                double f = hz(midi);
                worstHarmonic = Math.max(worstHarmonic, accuracy(f,
                        tone(f, phase, new double[]{0.25, 1, 0.6, 0.3, 0.16}, 0, 2, 0), 3, "Harmonics"));
            }
        }
        for (double f : new double[]{24.6, 27, 55, 110, 261.63, 440, 880, 1760, 4186, 4690}) {
            accuracy(f, tone(f, 0.8, new double[]{1, 0.4, 0.2}, 0.015, 2, 0), 5, "Noise/decay");
        }
        for (double f : new double[]{55, 110, 220, 440, 880}) {
            accuracy(f, tone(f, 1.1, new double[]{0, 1, 0.7, 0.35}, 0.003, 1, 0), 5, "Missing fundamental");
            accuracy(f, tone(f, 0.4, new double[]{0.18, 1, 0.02}, 0.001, 0, 0), 5, "Dominant second");
            accuracy(f, tone(f, 0.6, new double[]{1, 0.45, 0.2}, 0.003, 2, 0.0001), 6, "Mild stretched partials");
        }
        Random holdout = new Random(284941);
        for (int trial = 0; trial < 160; trial++) {
            double f = 24.6 * Math.pow(4700 / 24.6, holdout.nextDouble());
            double[] partials = {0.3 + holdout.nextDouble(), 0.3 + holdout.nextDouble(),
                    holdout.nextDouble(), holdout.nextDouble() * 0.4};
            short[] pcm = tone(f, holdout.nextDouble() * Math.PI * 2, partials,
                    0.004, holdout.nextDouble() * 3, 0);
            for (int i = 0; i < pcm.length; i++) pcm[i] += 1000; // A realistic small DC offset.
            accuracy(f, pcm, 3, "Seeded holdout");
        }
        for (double f : new double[]{1000, 1760, 3000, 4186}) {
            accuracy(f, tone(f, 1.3, new double[]{0, 1, 0.7, 0.35}, 0.003, 1, 0), 5, "High missing fundamental");
        }
        short[] zeros = new short[4096];
        check(detector.detect(zeros, zeros.length) == 0 && detector.confidence() == 0, "Silence rejected");
        Arrays.fill(zeros, (short) 14000);
        check(detector.detect(zeros, zeros.length) == 0, "DC rejected");
        Random random = new Random(4378);
        for (int trial = 0; trial < 12; trial++) {
            short[] noise = new short[4096];
            for (int i = 0; i < noise.length; i++) noise[i] = (short) (random.nextGaussian() * 4000);
            check(detector.detect(noise, noise.length) == 0, "White noise rejected " + trial);
        }
        for (int trial = 0; trial < 8; trial++) {
            short[] colored = new short[4096];
            double memory = 0;
            for (int i = 0; i < colored.length; i++) {
                memory = 0.7 * memory + random.nextGaussian() * 2200;
                colored[i] = (short) memory;
            }
            check(detector.detect(colored, colored.length) == 0, "Colored noise rejected " + trial);
        }
        short[] impulse = new short[4096];
        impulse[1100] = 30000;
        check(detector.detect(impulse, impulse.length) == 0, "Isolated transient rejected");
        for (double f : new double[]{15, 5200, 8000}) {
            check(detector.detect(tone(f, 0.5, new double[]{1}, 0, 0, 0), 4096) == 0, "Outside detector band " + f);
        }
        short[] weak = tone(440, 0, new double[]{1}, 0, 0, 0);
        for (int i = 0; i < weak.length; i++) weak[i] /= 100;
        check(detector.detect(weak, weak.length) == 0, "Below RMS floor rejected");
        short[] rail = new short[4096];
        for (int i = 0; i < rail.length; i++) rail[i] = i % 100 < 50 ? Short.MAX_VALUE : Short.MIN_VALUE;
        check(detector.detect(rail, rail.length) == 0, "Clipped capture rejected");
        check(detector.detect(null, 4096) == 0, "Null input rejected");
        check(detector.detect(new short[4], 4096) == 0, "Short array rejected");
        check(detector.detect(new short[4096], 1024) == 0, "Partial frame rejected");
        check(detector.detect(new short[4096], -1) == 0, "Invalid count rejected");
        // Recover immediately: no previous pitch/selected target is fed into the estimator.
        accuracy(440, tone(440, 0, new double[]{1}, 0, 0, 0), 1, "Recovery");
        accuracy(233.08, tone(233.08, 0, new double[]{1}, 0, 0, 0), 1, "Independent next key");
        // Compare all difference lags against the direct O(N*tau) expression.
        short[] audit = tone(173.4, 0.6, new double[]{1, 0.3, 0.7}, 0.02, 3, 0);
        detector.detect(audit, audit.length);
        for (int lag = 1; lag <= 1839; lag++) {
            double sum = 0;
            for (int i = 0; i < audit.length - lag; i++) {
                double d = (audit[i] - (double) audit[i + lag]) / 32768.0;
                sum += d * d;
            }
            check(Math.abs(sum / (audit.length - lag) - detector.differenceAt(lag)) < 1e-10, "Linear FFT difference at lag " + lag);
        }
        System.out.printf(Locale.US, "Worst synthetic pure %.4f cents; harmonic/decay %.4f cents%n", worstPure, worstHarmonic);
        if (failures > 0) throw new AssertionError(failures + " / " + checks + " pitch checks failed");
        System.out.println("PASS: " + checks + " synthetic FFT pitch checks (not real-piano validation)");
    }
}
