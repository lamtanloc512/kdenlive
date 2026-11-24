package dev.kdenlivemcp.mcp;

import dev.kdenlivemcp.backend.kdenlive.KdenliveXmlBackend;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.session.ProjectSession;
import io.quarkiverse.mcp.server.ImageContent;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;

import java.util.Base64;
import java.util.Locale;

/** Look at the edit: single frames and contact sheets, rendered by melt from the current state. */
public class PreviewTools {

    static final int PREVIEW_WIDTH = 960;
    static final int TILE_WIDTH = 400;

    private final ProjectSession session;
    private final KdenliveXmlBackend backend;

    public PreviewTools(ProjectSession session, KdenliveXmlBackend backend) {
        this.session = session;
        this.backend = backend;
    }

    @Tool(name = "preview_frame", description = """
            Render the timeline frame at time t (seconds) as a PNG image (max 960 px wide), with titles, \
            transitions and compositing as they will appear in the final render. Example: preview_frame(t=4.2)""",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public ToolResponse previewFrame(@ToolArg(description = "Time in seconds") double t) {
        return ToolSupport.call("preview_frame", () -> {
            Project p = session.current();
            long frame = ToolSupport.frames(p.fps(), "t", t);
            byte[] png = backend.grabFrame(p, frame, PREVIEW_WIDTH, null);
            return ToolResponse.success(new TextContent("frame at " + p.fps().sec(frame) + "s (frame " + frame + ")"),
                    new ImageContent(Base64.getEncoder().encodeToString(png), "image/png"));
        });
    }

    @Tool(name = "contact_sheet", description = """
            One image with 'count' frames evenly spaced from start to end (seconds, inclusive), 4 per row, each \
            stamped with its time. Use it to check the whole edit at a glance. Defaults: the whole timeline, 8 frames.
            Example: contact_sheet(start=0, end=20, count=8)""",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public ToolResponse contactSheet(
            @ToolArg(description = "Start time in seconds", required = false) Double start,
            @ToolArg(description = "End time in seconds (default: end of timeline)", required = false) Double end,
            @ToolArg(description = "Number of frames, 2-16", defaultValue = "8") int count) {
        return ToolSupport.call("contact_sheet", () -> {
            Project p = session.current();
            Fps fps = p.fps();
            if (p.duration() == 0) {
                throw new EditException("the timeline is empty");
            }
            if (count < 2 || count > 16) {
                throw new EditException("count must be between 2 and 16");
            }
            long last = p.duration() - 1;
            long s = start == null ? 0 : ToolSupport.frames(fps, "start", start);
            long e = end == null ? last : Math.min(last, ToolSupport.frames(fps, "end", end));
            if (s >= e) {
                throw new EditException("start must be before end (timeline is " + fps.sec(p.duration()) + "s)");
            }
            byte[] png = backend.contactSheet(p, s, e, count, TILE_WIDTH);
            StringBuilder caption = new StringBuilder(String.format(Locale.ROOT, "%d frames from %ss to %ss:", count,
                    fps.sec(s), fps.sec(e)));
            for (int i = 0; i < count; i++) {
                caption.append(' ').append(fps.sec(s + Math.round((double) (e - s) * i / (count - 1))));
            }
            return ToolResponse.success(new TextContent(caption.toString()),
                    new ImageContent(Base64.getEncoder().encodeToString(png), "image/png"));
        });
    }
}
