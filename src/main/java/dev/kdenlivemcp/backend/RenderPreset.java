package dev.kdenlivemcp.backend;

import dev.kdenlivemcp.model.EditException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Render presets: output size and encoder settings passed to melt's avformat consumer.
 * Height 0 = keep the project's size.
 */
public enum RenderPreset {
    DRAFT_540P("draft_540p", 960, 540, List.of("vcodec=libx264", "preset=veryfast", "crf=28", "acodec=aac", "ab=128k")),
    FINAL_1080P("final_1080p", 1920, 1080, List.of("vcodec=libx264", "preset=medium", "crf=18", "acodec=aac", "ab=192k")),
    VERTICAL_1080P("vertical_1080p", 1080, 1920, List.of("vcodec=libx264", "preset=medium", "crf=18", "acodec=aac", "ab=192k"));

    public final String key;
    public final int width;
    public final int height;
    public final List<String> consumerArgs;

    RenderPreset(String key, int width, int height, List<String> consumerArgs) {
        this.key = key;
        this.width = width;
        this.height = height;
        this.consumerArgs = consumerArgs;
    }

    public static RenderPreset byKey(String key) {
        return Arrays.stream(values()).filter(p -> p.key.equalsIgnoreCase(key == null ? "" : key.trim())).findFirst()
                .orElseThrow(() -> new EditException("unknown render preset '" + key + "'; use one of: "
                        + Arrays.stream(values()).map(p -> p.key).collect(Collectors.joining(", "))));
    }
}
