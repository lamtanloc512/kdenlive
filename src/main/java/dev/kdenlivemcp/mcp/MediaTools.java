package dev.kdenlivemcp.mcp;

import dev.kdenlivemcp.edit.EditResult;
import dev.kdenlivemcp.edit.ProjectOps;
import dev.kdenlivemcp.media.FfprobeService;
import dev.kdenlivemcp.media.MediaException;
import dev.kdenlivemcp.media.MediaInfo;
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

    public MediaTools(ProjectSession session, FfprobeService ffprobe) {
        this.session = session;
        this.ffprobe = ffprobe;
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
