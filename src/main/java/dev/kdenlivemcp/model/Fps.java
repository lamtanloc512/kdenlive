package dev.kdenlivemcp.model;

import java.util.Locale;

/** A rational frame rate, e.g. 30/1 or 30000/1001. All timeline math is done in whole frames. */
public record Fps(int num, int den) {

    public Fps {
        if (num <= 0 || den <= 0) {
            throw new IllegalArgumentException("invalid frame rate " + num + "/" + den);
        }
        int g = gcd(num, den);
        num /= g;
        den /= g;
    }

    public static Fps of(int fps) {
        return new Fps(fps, 1);
    }

    /** Parses "30", "29.97", "30000/1001"; returns null for "0/0" or garbage. */
    public static Fps parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            if (s.contains("/")) {
                String[] p = s.split("/");
                int n = Integer.parseInt(p[0].trim());
                int d = Integer.parseInt(p[1].trim());
                return n > 0 && d > 0 ? new Fps(n, d) : null;
            }
            double v = Double.parseDouble(s);
            if (v <= 0) {
                return null;
            }
            for (int common : new int[] {24, 30, 60, 120}) {
                if (Math.abs(v - common * 1000.0 / 1001) < 0.005) {
                    return new Fps(common * 1000, 1001);
                }
            }
            if (Math.abs(v - Math.rint(v)) < 1e-6) {
                return new Fps((int) Math.rint(v), 1);
            }
            return new Fps((int) Math.round(v * 1000), 1000);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public double toDouble() {
        return (double) num / den;
    }

    /** Nearest frame for a time in seconds. */
    public long frames(double seconds) {
        return Math.round(seconds * num / den);
    }

    /** Whole frames that fit completely in a duration (used for media length). */
    public long framesFloor(double seconds) {
        return (long) Math.floor(seconds * num / den + 1e-6);
    }

    public double seconds(long frames) {
        return frames * (double) den / num;
    }

    /** "6.000" — seconds with millisecond precision, dot decimal separator. */
    public String sec(long frames) {
        return String.format(Locale.ROOT, "%.3f", seconds(frames));
    }

    /** "30", "29.97", "23.976". */
    public String label() {
        if (den == 1) {
            return Integer.toString(num);
        }
        String s = String.format(Locale.ROOT, "%.3f", toDouble());
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public String toString() {
        return num + "/" + den;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
