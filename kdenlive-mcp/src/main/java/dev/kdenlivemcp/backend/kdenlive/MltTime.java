package dev.kdenlivemcp.backend.kdenlive;

import dev.kdenlivemcp.model.Fps;

import java.util.Locale;

/** MLT/Kdenlive time strings. Kdenlive writes "HH:MM:SS.mmm" (milliseconds), and "HH:MM:SS:FF" for durations. */
public final class MltTime {

    private MltTime() {
    }

    /** Frame number to "HH:MM:SS.mmm" exactly as Kdenlive writes it. */
    public static String clock(long frames, Fps fps) {
        long ms = Math.round(frames * 1000.0 * fps.den() / fps.num());
        long h = ms / 3_600_000;
        long m = (ms / 60_000) % 60;
        long s = (ms / 1000) % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", h, m, s, ms % 1000);
    }

    /** Frame count to "HH:MM:SS:FF" (Kdenlive's kdenlive:duration on clips). */
    public static String timecode(long frames, Fps fps) {
        int rate = (int) Math.round(fps.toDouble());
        long f = frames % rate;
        long totalSec = frames / rate;
        return String.format(Locale.ROOT, "%02d:%02d:%02d:%02d", totalSec / 3600, (totalSec / 60) % 60, totalSec % 60, f);
    }

    /** Parses "HH:MM:SS.mmm", "HH:MM:SS:FF", "SS.mmm" or a plain frame count. Returns frames. */
    public static long parse(String value, Fps fps) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String v = value.trim();
        if (!v.contains(":")) {
            if (v.contains(".")) {
                return fps.frames(Double.parseDouble(v));
            }
            return Long.parseLong(v);
        }
        String[] p = v.split(":");
        if (p.length == 4) {
            int rate = (int) Math.round(fps.toDouble());
            long secs = Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60 + Long.parseLong(p[2]);
            return secs * rate + Long.parseLong(p[3]);
        }
        double secs = 0;
        for (String part : p) {
            secs = secs * 60 + Double.parseDouble(part);
        }
        return fps.frames(secs);
    }
}
