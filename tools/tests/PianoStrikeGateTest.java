package com.example.bluetoothmotor;

public final class PianoStrikeGateTest {
    private static int checks;
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("Strike gate check " + (checks + 1));
        checks++;
    }
    private static void quiet(PianoStrikeGate g, long now) {
        check(!g.observe(now, 0.01f));
        check(!g.observe(now + 100, 0.02f));
        check(!g.observe(now + 200, 0.01f));
    }
    public static void main(String[] args) {
        PianoStrikeGate g = new PianoStrikeGate();
        check(!g.observe(0, 0.5f) && !g.acceptStable(500));
        g.arm(0, 450);
        check(!g.observe(100, 0.01f));
        check(!g.observe(300, 0.01f));
        check(!g.observe(450, 0.5f)); // a tail during settle cannot arm
        for (long t = 500; t <= 2000; t += 100) check(!g.observe(t, 0.5f));
        check(!g.acceptStable(2000));
        quiet(g, 2100);
        check(g.observe(2400, 0.4f));
        check(!g.acceptStable(2500));
        check(!g.observe(2600, 0.3f));
        check(!g.observe(2620, 0.3f));
        check(g.acceptStable(2620) && !g.armed());
        check(!g.acceptStable(2700));
        check(!g.observe(2800, 0.8f));
        g.arm(3000, 0);
        quiet(g, 3000);
        check(!g.observe(3300, 0.08f));
        check(g.observe(3400, 0.4f)); // rise within allowed window
        g.cancel();
        check(!g.acceptStable(3700));
        g.arm(4000, 0);
        quiet(g, 4000);
        check(!g.observe(4400, 0.08f));
        check(!g.observe(4600, 0.08f));
        check(!g.observe(4700, 0.4f)); // slow background rise, not a new attack
        g.arm(5000, 0);
        quiet(g, 5000);
        check(!g.observe(5800, 0.4f)); // missed frames reset silence evidence
        check(!g.acceptStable(6100));
        quiet(g, 6200);
        check(g.observe(6500, 0.4f));
        check(!g.observe(6600, 0.01f)); // transient ends before stable pitch
        check(!g.acceptStable(6800));
        g.arm(7000, 0);
        quiet(g, 7000);
        check(g.observe(7300, 0.3f));
        for (long t = 7400; t <= 9400; t += 100) check(!g.observe(t, 0.2f));
        check(!g.acceptStable(9400)); // sustained sound cannot renew attack window
        g.arm(10000, 0);
        quiet(g, 10000);
        check(!g.observe(10300, Float.NaN));
        check(!g.observe(10400, 0.5f));
        quiet(g, 10500);
        check(!g.observe(10800, Float.POSITIVE_INFINITY));
        check(!g.observe(10900, 0.5f));
        g.arm(11000, 0);
        quiet(g, 11000);
        check(!g.observe(10900, 0.5f)); // backwards clock resets evidence
        check(!g.acceptStable(11500));
        System.out.println("PASS: " + checks + " acoustic strike gate checks");
    }
}
