package com.example.bluetoothmotor;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public final class DialGeometryTest {
    private static int checks;
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Dial check " + (checks + 1));
        checks++;
    }
    private static void close(double a, double b) { check(Math.abs(a - b) < 0.001); }
    private static void pivot(BufferedImage b, float px, float py, int left, int top, int right, int bottom) {
        double x = 0, y = 0; int count = 0;
        for (int row = top; row <= bottom; row++) for (int col = left; col <= right; col++) {
            int p = b.getRGB(col, row);
            if ((p >>> 24) >= 240 && ((p >>> 16) & 255) < 20 && ((p >>> 8) & 255) < 20 && (p & 255) < 20) {
                x += col; y += row; count++;
            }
        }
        check(count > 100);
        check(Math.abs(x / count - px) < 0.2);
        check(Math.abs(y / count - py) < 0.2);
    }
    public static void main(String[] args) throws Exception {
        close(DialGeometry.angleDegrees(0), 0);
        close(DialGeometry.angleDegrees(100), 20);
        close(DialGeometry.angleDegrees(-100), -20);
        close(DialGeometry.angleDegrees(1), 0.2);
        close(DialGeometry.angleDegrees(20), 4);
        close(DialGeometry.angleDegrees(50), 10);
        close(DialGeometry.angleDegrees(200), 40);
        close(DialGeometry.angleDegrees(-200), -40);
        close(DialGeometry.angleDegrees(1200), 40);
        close(DialGeometry.angleDegrees(-1200), -40);
        close(DialGeometry.angleDegrees(Double.NaN), 0);
        close(DialGeometry.angleDegrees(Double.POSITIVE_INFINITY), 0);
        check(!DialGeometry.finite(Double.NaN) && !DialGeometry.finite(Double.NEGATIVE_INFINITY));
        for (float radius : new float[]{80, 150, 220, 400}) {
            float scale = DialGeometry.needleScale(radius);
            float left = DialGeometry.origin(200, DialGeometry.NEEDLE_PIVOT_X, scale);
            float top = DialGeometry.origin(300, DialGeometry.NEEDLE_PIVOT_Y, scale);
            close(left + DialGeometry.NEEDLE_PIVOT_X * scale, 200);
            close(top + DialGeometry.NEEDLE_PIVOT_Y * scale, 300);
            close(300 - top, radius * 0.86);
            for (double cents : new double[]{-200, -100, 0, 100, 200}) {
                double radians = Math.toRadians(DialGeometry.angleDegrees(cents));
                double tipX = Math.sin(radians) * (300 - top);
                check(cents == 0 ? tipX == 0 : Math.signum(tipX) == Math.signum(cents));
            }
        }
        close(DialGeometry.needleScale(-1), 0);
        Path assets = Path.of(args[0], "app", "src", "main", "res", "drawable-nodpi");
        BufferedImage needle = ImageIO.read(assets.resolve("zhen.png").toFile());
        BufferedImage dial = ImageIO.read(assets.resolve("rotating_dial.png").toFile());
        check(needle.getWidth() == 80 && needle.getHeight() == 421);
        check(dial.getWidth() == 1024 && dial.getHeight() == 1024);
        check(needle.getColorModel().hasAlpha() && dial.getColorModel().hasAlpha());
        check((needle.getRGB(0, 0) >>> 24) == 0 && (dial.getRGB(0, 0) >>> 24) == 0);
        pivot(needle, DialGeometry.NEEDLE_PIVOT_X, DialGeometry.NEEDLE_PIVOT_Y, 24, 387, 56, 420);
        pivot(dial, DialGeometry.DIAL_PIVOT_X, DialGeometry.DIAL_PIVOT_Y, 480, 480, 550, 550);
        System.out.println("PASS: " + checks + " needle scale/pivot/original-asset checks");
    }
}
