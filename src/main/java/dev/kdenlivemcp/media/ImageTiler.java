package dev.kdenlivemcp.media;

import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.tools.ProcessRunner;
import dev.kdenlivemcp.tools.ResolvedBinary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Tiles equally sized PNGs into one image with ffmpeg (no AWT, so it works in native images on macOS). */
public final class ImageTiler {

    private ImageTiler() {
    }

    /** {@code images} are written to {@code dir} and tiled 4 per row. */
    public static byte[] tile(ResolvedBinary ffmpeg, Path dir, List<byte[]> images) {
        try {
            for (int i = 0; i < images.size(); i++) {
                Files.write(dir.resolve(String.format(Locale.ROOT, "f%03d.png", i)), images.get(i));
            }
            int cols = Math.min(4, images.size());
            int rows = (images.size() + cols - 1) / cols;
            Path sheet = dir.resolve("sheet.png");
            ProcessRunner.Result r = ProcessRunner.run(List.of(ffmpeg.path().toString(), "-v", "error", "-y",
                    "-framerate", "1", "-i", dir.resolve("f%03d.png").toString(),
                    "-vf", "tile=" + cols + "x" + rows + ":padding=6:margin=6:color=0x202020",
                    "-frames:v", "1", "-update", "1", sheet.toString()), Duration.ofSeconds(60), ffmpeg.env(), dir);
            if (!r.ok() || !Files.isRegularFile(sheet)) {
                throw new EditException("ffmpeg could not build the contact sheet: " + r.stderrTail(5).strip());
            }
            return Files.readAllBytes(sheet);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // already gone
        }
    }
}
