package com.example.bluetoothmotor;

public final class RatchetSessionTest {
    private static int checks;
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("Ratchet check " + (checks + 1));
        checks++;
    }
    private static void reject(Runnable task) {
        boolean failed = false;
        try { task.run(); } catch (IllegalStateException expected) { failed = true; }
        check(failed);
    }
    private static void home(SafeMotorSession s, long now) {
        SafeMotorSession.Request h = s.home(now);
        check(s.status("HOME_START:" + h.id, now + 1) == SafeMotorSession.Event.STARTED);
        check(s.status("HOME_DONE:" + h.id, now + 2) == SafeMotorSession.Event.HOME_DONE);
    }
    private static void high(SafeMotorSession s, long now) {
        SafeMotorSession.Request m = s.move(2, 1000, 0, now);
        check(s.status("MOVE_START:" + m.id, now + 1) == SafeMotorSession.Event.STARTED);
        check(s.status("MOVE_HIGH:" + (m.id + 1), now + 2) == SafeMotorSession.Event.IGNORED);
        check(s.status("MOVE_HIGH:" + m.id, now + 900) == SafeMotorSession.Event.HIGH_LIMIT);
        check(!s.homed() && s.ratchetEligible() && s.pending() == null);
    }
    public static void main(String[] args) {
        SafeMotorSession s = new SafeMotorSession();
        s.connected();
        s.status("SAFE_V2", 0);
        check(s.capable() && s.supportsRatchet() && !s.ratchetEligible());
        reject(() -> s.ratchet(0));
        home(s, 0);
        for (int cycle = 1; cycle <= 3; cycle++) {
            long now = cycle * 50000L;
            high(s, now);
            reject(() -> s.move(2, 100, 0, now + 901));
            SafeMotorSession.Request r = s.ratchet(now + 1000);
            check(r.kind == SafeMotorSession.Kind.RATCHET && r.hex().equals(String.format("A1F3%04X", r.id)));
            check(s.ratchetCycles() == cycle && !s.ratchetEligible());
            check(s.status("RATCH_DONE", now + 1001) == SafeMotorSession.Event.IGNORED);
            check(s.status("HOME_DONE:" + r.id, now + 1002) == SafeMotorSession.Event.IGNORED);
            check(s.status("RATCH_START:" + r.id, now + 1003) == SafeMotorSession.Event.STARTED);
            check(s.status("RATCH_DONE:" + r.id, now + 42000) == SafeMotorSession.Event.RATCHET_DONE);
            check(s.homed() && !s.ratchetEligible());
            check(s.status("RATCH_DONE:" + r.id, now + 42001) == SafeMotorSession.Event.IGNORED);
        }
        SafeMotorSession.Request m = s.move(2, 1000, 0, 200000);
        s.status("MOVE_START:" + m.id, 200001);
        check(s.status("MOVE_HIGH:" + m.id, 200900) == SafeMotorSession.Event.FAULT);
        reject(() -> s.ratchet(200901));
        home(s, 201000);
        check(s.ratchetCycles() == 0);
        high(s, 202000);
        SafeMotorSession.Request r = s.ratchet(203000);
        check(s.status("RATCH_DONE:" + r.id, 203001) == SafeMotorSession.Event.FAULT); // lost START
        home(s, 204000);
        high(s, 205000);
        r = s.ratchet(206000);
        s.status("RATCH_START:" + r.id, 206001);
        check(s.status("RATCH_ABORT:" + r.id, 206002) == SafeMotorSession.Event.FAULT);
        home(s, 207000);
        high(s, 208000);
        r = s.ratchet(209000);
        s.status("RATCH_START:" + r.id, 209001);
        check(s.expire(332000) && !s.homed() && !s.ratchetEligible());
        home(s, 333000);
        high(s, 334000);
        s.disconnected();
        reject(() -> s.ratchet(335000));
        s.connected();
        s.status("SAFE_V1", 336000);
        check(!s.supportsRatchet());
        home(s, 337000);
        m = s.move(2, 1000, 0, 338000);
        s.status("MOVE_START:" + m.id, 338001);
        check(s.status("MOVE_HIGH:" + m.id, 338900) == SafeMotorSession.Event.FAULT);
        reject(() -> s.ratchet(338901));
        s.status("SAFE_V2", 339000);
        home(s, 340000);
        m = s.move(1, 1000, 0, 341000);
        s.status("MOVE_START:" + m.id, 341001);
        check(s.status("MOVE_HIGH:" + m.id, 341900) == SafeMotorSession.Event.FAULT); // lowering/preload cannot authorize
        System.out.println("PASS: " + checks + " ratchet continuation session checks");
    }
}
