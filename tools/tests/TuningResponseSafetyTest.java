package com.example.bluetoothmotor;

public final class TuningResponseSafetyTest {
    private static int checks;

    private static void check(TuningResponseSafety.Result wanted, double expected, double actual) {
        TuningResponseSafety.Result got = TuningResponseSafety.evaluate(expected, actual);
        if (got != wanted) {
            throw new AssertionError(expected + "/" + actual + ": expected " + wanted + ", got " + got);
        }
        checks++;
    }

    public static void main(String[] args) {
        check(TuningResponseSafety.Result.CONSISTENT, 5, 4);
        check(TuningResponseSafety.Result.CONSISTENT, -5, -4);
        check(TuningResponseSafety.Result.OPPOSITE, 5, -1);
        check(TuningResponseSafety.Result.OPPOSITE, -5, 1);
        check(TuningResponseSafety.Result.TOO_SMALL, 5, 0);
        check(TuningResponseSafety.Result.TOO_SMALL, 5, -0.19);
        check(TuningResponseSafety.Result.CONSISTENT, 1, 0.2);
        check(TuningResponseSafety.Result.EXCESSIVE, 1, 4.01);
        check(TuningResponseSafety.Result.CONSISTENT, 1, 4);
        check(TuningResponseSafety.Result.EXCESSIVE, -1, -4.01);
        check(TuningResponseSafety.Result.CONSISTENT, 0.1, 2);
        check(TuningResponseSafety.Result.EXCESSIVE, 0.1, 2.01);
        check(TuningResponseSafety.Result.INVALID, 0, 1);
        check(TuningResponseSafety.Result.INVALID, Double.NaN, 1);
        check(TuningResponseSafety.Result.INVALID, 1, Double.POSITIVE_INFINITY);
        check(TuningResponseSafety.Result.INVALID, Double.NEGATIVE_INFINITY, 1);
        System.out.println("PASS: " + checks + " tuning response safety checks");
    }
}
