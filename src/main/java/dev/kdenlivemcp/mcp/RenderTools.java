package dev.kdenlivemcp.mcp;

import dev.kdenlivemcp.backend.RenderJob;
import dev.kdenlivemcp.backend.RenderPreset;
import dev.kdenlivemcp.backend.RenderSpec;
import dev.kdenlivemcp.backend.RenderStatus;
import dev.kdenlivemcp.backend.kdenlive.KdenliveXmlBackend;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.session.ProjectSession;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

import java.nio.file.Path;

/** Background rendering with melt. */
public class RenderTools {

    private final ProjectSession session;
    private final KdenliveXmlBackend backend;

    public RenderTools(ProjectSession session, KdenliveXmlBackend backend) {
        this.session = session;
        this.backend = backend;
    }

    @Tool(name = "render", description = """
            Start rendering the current timeline to a video file in the background and return a job id at once. \
            Poll render_status(jobId) until done. Presets: draft_540p (fast check), final_1080p (H.264 CRF 18), \
            vertical_1080p (1080x1920, vertical projects only). Later edits do not affect a running render.
            Example: render(outputPath="/Users/me/Videos/promo.mp4", preset="final_1080p")""")
    public String render(
            @ToolArg(description = "Absolute path of the .mp4/.mov/.mkv to write") String outputPath,
            @ToolArg(description = "draft_540p | final_1080p | vertical_1080p", defaultValue = "draft_540p") String preset) {
        return ToolSupport.call("render", () -> {
            Path out = ProjectTools.absolute(outputPath, "outputPath");
            RenderJob job = backend.render(session.current(), new RenderSpec(out, RenderPreset.byKey(preset)));
            String msg = "render started: jobId=" + job.id() + " -> " + out + ". Poll render_status(jobId=\""
                    + job.id() + "\").";
            var problem = backend.titleRenderingProblem();
            if (problem.isPresent()) {
                msg += "\nwarning: " + problem.get();
            }
            return msg;
        });
    }

    @Tool(name = "render_status", description = """
            Progress of a render job: running N% with ETA, done (path, duration, size, codecs) or failed (reason \
            and last log lines). Example: render_status(jobId="r1")""",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public String renderStatus(@ToolArg(description = "Job id returned by render") String jobId) {
        return ToolSupport.call("render_status", () -> {
            RenderJob job = backend.job(jobId).orElseThrow(() -> new EditException("unknown render job '" + jobId
                    + "'; jobs: " + backend.jobIds()));
            RenderStatus s = job.status();
            String out = s.state().name().toLowerCase() + ": " + s.message();
            if (s.state() == RenderStatus.State.FAILED && !s.logTail().isBlank()) {
                out += "\nlast log lines:\n" + s.logTail();
            }
            return out;
        });
    }

    @Tool(name = "render_cancel", description = "Stop a running render and delete its partial file. Example: render_cancel(jobId=\"r1\")")
    public String renderCancel(@ToolArg(description = "Job id") String jobId) {
        return ToolSupport.call("render_cancel", () -> {
            RenderJob job = backend.job(jobId).orElseThrow(() -> new EditException("unknown render job '" + jobId + "'"));
            job.cancel();
            return job.status().state().name().toLowerCase() + ": " + job.status().message();
        });
    }
}
