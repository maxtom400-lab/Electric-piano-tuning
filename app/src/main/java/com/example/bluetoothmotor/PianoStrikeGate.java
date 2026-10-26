package com.example.bluetoothmotor;

/** Acoustic restrike heuristic, not proof of the sound source being a piano. */
final class PianoStrikeGate {
    static final long QUIET_MS = 200;
    static final long ATTACK_RISE_MS = 300;
    static final long STABLE_DELAY_MS = 220;
    static final long ATTACK_WINDOW_MS = 2000;
    static final long MAX_FRAME_GAP_MS = 500;
    static final float QUIET_LEVEL = 0.06f;
    static final float ATTACK_LEVEL = 0.12f;
    private boolean armed;
    private long armedAt;
    private long settleMs;
    private long lastFrameAt = -1;
    private long quietSince = -1;
    private long quietReadyUntil = -1;
    private long attackAt = -1;

    void arm(long now, long settle) {
        armed = true;
        armedAt = now;
        settleMs = Math.max(0L, settle);
        lastFrameAt = -1;
        resetEnvelope();
    }
    void cancel() { armed = false; resetEnvelope(); }
    boolean armed() { return armed; }
    private void resetEnvelope() { quietSince = -1; quietReadyUntil = -1; attackAt = -1; }

    /** Returns true only on a newly observed attack; caller resets pitch stability. */
    boolean observe(long now, float level) {
        if (!armed) return false;
        if (lastFrameAt >= 0 && (now < lastFrameAt || now - lastFrameAt > MAX_FRAME_GAP_MS)) resetEnvelope();
        lastFrameAt = now;
        if (now < armedAt || now - armedAt < settleMs || Float.isNaN(level)
                || Float.isInfinite(level) || level < 0 || level > 1) {
            resetEnvelope();
            return false;
        }
        if (attackAt >= 0) {
            if (now - attackAt <= ATTACK_WINDOW_MS && level >= QUIET_LEVEL) return false;
            resetEnvelope();
        }
        if (level < QUIET_LEVEL) {
            if (quietSince < 0) quietSince = now;
            if (now - quietSince >= QUIET_MS) quietReadyUntil = now + ATTACK_RISE_MS;
            return false;
        }
        quietSince = -1;
        if (quietReadyUntil >= now && level >= ATTACK_LEVEL) {
            attackAt = now;
            quietReadyUntil = -1;
            return true;
        }
        if (now > quietReadyUntil) quietReadyUntil = -1;
        return false;
    }

    boolean acceptStable(long now) {
        if (!armed || attackAt < 0 || lastFrameAt < 0 || now < lastFrameAt
                || now - lastFrameAt > MAX_FRAME_GAP_MS
                || now - attackAt < STABLE_DELAY_MS || now - attackAt > ATTACK_WINDOW_MS) return false;
        cancel(); // one stable measurement per quiet/attack cycle, never a decay tail
        return true;
    }
}
