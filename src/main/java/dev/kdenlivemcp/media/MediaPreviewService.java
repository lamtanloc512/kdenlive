package dev.kdenlivemcp.media;

import dev.kdenlivemcp.config.KdenliveConfig;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.tools.Binary;
import dev.kdenlivemcp.tools.ExternalTools;
import dev.kdenlivemcp.tools.ProcessRunner;
import dev.kdenlivemcp.tools.ResolvedBinary;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Frames straight from a source file (not the timeline), so the AI can scout a clip before cutting it.
 * Uses ffmpeg: accurate seeking, rotation applied, times stamped with drawtext when ffmpeg has it.
 */
@ApplicationScoped
public class MediaPreviewService {

    private final ExternalTools tools;
    private final KdenliveConfig config;
    private volatile Boolean drawtext;

    public MediaPreviewService(ExternalTools tools, KdenliveConfig config) {
        this.tools = tools;
        this.config = config;
    }

    /** Frames at the given source times (seconds), tiled 4 per row, each at most tileWidth wide. */
    public byte[] contactSheet(Path file, List<Double> times, int tileWidth) {
        ResolvedBinary ffmpeg = tools.require(Binary.FFMPEG);
        Path dir;
        try {
            dir = Files.createDirectories(Path.of(expand(config.workDir())).toAbsolutePath()
                    .resolve("media-sheet-" + UUID.randomUUID()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            List<byte[]> images = new ArrayList<>();
            for (double t : times) {
                images.add(grab(ffmpeg, file, t, tileWidth, dir));
            }
            return ImageTiler.tile(ffmpeg, dir, images);
        } finally {
            ImageTiler.deleteTree(dir);
        }
    }

    private byte[] grab(ResolvedBinary ffmpeg, Path file, double t, int width, Path dir) {
        Path png = dir.resolve(String.format(Locale.ROOT, "grab-%.3f.png", t));
        String vf = "scale=" + width + ":-2";
        if (hasDrawtext(ffmpeg)) {
            String label = String.format(Locale.ROOT, "%.3fs", t);
            vf += ",drawtext=text='" + label + "':x=w-tw-8:y=h-th-8:fontsize=" + Math.max(14, width / 22)
                    + ":fontcolor=white:box=1:boxcolor=black@0.65:boxborderw=5";
        }
        List<String> cmd = List.of(ffmpeg.path().toString(), "-v", "error", "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", t), "-i", file.toString(),
                "-frames:v", "1", "-vf", vf, "-update", "1", png.toString());
        ProcessRunner.Result r = ProcessRunner.run(cmd, Duration.ofSeconds(config.frameTimeout()), ffmpeg.env(), dir);
        if (!r.ok() || !Files.isRegularFile(png)) {
            throw new EditException("ffmpeg could not read a frame at " + String.format(Locale.ROOT, "%.3f", t)
                    + "s of " + file.getFileName() + ": " + r.stderrTail(3).strip());
        }
        try {
            return Files.readAllBytes(png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True when frames get their time stamped on them (ffmpeg built with libfreetype). */
    public boolean labelsFrames() {
        return hasDrawtext(tools.require(Binary.FFMPEG));
    }

    private boolean hasDrawtext(ResolvedBinary ffmpeg) {
        Boolean d = drawtext;
        if (d == null) {
            ProcessRunner.Result r = ProcessRunner.run(List.of(ffmpeg.path().toString(), "-hide_banner", "-filters"),
                    Duration.ofSeconds(15), ffmpeg.env(), null);
            d = r.stdout().contains(" drawtext ");
            drawtext = d;
        }
        return d;
    }

    private static String expand(String p) {
        return p.startsWith("~/") ? System.getProperty("user.home") + p.substring(1) : p;
    }
}
