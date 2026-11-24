package dev.kdenlivemcp.mcp;

import dev.kdenlivemcp.edit.EditResult;
import dev.kdenlivemcp.edit.ProjectOps;
import dev.kdenlivemcp.media.FfprobeService;
import dev.kdenlivemcp.media.MediaException;
import dev.kdenlivemcp.media.MediaInfo;
import dev.kdenlivemcp.media.MediaPreviewService;
import io.quarkiverse.mcp.server.ImageContent;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolResponse;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.MediaClip;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.session.ProjectSession;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** media_import / media_list. */
public class MediaTools {

    private final ProjectSession session;
    private final FfprobeService ffprobe;
    private final MediaPreviewService previews;

    public MediaTools(ProjectSession session, FfprobeService ffprobe, MediaPreviewService previews) {
        this.session = session;
        this.ffprobe = ffprobe;
        this.previews = previews;
    }

    @Tool(name = "media_contact_sheet", description = """
            Look inside a bin clip BEFORE editing: 'count' frames from the source file, evenly spaced from start             to end (seconds into the file, default the whole file), 4 per row, each stamped with its source time.             Use those times as in/out for clip_insert. Example: media_contact_sheet(clipId="m1", count=12) or             media_contact_sheet(clipId="m1", start=30, end=45, count=8)""",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public ToolResponse mediaContactSheet(
            @ToolArg(description = "Bin clip id, e.g. m1") String clipId,
            @ToolArg(description = "Start, seconds into the file (default 0)", required = false) Double start,
            @ToolArg(description = "End, seconds into the file (default: end of file)", required = false) Double end,
            @ToolArg(description = "Number of frames, 2-24", defaultValue = "12") int count) {
        return ToolSupport.call("media_contact_sheet", () -> {
            Project p = session.current();
            MediaClip m = p.media(clipId).orElseThrow(() -> new EditException("unknown clip id '" + clipId
                    + "'; bin: " + p.bin().stream().map(MediaClip::label).toList()));
            if (!m.kind().hasVideo()) {
                throw new EditException(m.label() + " is audio only: nothing to look at");
            }
            if (count < 2 || count > 24) {
                throw new EditException("count must be between 2 and 24");
            }
            if (m.info().isColor()) {
                throw new EditException(m.label() + " is a color clip (" + m.color() + "): nothing to preview");
            }
            double dur = m.info().stillImage() ? 0 : m.info().durationSeconds();
            double last = Math.max(0, dur - 0.05);
            double s = start == null ? 0 : start;
            double e = end == null ? last : Math.min(end, last);
            if (s < 0 || s >= e) {
                throw new EditException(String.format(java.util.Locale.ROOT,
                        "need 0 <= start < end <= %.3f (%s is %.3fs long)", last, m.label(), dur));
            }
            List<Double> times = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                times.add(Math.round((s + (e - s) * i / (count - 1)) * 1000) / 1000.0);
            }
            byte[] png = previews.contactSheet(m.path(), times, PreviewTools.TILE_WIDTH);
            String note = previews.labelsFrames() ? "" : " (left to right, top to bottom; this ffmpeg cannot draw the times on the frames)";
            return ToolResponse.success(new TextContent(m.label() + " source times: " + times.stream()
                            .map(t -> String.format(java.util.Locale.ROOT, "%.3f", t)).toList() + note),
                    new ImageContent(java.util.Base64.getEncoder().encodeToString(png), "image/png"));
        });
    }

    @Tool(name = "media_import", description = """
            Add video, audio or image files to the project bin. Returns a clip id per file (m1, m2, ...) with \
            duration, size, fps and audio info; use the clip id with clip_insert. Importing a file twice returns \
            its existing id. Example: media_import(paths=["/Users/me/clips/intro.mp4", "/Users/me/music/bed.mp3"])""")
    public String mediaImport(@ToolArg(description = "Absolute file paths") List<String> paths) {
        return ToolSupport.call("media_import", () -> {
            if (paths == null || paths.isEmpty()) {
                throw new EditException("paths must list at least one file");
            }
            List<MediaInfo> infos = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            for (String raw : paths) {
                try {
                    Path file = ProjectTools.absolute(raw, "path");
                    infos.add(ffprobe.probe(file));
                } catch (MediaException | EditException e) {
                    problems.add(raw + ": " + e.getMessage());
                }
            }
            if (!problems.isEmpty()) {
                throw new EditException("nothing imported; fix these first:\n" + String.join("\n", problems));
            }
            EditResult r = session.apply("media_import", p -> ProjectOps.importMedia(p, infos));
            return r.message();
        });
    }

    @Tool(name = "media_list", description = "List the clips in the project bin with ids and probe info. Example: media_list()",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public String mediaList() {
        return ToolSupport.call("media_list", () -> {
            Project p = session.current();
            if (p.bin().isEmpty()) {
                return "bin is empty; use media_import";
            }
            StringBuilder sb = new StringBuilder();
            for (MediaClip m : p.bin()) {
                sb.append(m.id()).append(": ").append(m.name()).append(' ').append(m.info().describe())
                        .append(" (").append(m.path()).append(")\n");
            }
            return sb.toString().strip();
        });
    }
}
