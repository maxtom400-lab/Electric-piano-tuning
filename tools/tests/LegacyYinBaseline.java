package com.example.bluetoothmotor;

/** Frozen pre-FFT implementation from b5d6629, bugs retained for comparison only.
 * Not compiled into the Android app. */
final class LegacyYinBaseline {
    private static final int SAMPLE_RATE = 44100;
    private static final int PITCH_BUFFER_SIZE = 4096;
    private static final double MIN_PIANO_FREQ = 27.5;
    private static final double MAX_PIANO_FREQ = 4186.0;
    static double detect(short[] input, int size) {
        int n = Math.min(size, PITCH_BUFFER_SIZE);
        double mean = 0;
        for (int i = 0; i < n; i++) {
            mean += input[i];
        }
        mean /= n;

        double rms = 0;
        double[] samples = new double[n];
        for (int i = 0; i < n; i++) {
            samples[i] = (input[i] - mean) / 32768.0;
            rms += samples[i] * samples[i];
        }
        rms = Math.sqrt(rms / n);
        if (rms < 0.01) {
            return 0;
        }

        int tauMin = Math.max(2, (int) (SAMPLE_RATE / MAX_PIANO_FREQ));
        int tauMax = Math.min(n - 2, (int) (SAMPLE_RATE / MIN_PIANO_FREQ));
        double[] yin = new double[tauMax + 1];
        for (int tau = 1; tau <= tauMax; tau++) {
            double sum = 0;
            for (int i = 0; i < n - tau; i++) {
                double delta = samples[i] - samples[i + tau];
                sum += delta * delta;
            }
            yin[tau] = sum;
        }

        double runningSum = 0;
        int bestTau = -1;
        for (int tau = 1; tau <= tauMax; tau++) {
            runningSum += yin[tau];
            yin[tau] = runningSum == 0 ? 1 : yin[tau] * tau / runningSum;
            if (tau >= tauMin && yin[tau] < 0.12) {
                while (tau + 1 <= tauMax && yin[tau + 1] < yin[tau]) {
                    tau++;
                }
                bestTau = tau;
                break;
            }
        }

        if (bestTau < 0) {
            double best = 1.0;
            for (int tau = tauMin; tau <= tauMax; tau++) {
                if (yin[tau] < best) {
                    best = yin[tau];
                    bestTau = tau;
                }
            }
            if (best > 0.35) {
                return 0;
            }
        }

        double betterTau = bestTau;
        if (bestTau > 1 && bestTau < tauMax) {
            double left = yin[bestTau - 1];
            double center = yin[bestTau];
            double right = yin[bestTau + 1];
            double divisor = 2 * (2 * center - left - right);
            if (Math.abs(divisor) > 1e-9) {
                betterTau = bestTau + (right - left) / divisor;
            }
        }

        double frequency = SAMPLE_RATE / betterTau;
        return frequency >= MIN_PIANO_FREQ && frequency <= MAX_PIANO_FREQ ? frequency : 0;
    }
}
