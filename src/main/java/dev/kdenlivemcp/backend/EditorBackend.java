package dev.kdenlivemcp.backend;

import dev.kdenlivemcp.model.Project;

import java.nio.file.Path;

/**
 * Where projects come from and go to. The first implementation reads/writes .kdenlive files and
 * renders with melt; a live Kdenlive (RPC) or another editor can implement the same contract later.
 */
public interface EditorBackend {

    /** Short id for logs and project_summary, e.g. "kdenlive-xml". */
    String name();

    Project load(Path file);

    /** Writes the project atomically to {@code file}. */
    void save(Project project, Path file);

    /** Starts an asynchronous render of a snapshot of {@code project}. Never blocks on the render. */
    RenderJob render(Project project, RenderSpec spec);

    /** PNG bytes of the timeline frame at {@code frame}, scaled to at most {@code maxWidth} pixels wide. */
    byte[] grabFrame(Project project, long frame, int maxWidth);
}
