package dev.kdenlivemcp.mcp;

import dev.kdenlivemcp.backend.kdenlive.AssFile;
import dev.kdenlivemcp.edit.MarkerOps;
import dev.kdenlivemcp.edit.ProjectOps;
import dev.kdenlivemcp.edit.SpeedOps;
import dev.kdenlivemcp.edit.SubtitleOps;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.Subtitles;
import dev.kdenlivemcp.session.ProjectSession;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Guides and markers, subtitles, clip speed and color clips. */
public class ExtrasTools {

    private final ProjectSession session;

    public ExtrasTools(ProjectSession session) {
        this.session = session;
    }

    private Fps fps() {
        return session.current().fps();
    }

    // ------------------------------------------------------------------ markers

    @Tool(name = "marker_add", description = """
            Add a guide on the timeline (Kdenlive's Guides: chapter marks, beat marks, notes) or, with clipId, a \
            marker on a bin clip at a source time. category 0..8 = purple, blue, cyan, green, yellow, orange, dark \
            orange, red-orange, red. duration > 0 makes a range marker. A marker at the same time is replaced.
            Example: marker_add(at=12.5, comment="Chorus", category=3)""")
    public String markerAdd(
            @ToolArg(description = "Time in seconds (timeline, or source time with clipId)") double at,
            @ToolArg(description = "Text", required = false) String comment,
            @ToolArg(description = "Category 0..8", defaultValue = "0") int category,
            @ToolArg(description = "Range length in seconds (0 = point)", defaultValue = "0") double duration,
            @ToolArg(description = "Bin clip id for a clip marker; omit for a timeline guide", required = false)
            String clipId) {
        return ToolSupport.call("marker_add", () -> {
            long pos = ToolSupport.frames(fps(), "at", at);
            long len = duration <= 0 ? 0 : ToolSupport.frames(fps(), "duration", duration);
            return session.apply("marker_add", p -> MarkerOps.add(p, blank(clipId), pos, comment, category, len)).message();
        });
    }

    @Tool(name = "marker_list", description = "Guides of the timeline, or markers of a bin clip. Example: marker_list()",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public String markerList(@ToolArg(description = "Bin clip id; omit for guides", required = false) String clipId) {
        return ToolSupport.call("marker_list", () -> {
            Project p = session.current();
            List<MarkerOps.Marker> list = MarkerOps.list(p, blank(clipId));
            if (list.isEmpty()) {
                return clipId == null || clipId.isBlank() ? "no guides" : "no markers on " + clipId;
            }
            StringBuilder sb = new StringBuilder();
            for (MarkerOps.Marker m : list) {
                sb.append(p.fps().sec(m.pos())).append("s");
                if (m.duration() > 0) {
                    sb.append("–").append(p.fps().sec(m.pos() + m.duration())).append("s");
                }
                sb.append(" [").append(MarkerOps.CATEGORY_COLORS.get(Math.max(0, Math.min(8, m.category())))).append("] ")
                        .append(m.comment()).append('\n');
            }
            return sb.toString().stripTrailing();
        });
    }

    @Tool(name = "marker_remove", description = """
            Remove the guide (or clip marker) at a time, or all of them when at is omitted.
            Example: marker_remove(at=12.5)""")
    public String markerRemove(
            @ToolArg(description = "Time in seconds; omit to remove all", required = false) Double at,
            @ToolArg(description = "Bin clip id; omit for guides", required = false) String clipId) {
        return ToolSupport.call("marker_remove", () -> {
            Long pos = ToolSupport.framesOrNull(fps(), "at", at);
            return session.apply("marker_remove", p -> MarkerOps.remove(p, blank(clipId), pos)).message();
        });
    }

    // ------------------------------------------------------------------ subtitles

    @Tool(name = "subtitle_add", description = """
            Add a line to the subtitle track (Kdenlive's subtitle track, saved as <project>.kdenlive.ass next to the \
            project and burned into renders). Use \\n for a line break. Lines are numbered in time order.
            Example: subtitle_add(start=1.2, end=3.5, text="Xin chào!")""")
    public String subtitleAdd(
            @ToolArg(description = "Start, seconds") double start,
            @ToolArg(description = "End, seconds") double end,
            @ToolArg(description = "Text") String text) {
        return ToolSupport.call("subtitle_add", () -> session.apply("subtitle_add",
                p -> SubtitleOps.add(p, ToolSupport.frames(fps(), "start", start), ToolSupport.frames(fps(), "end", end),
                        text)).message());
    }

    @Tool(name = "subtitle_list", description = "All subtitle lines with their numbers and times. Example: subtitle_list()",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public String subtitleList() {
        return ToolSupport.call("subtitle_list", () -> {
            Project p = session.current();
            List<Subtitles.Line> lines = p.subtitles().lines();
            if (lines.isEmpty()) {
                return "no subtitles";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                Subtitles.Line l = lines.get(i);
                sb.append(i + 1).append(". ").append(p.fps().sec(l.start())).append("–").append(p.fps().sec(l.end()))
                        .append(' ').append(l.text().replace("\n", "\\n")).append('\n');
            }
            return sb.toString().stripTrailing();
        });
    }

    @Tool(name = "subtitle_set", description = """
            Change a subtitle line's times and/or text (number from subtitle_list).
            Example: subtitle_set(number=2, end=6.0, text="New text")""")
    public String subtitleSet(
            @ToolArg(description = "Line number") int number,
            @ToolArg(description = "New start, seconds", required = false) Double start,
            @ToolArg(description = "New end, seconds", required = false) Double end,
            @ToolArg(description = "New text", required = false) String text) {
        return ToolSupport.call("subtitle_set", () -> session.apply("subtitle_set", p -> SubtitleOps.set(p, number,
                ToolSupport.framesOrNull(fps(), "start", start), ToolSupport.framesOrNull(fps(), "end", end), text))
                .message());
    }

    @Tool(name = "subtitle_remove", description = "Remove a subtitle line, or all lines when number is omitted. "
            + "Example: subtitle_remove(number=3)")
    public String subtitleRemove(@ToolArg(description = "Line number; omit for all", required = false) Integer number) {
        return ToolSupport.call("subtitle_remove",
                () -> session.apply("subtitle_remove", p -> SubtitleOps.remove(p, number)).message());
    }

    @Tool(name = "subtitle_import", description = """
            Import subtitles from an .srt, .vtt or .ass file (e.g. a transcript from a speech-to-text tool), \
            shifted by offset seconds. replace=true replaces the current lines (and, for .ass, the styles).
            Example: subtitle_import(path="/Users/me/talk.srt", offset=2.0)""")
    public String subtitleImport(
            @ToolArg(description = "Absolute path of the subtitle file") String path,
            @ToolArg(description = "Shift in seconds (may be negative)", defaultValue = "0") double offset,
            @ToolArg(description = "Replace existing lines", defaultValue = "false") boolean replace) {
        return ToolSupport.call("subtitle_import", () -> {
            Path file = Path.of(path.startsWith("~/") ? System.getProperty("user.home") + path.substring(1) : path);
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException e) {
                throw new EditException("cannot read " + file + ": " + e.getMessage());
            }
            Fps f = fps();
            Subtitles parsed = file.toString().toLowerCase(Locale.ROOT).endsWith(".ass")
                    ? AssFile.parse(text, f, java.util.Map.of()) : AssFile.parseSrt(text, f);
            long off = Math.round(offset * f.toDouble());
            return session.apply("subtitle_import", p -> SubtitleOps.importLines(p, parsed, off, replace)).message();
        });
    }

    @Tool(name = "subtitle_style", description = """
            Style of all subtitles: font, size (px; default frame height / 18), color and outline (#RRGGBB[AA]), \
            outline width, bold, position (bottom, middle, top), marginV (px from the edge), box (draw an opaque box \
            of backColor behind the text). Example: subtitle_style(font="Arial", size=64, color="#ffe000", bold=true)""")
    public String subtitleStyle(
            @ToolArg(description = "Font family", defaultValue = "Arial") String font,
            @ToolArg(description = "Size in pixels (0 = automatic)", defaultValue = "0") double size,
            @ToolArg(description = "Text color", defaultValue = "#ffffff") String color,
            @ToolArg(description = "Outline color", defaultValue = "#000000") String outlineColor,
            @ToolArg(description = "Outline width px", defaultValue = "3") double outline,
            @ToolArg(description = "Bold", defaultValue = "false") boolean bold,
            @ToolArg(description = "bottom | middle | top", defaultValue = "bottom") String position,
            @ToolArg(description = "Vertical margin px (0 = automatic)", defaultValue = "0") int marginV,
            @ToolArg(description = "Box behind the text", defaultValue = "false") boolean box,
            @ToolArg(description = "Box color", defaultValue = "#00000080") String backColor) {
        return ToolSupport.call("subtitle_style", () -> {
            String pos = position == null ? "bottom" : position.trim().toLowerCase(Locale.ROOT);
            if (!List.of("bottom", "middle", "center", "top").contains(pos)) {
                throw new EditException("position must be bottom, middle or top");
            }
            Subtitles.Style st = new Subtitles.Style(font, size, color, outlineColor, outline, bold, pos, marginV,
                    backColor, box);
            AssFile.write(Subtitles.EMPTY.withStyle(st), session.current().profile(), "check"); // validates colors
            return session.apply("subtitle_style", p -> SubtitleOps.style(p, st)).message();
        });
    }

    // ------------------------------------------------------------------ speed and color clips

    @Tool(name = "clip_speed", description = """
            Slow motion / fast motion (Kdenlive's Change Speed): speed 0.5 = half speed (twice as long), 2 = double \
            speed. The clip keeps its start and the same part of the source, so its length changes; the linked \
            audio/video part follows, and effect keyframes stay on the same pictures. keepPitch keeps the voice pitch.
            Example: clip_speed(itemId="c4", speed=0.5)""")
    public String clipSpeed(
            @ToolArg(description = "Timeline clip id") String itemId,
            @ToolArg(description = "Speed factor, 0.01..100 (1 = normal)") double speed,
            @ToolArg(description = "Keep audio pitch", defaultValue = "false") boolean keepPitch) {
        return ToolSupport.call("clip_speed",
                () -> session.apply("clip_speed " + itemId, p -> SpeedOps.set(p, itemId, speed, keepPitch)).message());
    }

    @Tool(name = "media_add_color", description = """
            Add a color clip to the bin (backgrounds, title cards, solid overlays with a blend composition). Then \
            place it with clip_insert(clipId, track, at, out=<seconds>). Example: media_add_color(color="#101820")""")
    public String mediaAddColor(
            @ToolArg(description = "Color #RRGGBB or #RRGGBBAA") String color,
            @ToolArg(description = "Name in the bin", required = false) String name) {
        return ToolSupport.call("media_add_color",
                () -> session.apply("media_add_color", p -> ProjectOps.addColor(p, color, name)).message());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
