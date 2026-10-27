package com.example.bluetoothmotor;

import java.util.Arrays;

/** Single-thread-owned, allocation-free per frame. No selected-key or motor input.
 * FFT linear autocorrelation accelerates a YIN-style, overlap-corrected difference
 * function. This is a monophonic periodicity detector, NOT piano/source recognition.
 * Research: de Cheveigne & Kawahara, JASA 111 (2002), doi:10.1121/1.1458024.
 */
final class FftPitchDetector {
    static final int FRAME_SIZE = 4096;
    static final int SAMPLE_RATE = 44100;
    // Include detuned A0/C8, not only the exact equal-temperament endpoints.
    static final double MIN_FREQUENCY = 24.0;
    static final double MAX_FREQUENCY = 4800.0;
    private static final int FFT_SIZE = FRAME_SIZE * 2; // Zero padding prevents circular wrap.
    private static final int MAX_LAG = (int) Math.ceil(SAMPLE_RATE / MIN_FREQUENCY);
    private static final int MIN_LAG = (int) (SAMPLE_RATE / MAX_FREQUENCY);
    private final double[] samples = new double[FRAME_SIZE];
    private final double[] prefixEnergy = new double[FRAME_SIZE + 1];
    private final double[] prefixSum = new double[FRAME_SIZE + 1];
    private final double[] real = new double[FFT_SIZE];
    private final double[] imaginary = new double[FFT_SIZE];
    private final double[] cos = new double[FFT_SIZE / 2];
    private final double[] sin = new double[FFT_SIZE / 2];
    private final double[] hann = new double[FRAME_SIZE];
    private final double[] difference = new double[MAX_LAG + 2];
    private final double[] normalized = new double[MAX_LAG + 2];
    private final double[] correlationLoss = new double[MAX_LAG + 2];
    private final int[] reversed = new int[FFT_SIZE];
    private double confidence;
    private double spectralMaximum;

    FftPitchDetector() {
        int bits = Integer.numberOfTrailingZeros(FFT_SIZE);
        for (int i = 0; i < FFT_SIZE; i++) reversed[i] = Integer.reverse(i) >>> (32 - bits);
        for (int i = 0; i < cos.length; i++) {
            cos[i] = Math.cos(2.0 * Math.PI * i / FFT_SIZE);
            sin[i] = Math.sin(2.0 * Math.PI * i / FFT_SIZE);
        }
        for (int i = 0; i < FRAME_SIZE; i++) hann[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FRAME_SIZE - 1));
    }

    double confidence() { return confidence; }
    // Package-private numerical audit of the FFT against a direct difference sum.
    double differenceAt(int lag) { return difference[lag]; }

    double detect(short[] pcm, int size) {
        confidence = 0;
        if (pcm == null || size != FRAME_SIZE || pcm.length < size) return 0;
        double mean = 0;
        int clipped = 0;
        for (int i = 0; i < size; i++) {
            mean += pcm[i];
            if (pcm[i] >= 32760 || pcm[i] <= -32760) clipped++;
        }
        if (clipped > size / 100) return 0;
        mean /= size;
        prefixEnergy[0] = 0;
        prefixSum[0] = 0;
        for (int i = 0; i < size; i++) {
            double x = (pcm[i] - mean) / 32768.0;
            samples[i] = x;
            real[i] = x;
            prefixEnergy[i + 1] = prefixEnergy[i] + x * x;
            prefixSum[i + 1] = prefixSum[i] + x;
        }
        if (prefixEnergy[size] / size < 0.0001) return 0; // DC-removed RMS < 0.01.
        Arrays.fill(real, size, FFT_SIZE, 0);
        Arrays.fill(imaginary, 0);
        fft(false);
        for (int i = 0; i < FFT_SIZE; i++) {
            real[i] = real[i] * real[i] + imaginary[i] * imaginary[i];
            imaginary[i] = 0;
        }
        fft(true);
        double running = 0;
        normalized[0] = 1;
        for (int lag = 1; lag <= MAX_LAG + 1; lag++) {
            double energy = prefixEnergy[size - lag] + prefixEnergy[size] - prefixEnergy[lag];
            // Divide by the number of pairs: long lags must not win by using fewer samples.
            difference[lag] = Math.max(0, energy - 2 * real[lag]) / (size - lag);
            running += difference[lag];
            normalized[lag] = running > 1e-20 ? difference[lag] * lag / running : 1;
            double pairs = size - lag;
            double leftSum = prefixSum[size - lag], rightSum = prefixSum[size] - prefixSum[lag];
            double leftEnergy = prefixEnergy[size - lag] - leftSum * leftSum / pairs;
            double rightEnergy = prefixEnergy[size] - prefixEnergy[lag] - rightSum * rightSum / pairs;
            double norm = Math.sqrt(Math.max(0, leftEnergy * rightEnergy));
            correlationLoss[lag] = norm > 1e-20 ? 1 - (real[lag] - leftSum * rightSum / pairs) / norm : 1;
        }
        // All lags are normalized BEFORE picking/interpolating any valley.
        int chosen = -1;
        for (int lag = MIN_LAG; lag <= MAX_LAG; lag++) {
            if (isValley(lag) && normalized[lag] < 0.12) { chosen = lag; break; }
        }
        if (chosen < 0) {
            double best = 0.18;
            for (int lag = MIN_LAG; lag <= MAX_LAG; lag++) {
                if (isValley(lag) && normalized[lag] < best) { best = normalized[lag]; chosen = lag; }
            }
        }
        if (chosen < 0) return 0;
        prepareSpectrum();
        // A strong overtone can make a half/third-period dip. Demand a material
        // improvement at its integer multiple, not tiny sampling-roundoff gains.
        double score = valleyScore(chosen);
        int first = chosen;
        for (int multiple = 2; multiple <= 3; multiple++) {
            int center = first * multiple;
            int radius = Math.max(multiple + 1, (int) (center * 0.02));
            for (int lag = Math.max(MIN_LAG, center - radius); lag <= Math.min(MAX_LAG, center + radius); lag++) {
                if (isValley(lag)) {
                    double next = valleyScore(lag);
                    if (next < score * 0.35 && score - next > 0.025
                            && harmonicDivisor(SAMPLE_RATE / (double) lag) == 1) { chosen = lag; score = next; }
                }
            }
        }
        // Locally normalized correlation removes amplitude-decay bias in low notes.
        int local = chosen;
        int localRadius = Math.max(1, (int) (chosen * 0.015));
        for (int i = Math.max(MIN_LAG, chosen - localRadius); i <= Math.min(MAX_LAG, chosen + localRadius); i++) {
            if (correlationLoss[i] < correlationLoss[local]) local = i;
        }
        double lag = local + parabola(correlationLoss[local - 1], correlationLoss[local], correlationLoss[local + 1]);
        double frequency = SAMPLE_RATE / lag;
        if (frequency < MIN_FREQUENCY || frequency > MAX_FREQUENCY) return 0;
        confidence = Math.max(0, Math.min(1, 1 - score));
        // Resolve period multiples using the GCD of observed partial indices. For
        // example 3/6/9 are evidence for 3*f, not an invented fundamental at f.
        int divisor = harmonicDivisor(frequency);
        if (divisor == 0) { confidence = 0; return 0; }
        if (divisor > 1) {
            if (frequency * divisor > MAX_FREQUENCY) { confidence = 0; return 0; }
            frequency = supportedRefinement(frequency * divisor, 100);
            if (frequency == 0) { confidence = 0; return 0; }
        } else if (frequency >= 500) {
            // High notes need spectral interpolation, not a sample-grid-biased lag.
            double refined = supportedRefinement(frequency, 20);
            if (refined == 0) { confidence = 0; return 0; }
            frequency = refined;
        }
        if (frequency < MIN_FREQUENCY || frequency > MAX_FREQUENCY) { confidence = 0; return 0; }
        return frequency;
    }

    private boolean isValley(int lag) {
        return normalized[lag] <= normalized[lag - 1] && normalized[lag] < normalized[lag + 1];
    }
    private double valleyScore(int lag) {
        double offset = parabola(normalized[lag - 1], normalized[lag], normalized[lag + 1]);
        return Math.max(0, normalized[lag] - 0.25 * (normalized[lag - 1] - normalized[lag + 1]) * offset);
    }
    private static double parabola(double left, double center, double right) {
        double denominator = left - 2 * center + right;
        if (Math.abs(denominator) <= 1e-20) return 0;
        return Math.max(-0.5, Math.min(0.5, 0.5 * (left - right) / denominator));
    }

    private void prepareSpectrum() {
        for (int i = 0; i < FRAME_SIZE; i++) real[i] = samples[i] * hann[i];
        Arrays.fill(real, FRAME_SIZE, FFT_SIZE, 0);
        Arrays.fill(imaginary, 0);
        fft(false);
        spectralMaximum = 0;
        for (int i = 1; i < FFT_SIZE / 2; i++) {
            real[i] = real[i] * real[i] + imaginary[i] * imaginary[i];
            spectralMaximum = Math.max(spectralMaximum, real[i]);
        }
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int next = a % b; a = b; b = next; }
        return a;
    }

    private int harmonicDivisor(double f) {
        int divisor = 0;
        int maximum = Math.min(32, (int) (21000 / f));
        for (int harmonic = 1; harmonic <= maximum; harmonic++) {
            if (fundamentalPeak(f * harmonic, 35, 0.005) > 0) {
                divisor = gcd(divisor, harmonic);
                if (divisor == 1) return 1;
            }
        }
        return divisor;
    }

    private double supportedRefinement(double estimate, double allowedCents) {
        double fundamental = fundamentalPeak(estimate, allowedCents, 0.02);
        if (fundamental > 0) return fundamental;
        // Missing/weak fundamental: at least two coprime partial indices must
        // agree. Stretched/ambiguous partials must not silently pull the result.
        double sum = 0, minimum = Double.POSITIVE_INFINITY, maximum = 0;
        int count = 0, divisor = 0;
        for (int harmonic = 2; harmonic <= 6 && estimate * harmonic < 21000; harmonic++) {
            double partial = fundamentalPeak(estimate * harmonic, allowedCents, 0.005);
            if (partial == 0) continue;
            double f = partial / harmonic;
            minimum = Math.min(minimum, f); maximum = Math.max(maximum, f);
            sum += f; count++; divisor = gcd(divisor, harmonic);
        }
        return count >= 2 && divisor == 1 && 1200 * Math.log(maximum / minimum) / Math.log(2) <= 3
                ? sum / count : 0;
    }

    private double fundamentalPeak(double estimate, double allowedCents, double minimumPower) {
        int bin = (int) Math.round(estimate * FFT_SIZE / SAMPLE_RATE);
        int radius = Math.max(3, (int) Math.ceil(bin * (Math.pow(2, allowedCents / 1200) - 1)) + 1);
        double bestPower = spectralMaximum * minimumPower, result = 0;
        for (int peak = Math.max(2, bin - radius); peak <= Math.min(FFT_SIZE / 2 - 2, bin + radius); peak++) {
            if (real[peak] < bestPower || real[peak] <= real[peak - 1] || real[peak] <= real[peak + 1]) continue;
            double delta = parabola(Math.log(Math.max(1e-30, real[peak - 1])),
                    Math.log(Math.max(1e-30, real[peak])), Math.log(Math.max(1e-30, real[peak + 1])));
            double refined = (peak + delta) * SAMPLE_RATE / FFT_SIZE;
            double changeCents = 1200.0 * Math.log(refined / estimate) / Math.log(2);
            if (Math.abs(changeCents) <= allowedCents) { bestPower = real[peak]; result = refined; }
        }
        return result;
    }

    private void fft(boolean inverse) {
        for (int i = 0; i < FFT_SIZE; i++) {
            int j = reversed[i];
            if (i < j) {
                double t = real[i]; real[i] = real[j]; real[j] = t;
                t = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = t;
            }
        }
        for (int length = 2; length <= FFT_SIZE; length <<= 1) {
            int half = length / 2, stride = FFT_SIZE / length;
            for (int start = 0; start < FFT_SIZE; start += length) {
                for (int j = 0; j < half; j++) {
                    int a = start + j, b = a + half, twiddle = j * stride;
                    double s = inverse ? sin[twiddle] : -sin[twiddle];
                    double re = real[b] * cos[twiddle] - imaginary[b] * s;
                    double im = real[b] * s + imaginary[b] * cos[twiddle];
                    real[b] = real[a] - re; imaginary[b] = imaginary[a] - im;
                    real[a] += re; imaginary[a] += im;
                }
            }
        }
        if (inverse) for (int i = 0; i < FFT_SIZE; i++) { real[i] /= FFT_SIZE; imaginary[i] /= FFT_SIZE; }
    }
}
