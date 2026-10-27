package com.example.bluetoothmotor;

public final class PcmFrameBufferTest {
    private static int checks;
    private static void check(boolean ok) { if (!ok) throw new AssertionError("PCM check " + (checks + 1)); checks++; }
    private static boolean read(PcmFrameBuffer f, int count, long now, int startValue) {
        for (int i = 0; i < count; i++) f.samples()[f.offset() + i] = (short) (startValue + i);
        return f.commitRead(count, now);
    }
    private static void invalid(Runnable call) {
        try { call.run(); throw new AssertionError("Invalid call accepted"); }
        catch (IllegalArgumentException | IllegalStateException expected) { checks++; }
    }
    public static void main(String[] args) {
        for (int chunk : new int[]{1, 127, 512, 1024, 4096}) {
            PcmFrameBuffer f = new PcmFrameBuffer(4096, 500);
            short[] storage = f.samples();
            int total = 0;
            while (f.remaining() > 0) {
                int count = Math.min(chunk, f.remaining());
                check(read(f, count, total / 44, total) == (total + count == 4096));
                total += count;
            }
            check(f.samples() == storage && !f.discontinuity());
            for (int i = 0; i < 4096; i++) check(f.samples()[i] == i);
            invalid(() -> f.commitRead(1, 100));
            f.consume();
            check(f.offset() == 0 && f.remaining() == 4096 && f.samples() == storage);
            check(read(f, 4096, 200, 9000));
            check(f.samples()[0] == 9000 && f.samples()[4095] == 13095);
        }
        for (long next : new long[]{99, 601}) {
            PcmFrameBuffer f = new PcmFrameBuffer(8, 500);
            check(!read(f, 4, 100, 1));
            check(!read(f, 4, next, 21));
            check(f.offset() == 4 && f.discontinuity());
            for (int i = 0; i < 4; i++) check(f.samples()[i] == 21 + i);
            check(read(f, 4, next + 1, 25));
            for (int i = 0; i < 8; i++) check(f.samples()[i] == 21 + i);
            f.consume();
            check(!f.discontinuity());
        }
        PcmFrameBuffer slow = new PcmFrameBuffer(8, 500);
        check(!read(slow, 2, 0, 0));
        check(!read(slow, 2, 300, 2));
        check(!read(slow, 2, 501, 4)); // Total frame span, not only last-read gap.
        check(slow.offset() == 2 && slow.discontinuity() && slow.samples()[0] == 4);
        invalid(() -> slow.commitRead(0, 510));
        invalid(() -> slow.commitRead(-1, 510));
        invalid(() -> slow.commitRead(9, 510));
        invalid(() -> slow.commitRead(1, -1));
        invalid(slow::consume);
        invalid(() -> new PcmFrameBuffer(0, 500));
        invalid(() -> new PcmFrameBuffer(8, 0));
        System.out.println("PASS: " + checks + " PCM framing assertions (including sample-by-sample ordering)");
    }
}
