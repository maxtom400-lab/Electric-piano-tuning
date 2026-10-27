package com.example.bluetoothmotor;

/** Accumulates partial AudioRecord reads without reusing stale tail samples.
 * Single capture-thread owner; no allocation after construction. */
final class PcmFrameBuffer {
    private final short[] samples;
    private final long maxGapMs;
    private int filled;
    private long firstReadMs = -1, lastReadMs = -1;
    private boolean discontinuity;

    PcmFrameBuffer(int size, long maxGapMs) {
        if (size <= 0 || maxGapMs <= 0) throw new IllegalArgumentException("Invalid frame configuration");
        samples = new short[size];
        this.maxGapMs = maxGapMs;
    }
    short[] samples() { return samples; }
    int offset() { return filled; }
    int remaining() { return samples.length - filled; }

    /** Caller has just written count samples at offset(). True means one full frame. */
    boolean commitRead(int count, long now) {
        if (count <= 0 || count > remaining() || now < 0) throw new IllegalArgumentException("Invalid PCM read");
        boolean stale = filled > 0 && (now < lastReadMs || now - lastReadMs > maxGapMs
                || now - firstReadMs > maxGapMs);
        if (stale) {
            // Retain only this new read, never the samples from before the gap.
            System.arraycopy(samples, filled, samples, 0, count);
            filled = 0;
            discontinuity = true;
        }
        if (filled == 0) firstReadMs = now;
        filled += count;
        lastReadMs = now;
        return filled == samples.length;
    }
    boolean discontinuity() { return discontinuity; }
    void consume() {
        if (filled != samples.length) throw new IllegalStateException("Incomplete PCM frame");
        filled = 0;
        firstReadMs = lastReadMs = -1;
        discontinuity = false;
    }
}
