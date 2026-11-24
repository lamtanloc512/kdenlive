package dev.kdenlivemcp.backend;

import java.nio.file.Path;

/** What to render and how. */
public record RenderSpec(Path output, RenderPreset preset) {
}
