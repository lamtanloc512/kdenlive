package dev.kdenlivemcp.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kdenlivemcp.config.KdenliveConfig;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.tools.Binary;
import dev.kdenlivemcp.tools.ExternalTools;
import dev.kdenlivemcp.tools.ProcessRunner;
import dev.kdenlivemcp.tools.ResolvedBinary;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** ffprobe wrapper: duration, fps, resolution, streams, rotation. */
@ApplicationScoped
public class FfprobeService {

    private static final Set<String> IMAGE_CODECS = Set.of("png", "mjpeg", "jpeg", "bmp", "gif", "webp", "tiff", "jpegxl");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExternalTools tools;
    private final KdenliveConfig config;

    public FfprobeService(ExternalTools tools, KdenliveConfig config) {
        this.tools = tools;
        this.config = config;
    }

    public MediaInfo probe(Path file) {
        Path path = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new MediaException("file not found: " + path);
        }
        ResolvedBinary ffprobe = tools.require(Binary.FFPROBE);
        ProcessRunner.Result r = ProcessRunner.run(List.of(ffprobe.path().toString(),
                        "-v", "error", "-print_format", "json", "-show_format", "-show_streams", path.toString()),
                Duration.ofSeconds(config.probeTimeout()), ffprobe.env(), null);
        if (r.timedOut()) {
            throw new MediaException("ffprobe timed out after " + config.probeTimeout() + "s on " + path);
        }
        if (r.exitCode() != 0) {
            throw new MediaException("ffprobe cannot read " + path.getFileName() + ": " + r.stderrTail(3).strip());
        }
        try {
            long size = Files.size(path);
            return parse(path, JSON.readTree(r.stdout()), size);
        } catch (IOException e) {
            throw new MediaException("unexpected ffprobe output for " + path + ": " + e.getMessage());
        }
    }

    /** Visible for tests: turns ffprobe JSON into MediaInfo. */
    static MediaInfo parse(Path path, JsonNode root, long fileSize) {
        JsonNode video = null;
        JsonNode audio = null;
        for (JsonNode s : root.path("streams")) {
            String type = s.path("codec_type").asText();
            boolean attachedPic = s.path("disposition").path("attached_pic").asInt(0) == 1;
            if ("video".equals(type) && video == null && !attachedPic) {
                video = s;
            } else if ("audio".equals(type) && audio == null) {
                audio = s;
            }
        }
        if (video == null && audio == null) {
            throw new MediaException(path.getFileName() + " has no audio or video stream");
        }
        JsonNode format = root.path("format");
        String formatName = format.path("format_name").asText("");
        double duration = parseDouble(format.path("duration").asText(null));
        if (duration <= 0 && video != null) {
            duration = parseDouble(video.path("duration").asText(null));
        }
        if (duration <= 0 && audio != null) {
            duration = parseDouble(audio.path("duration").asText(null));
        }

        boolean still = false;
        int width = 0;
        int height = 0;
        Fps fps = null;
        int rotation = 0;
        boolean vfr = false;
        String vcodec = null;
        if (video != null) {
            vcodec = video.path("codec_name").asText("unknown");
            width = video.path("width").asInt(0);
            height = video.path("height").asInt(0);
            fps = Fps.parse(video.path("r_frame_rate").asText("0/0"));
            Fps avg = Fps.parse(video.path("avg_frame_rate").asText("0/0"));
            if (fps != null && avg != null && Math.abs(fps.toDouble() - avg.toDouble()) > 0.01 * fps.toDouble()) {
                vfr = true;
            }
            if (fps == null) {
                fps = avg;
            }
            String nbFrames = video.path("nb_frames").asText("");
            still = IMAGE_CODECS.contains(vcodec) && audio == null
                    && (duration <= 0 || "1".equals(nbFrames) || formatName.contains("_pipe") || formatName.equals("image2"));
            rotation = rotationOf(video);
        }
        if (still) {
            duration = 0;
            vfr = false;
        }
        String acodec = audio == null ? null : audio.path("codec_name").asText("unknown");
        int sampleRate = audio == null ? 0 : audio.path("sample_rate").asInt(0);
        int channels = audio == null ? 0 : audio.path("channels").asInt(0);
        if (!still && duration <= 0) {
            throw new MediaException(path.getFileName() + ": cannot determine duration");
        }
        return new MediaInfo(path, duration, video != null, audio != null, still, width, height, fps, rotation, vfr,
                vcodec, acodec, sampleRate, channels, formatName, fileSize);
    }

    private static int rotationOf(JsonNode video) {
        double rot = 0;
        String tag = video.path("tags").path("rotate").asText(null);
        if (tag != null) {
            rot = parseDouble(tag);
        }
        for (JsonNode sd : video.path("side_data_list")) {
            if (sd.has("rotation")) {
                rot = sd.path("rotation").asDouble();
            }
        }
        // ffprobe reports display-matrix rotation counter-clockwise as negative values
        int r = (int) Math.round(rot) % 360;
        if (r < 0) {
            r += 360;
        }
        return (r / 90) * 90 % 360;
    }

    private static double parseDouble(String s) {
        if (s == null || s.isBlank() || "N/A".equals(s)) {
            return 0;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
