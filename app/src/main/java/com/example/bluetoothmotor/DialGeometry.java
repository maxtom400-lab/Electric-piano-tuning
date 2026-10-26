package com.example.bluetoothmotor;

/** Display geometry only; never changes measured cents or motor calibration. */
final class DialGeometry {
    static final double DEGREES_PER_CENT = 20.0 / 100.0;
    static final double MAX_DISPLAY_CENTS = 200.0;
    // Locked pixel centers measured from the original black alignment dots.
    // Keep these coordinates when the dial artwork changes or covers its dot.
    static final float DIAL_PIVOT_X = 514.5f;
    static final float DIAL_PIVOT_Y = 512.5f;
    static final float NEEDLE_PIVOT_X = 40.5f;
    static final float NEEDLE_PIVOT_Y = 401.5f;
    static final float NEEDLE_REACH_RATIO = 0.86f;

    private DialGeometry() {}

    static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    static float angleDegrees(double cents) {
        if (!finite(cents)) return 0f;
        // Positive Android Canvas rotation is clockwise: sharp right, flat left.
        return (float) (Math.max(-MAX_DISPLAY_CENTS, Math.min(MAX_DISPLAY_CENTS, cents)) * DEGREES_PER_CENT);
    }

    static float origin(float screenPivot, float imagePivot, float scale) {
        return screenPivot - imagePivot * scale;
    }

    static float needleScale(float dialRadius) {
        return Math.max(0f, dialRadius) * NEEDLE_REACH_RATIO / NEEDLE_PIVOT_Y;
    }
}
