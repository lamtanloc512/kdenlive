package dev.kdenlivemcp.media;

import dev.kdenlivemcp.model.Fps;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * What ffprobe told us about a media file.
 *
 * @param durationSeconds container duration; 0 for still images
 * @param fps nominal video frame rate (r_frame_rate), null without video
 * @param rotation display rotation in degrees (0, 90, 180, 270)
 * @param variableFrameRate true when r_frame_rate and avg_frame_rate disagree noticeably
 */
public record MediaInfo(
        Path path,
        double durationSeconds,
        boolean hasVideo,
        boolean hasAudio,
        boolean stillImage,
        int width,
        int height,
        Fps fps,
        int rotation,
        boolean variableFrameRate,
        String videoCodec,
        String audioCodec,
        int audioSampleRate,
        int audioChannels,
        String formatName,
        long fileSize) {

    public MediaKind kind() {
        if (stillImage) {
            return MediaKind.IMAGE;
        }
        if (hasVideo && hasAudio) {
            return MediaKind.AV;
        }
        return hasVideo ? MediaKind.VIDEO : MediaKind.AUDIO;
    }

    /** Width/height as displayed (rotation applied). */
    public int displayWidth() {
        return rotation % 180 == 0 ? width : height;
    }

    public int displayHeight() {
        return rotation % 180 == 0 ? height : width;
    }

    /** Warnings worth showing the model on import. */
    public List<String> warnings() {
        List<String> w = new java.util.ArrayList<>();
        if (variableFrameRate) {
            w.add("variable frame rate: cuts may drift by a frame; consider transcoding to constant frame rate");
        }
        if (rotation != 0) {
            w.add("rotation metadata " + rotation + "°: shown rotated as " + displayWidth() + "x" + displayHeight());
        }
        return w;
    }

    /** One line, e.g. {@code 6.000s 1920x1080@30 video:h264 audio:aac 48kHz stereo}. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(stillImage ? "image" : String.format(Locale.ROOT, "%.3fs", durationSeconds));
        if (hasVideo) {
            sb.append(' ').append(displayWidth()).append('x').append(displayHeight());
            if (fps != null && !stillImage) {
                sb.append('@').append(fps.label());
            }
            sb.append(" video:").append(videoCodec);
        }
        if (hasAudio) {
            sb.append(" audio:").append(audioCodec);
            if (audioSampleRate > 0) {
                sb.append(' ').append(audioSampleRate / 1000).append("kHz");
            }
            if (audioChannels > 0) {
                sb.append(' ').append(audioChannels == 1 ? "mono" : audioChannels == 2 ? "stereo" : audioChannels + "ch");
            }
        } else {
            sb.append(" audio:none");
        }
        return sb.toString();
    }
}
