package dev.kdenlivemcp.media;

import dev.kdenlivemcp.tools.ProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Generates small media files with ffmpeg for integration tests. */
public final class TestMedia {

    private TestMedia() {
    }

    public static boolean available(String exe) {
        try {
            return ProcessRunner.run(List.of(exe, "-version"), Duration.ofSeconds(10)).ok();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Color bars + 440 Hz tone, 1920x1080@30. */
    public static Path bars(Path dir, double seconds) {
        return gen(dir.resolve("bars.mp4"), List.of("-f", "lavfi", "-i", "smptebars=size=1920x1080:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", str(seconds),
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest"));
    }

    /** testsrc2 + 660 Hz tone, 1920x1080@30. */
    public static Path testsrc(Path dir, double seconds) {
        return gen(dir.resolve("testsrc.mp4"), List.of("-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000", "-t", str(seconds),
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest"));
    }

    /** 220 Hz sine, audio only. */
    public static Path music(Path dir, double seconds) {
        return gen(dir.resolve("music.m4a"), List.of("-f", "lavfi", "-i", "sine=frequency=220:sample_rate=48000",
                "-t", str(seconds), "-c:a", "aac"));
    }

    private static Path gen(Path out, List<String> args) {
        if (Files.exists(out)) {
            return out;
        }
        List<String> cmd = new java.util.ArrayList<>(List.of("ffmpeg", "-y", "-v", "error"));
        cmd.addAll(args);
        cmd.add(out.toString());
        ProcessRunner.Result r = ProcessRunner.run(cmd, Duration.ofSeconds(120));
        if (!r.ok()) {
            throw new IllegalStateException("ffmpeg failed: " + r.stderrTail(10));
        }
        return out;
    }

    private static String str(double s) {
        return String.format(java.util.Locale.ROOT, "%.3f", s);
    }
}
