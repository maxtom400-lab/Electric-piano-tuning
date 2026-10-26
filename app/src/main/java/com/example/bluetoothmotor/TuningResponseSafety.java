package com.example.bluetoothmotor;

/** Conservative remeasurement gate. Never infers/reverses the motor wiring. */
final class TuningResponseSafety {
    enum Result { CONSISTENT, TOO_SMALL, OPPOSITE, EXCESSIVE, INVALID }

    private TuningResponseSafety() {}

    static Result evaluate(double expectedDelta, double actualDelta) {
        if (Double.isNaN(expectedDelta) || Double.isInfinite(expectedDelta)
                || Double.isNaN(actualDelta) || Double.isInfinite(actualDelta)
                || Math.abs(expectedDelta) < 0.1) {
            return Result.INVALID;
        }
        // Below this floor, neither direction nor calibration can be trusted.
        if (Math.abs(actualDelta) < 0.2) {
            return Result.TOO_SMALL;
        }
        if (Math.signum(actualDelta) != Math.signum(expectedDelta)) {
            return Result.OPPOSITE;
        }
        // Allow a noise floor for tiny moves, but reject implausible responses.
        if (Math.abs(actualDelta) > Math.max(2.0, Math.abs(expectedDelta) * 4.0)) {
            return Result.EXCESSIVE;
        }
        return Result.CONSISTENT;
    }
}
