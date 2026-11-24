package dev.kdenlivemcp.mcp;

import dev.kdenlivemcp.config.KdenliveConfig;
import dev.kdenlivemcp.tools.Binary;
import dev.kdenlivemcp.tools.ExternalTools;
import dev.kdenlivemcp.tools.MltCapabilities;
import dev.kdenlivemcp.tools.ResolvedBinary;
import io.quarkiverse.mcp.server.Tool;

import java.util.List;
import java.util.Optional;

/** Diagnostics: which binaries are used and what they can do. */
public class ServerTools {

    private final ExternalTools tools;
    private final KdenliveConfig config;
    private final dev.kdenlivemcp.backend.kdenlive.KdenliveXmlBackend backend;

    public ServerTools(ExternalTools tools, KdenliveConfig config,
                       dev.kdenlivemcp.backend.kdenlive.KdenliveXmlBackend backend) {
        this.tools = tools;
        this.config = config;
        this.backend = backend;
    }

    @Tool(name = "server_check",
            description = "Report the melt / ffprobe / ffmpeg binaries this server uses (path, version, how found), "
                    + "MLT services that are missing (e.g. kdenlivetitle needed to render titles), and the work/log "
                    + "directories. Call it first if rendering or importing fails. Example: server_check()",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public String serverCheck() {
        StringBuilder sb = new StringBuilder();
        for (Binary b : Binary.values()) {
            Optional<ResolvedBinary> r = tools.find(b);
            sb.append(b.exe).append(": ");
            if (r.isPresent()) {
                sb.append(r.get().path()).append(" [").append(r.get().source()).append("] ").append(r.get().version());
                if (!r.get().env().isEmpty()) {
                    sb.append(" env=").append(r.get().env());
                }
            } else {
                sb.append("NOT FOUND (set ").append(b.envVar).append(')');
            }
            sb.append('\n');
        }
        MltCapabilities caps = tools.mltCapabilities();
        List<String> missing = caps.missing(ExternalTools.REQUIRED_MLT);
        sb.append("mlt services: ").append(missing.isEmpty() ? "all required services present" : "MISSING " + missing);
        if (!caps.canRenderTitles()) {
            sb.append(" (titles will not render: use the melt bundled with Kdenlive, which has the qt module)");
        }
        sb.append('\n');
        sb.append("title rendering: ").append(backend.titleRenderingProblem().orElse("ok")).append('\n');
        sb.append("work dir: ").append(config.workDir()).append('\n');
        sb.append("log dir: ").append(config.logDir());
        return sb.toString();
    }
}
