package dev.kdenlivemcp.mcp;

import dev.kdenlivemcp.backend.kdenlive.KdenliveXmlBackend;
import dev.kdenlivemcp.edit.ProjectOps;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.ProfilePreset;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.session.ProjectSession;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** project_new / project_open / project_save / project_summary. */
public class ProjectTools {

    private final ProjectSession session;
    private final KdenliveXmlBackend backend;
    private final dev.kdenlivemcp.assets.AssetCatalog catalog;

    public ProjectTools(ProjectSession session, KdenliveXmlBackend backend, dev.kdenlivemcp.assets.AssetCatalog catalog) {
        this.session = session;
        this.backend = backend;
        this.catalog = catalog;
    }

    @Tool(name = "project_new", description = """
            Create a new empty Kdenlive project and make it the open project. It is saved immediately and after \
            every edit. Tracks: V2, V1 (video, V2 on top) and A1, A2 (audio). All times in other tools are seconds.
            Example: project_new(path="/Users/me/Videos/promo.kdenlive", preset="1080p30")""")
    public String projectNew(
            @ToolArg(description = "Absolute path of the .kdenlive file to create (~ allowed)") String path,
            @ToolArg(description = "Format: 1080p30 | 1080p60 | vertical_1080x1920_30 | 4k30", defaultValue = "1080p30")
            String preset,
            @ToolArg(description = "Replace the file if it already exists", defaultValue = "false") boolean overwrite) {
        return ToolSupport.call("project_new", () -> {
            Path file = projectPath(path);
            if (Files.exists(file) && !overwrite) {
                throw new EditException(file + " already exists; use project_open to edit it, or overwrite=true");
            }
            ProfilePreset p = ProfilePreset.byKey(preset);
            Project project = ProjectOps.create(file, p.profile);
            backend.save(project, file);
            session.open(project);
            return "created " + file + " (" + p.profile.description() + ", tracks V2 V1 A1 A2). Next: media_import.";
        });
    }

    @Tool(name = "project_open", description = """
            Open an existing .kdenlive project (Kdenlive 23.04+ format, one sequence). Replaces the open project \
            and clears undo history. Example: project_open(path="/Users/me/Videos/promo.kdenlive")""")
    public String projectOpen(@ToolArg(description = "Absolute path of the .kdenlive file") String path) {
        return ToolSupport.call("project_open", () -> {
            Path file = projectPath(path);
            Project p = backend.load(file);
            session.open(p);
            return Formatters.summary(p, catalog, 0, warnings(p));
        });
    }

    @Tool(name = "project_save", description = """
            Save the open project. Edits are autosaved already; use this with path to 'save as' a copy that \
            becomes the open project. Example: project_save() or project_save(path="/Users/me/Videos/v2.kdenlive")""")
    public String projectSave(@ToolArg(description = "Optional new path (save as)", required = false) String path) {
        return ToolSupport.call("project_save", () -> {
            Project p = session.save(path == null || path.isBlank() ? null : projectPath(path));
            return "saved " + p.file();
        });
    }

    @Tool(name = "project_summary", description = """
            Human/AI-readable overview of the open project: format, duration, every track with item ids and \
            times, bin clips with ids, transitions, warnings. Example: project_summary()""",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public String projectSummary() {
        return ToolSupport.call("project_summary", () -> {
            Project p = session.current();
            return Formatters.summary(p, catalog, session.undoDepth(), warnings(p));
        });
    }

    private List<String> warnings(Project p) {
        List<String> w = new ArrayList<>();
        backend.missingMedia(p).forEach(f -> w.add("media file missing: " + f));
        backend.titleRenderingProblem().ifPresent(w::add);
        return w;
    }

    static Path projectPath(String path) {
        Path file = absolute(path, "path");
        if (!file.getFileName().toString().endsWith(".kdenlive")) {
            throw new EditException("project path must end with .kdenlive: " + file);
        }
        return file;
    }

    static Path absolute(String path, String name) {
        if (path == null || path.isBlank()) {
            throw new EditException(name + " is required");
        }
        String p = path.trim();
        if (p.equals("~") || p.startsWith("~/")) {
            p = System.getProperty("user.home") + p.substring(1);
        }
        Path file = Path.of(p);
        if (!file.isAbsolute()) {
            throw new EditException(name + " must be an absolute path (or start with ~/): " + path);
        }
        return file.normalize();
    }
}
