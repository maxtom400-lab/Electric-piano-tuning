package com.example.bluetoothmotor;

public final class SafeMotorSessionTest {
    private static int checks;
    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks + " failed");
    }
    private static void rejected(Runnable action) {
        boolean rejected = false;
        try { action.run(); }
        catch (IllegalStateException | IllegalArgumentException expected) { rejected = true; }
        check(rejected);
    }
    private static SafeMotorSession ready() {
        SafeMotorSession s = new SafeMotorSession();
        s.connected();
        check(s.status("SAFE_V1", 0) == SafeMotorSession.Event.CAPABLE);
        return s;
    }
    private static void home(SafeMotorSession s, long now) {
        SafeMotorSession.Request r = s.home(now);
        check(r.hex().equals(String.format("A1F2%04X", r.id)));
        check(s.status("HOME_START:" + r.id, now + 1) == SafeMotorSession.Event.STARTED);
        check(s.status("HOME_DONE:" + r.id, now + 2) == SafeMotorSession.Event.HOME_DONE);
        check(s.homed() && s.pending() == null);
    }
    public static void main(String[] args) {
        SafeMotorSession s = new SafeMotorSession();
        rejected(() -> s.home(0));
        s.connected();
        rejected(() -> s.home(0));
        check(s.status("READY", 0) == SafeMotorSession.Event.IGNORED);
        check(!s.capable());
        check(s.status("SAFE_V1", 0) == SafeMotorSession.Event.CAPABLE);
        rejected(() -> s.move(2, 100, 0, 0));
        SafeMotorSession.Request h = s.home(0);
        check(h.id == 1 && h.hex().equals("A1F20001"));
        rejected(() -> s.home(1));
        check(s.status("HOME_DONE", 1) == SafeMotorSession.Event.IGNORED);
        check(!s.homed());
        check(s.status("HOME_DONE:2", 1) == SafeMotorSession.Event.IGNORED);
        check(s.status("HOME_START:1", 2) == SafeMotorSession.Event.STARTED);
        check(s.status("HOME_LOW_LIMIT:1", 3) == SafeMotorSession.Event.PROGRESS);
        check(s.status("HOME_BACKOFF:1", 4) == SafeMotorSession.Event.PROGRESS);
        check(s.status("HOME_DONE:1", 5) == SafeMotorSession.Event.HOME_DONE && s.homed());
        check(s.status("HOME_DONE:1", 6) == SafeMotorSession.Event.IGNORED);
        SafeMotorSession.Request m = s.move(2, 18000, 6000, 10);
        check(m.id == 2 && m.hex().equals("A1F102000217704650"));
        check(m.timeoutMs == 26630);
        check(s.status("MOVE_START:2", 11) == SafeMotorSession.Event.STARTED);
        check(s.status("MOVE_DONE:1", 12) == SafeMotorSession.Event.IGNORED && s.pending() == m);
        check(s.status("HOME_DONE:2", 13) == SafeMotorSession.Event.IGNORED && s.pending() == m);
        check(s.status("MOVE_DONE:2", 24000) == SafeMotorSession.Event.MOVE_DONE && s.homed());
        check(s.status("MOVE_DONE:2", 24001) == SafeMotorSession.Event.IGNORED);
        rejected(() -> s.move(0, 100, 0, 24002));
        rejected(() -> s.move(2, 20001, 0, 24002));
        rejected(() -> s.move(2, 0, 0, 24002));
        rejected(() -> s.move(2, 100, 10001, 24002));
        m = s.move(1, 100, 0, 25000);
        check(m.id == 3 && m.hex().equals("A1F101000300000064"));
        check(!s.expire(26999));
        check(s.expire(27000) && !s.homed() && s.pending() == null);
        check(s.status("MOVE_DONE:3", 27001) == SafeMotorSession.Event.IGNORED);
        rejected(() -> s.move(1, 100, 0, 27002));
        home(s, 28000);
        m = s.move(1, 100, 0, 29000);
        int moveId = m.id;
        check(s.status("MOVE_START:" + moveId, 29001) == SafeMotorSession.Event.STARTED);
        check(!s.expire(31599));
        check(s.expire(31600) && !s.homed());
        home(s, 32000);
        m = s.move(2, 100, 0, 33000);
        check(s.status("MOVE_DONE:" + m.id, 33001) == SafeMotorSession.Event.FAULT && !s.homed());
        home(s, 34000);
        m = s.move(2, 100, 0, 35000);
        check(s.status("MOVE_LIMIT:" + m.id, 35001) == SafeMotorSession.Event.FAULT && !s.homed());
        home(s, 36000);
        m = s.move(2, 100, 0, 37000);
        check(s.status("MOVE_ABORT:" + m.id, 37001) == SafeMotorSession.Event.FAULT);
        home(s, 38000);
        check(s.status("ERR_QUEUE", 38003) == SafeMotorSession.Event.FAULT && !s.homed());
        home(s, 39000);
        s.invalidate();
        check(!s.homed() && s.capable());
        home(s, 40000);
        s.disconnected();
        check(!s.homed() && !s.capable() && s.pending() == null);
        check(s.status("HOME_DONE:1", 41000) == SafeMotorSession.Event.IGNORED);
        s.connected();
        rejected(() -> s.home(41000));
        s.status("SAFE_V1", 41000);
        check(s.home(41001).id == 1); // caller must filter old GATT callbacks
        check(s.status("HOME_START:1", 41002) == SafeMotorSession.Event.STARTED);
        check(s.status("HOME_DONE:1", 41001 + SafeMotorSession.HOME_TIMEOUT_MS) == SafeMotorSession.Event.FAULT);

        SafeMotorSession edge = ready();
        edge.home(0);
        check(edge.status("HOME_DONE:bad", 1) == SafeMotorSession.Event.FAULT);
        edge.home(2);
        check(edge.status("HOME_DONE:0", 3) == SafeMotorSession.Event.FAULT);
        edge.home(4);
        check(edge.status("HOME_DONE:65536", 5) == SafeMotorSession.Event.FAULT);
        edge.home(6);
        check(edge.status("HOME_LOW_STUCK:4", 7) == SafeMotorSession.Event.FAULT);
        for (int id = 5; id <= 65535; id++) { edge.home(10); edge.invalidate(); }
        rejected(() -> edge.home(11));
        System.out.println("PASS: " + checks + " SAFE_V1 session/acknowledgement checks");
    }
}
