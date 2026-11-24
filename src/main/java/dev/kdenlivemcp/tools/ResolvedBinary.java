package dev.kdenlivemcp.tools;

import java.nio.file.Path;
import java.util.Map;

/**
 * A located executable.
 *
 * @param source how it was found: "config", "kdenlive.app", "homebrew", "PATH", ...
 * @param env extra environment needed to run it (e.g. MLT_REPOSITORY for the melt bundled in kdenlive.app)
 */
public record ResolvedBinary(Binary binary, Path path, String source, String version, Map<String, String> env) {
    public ResolvedBinary {
        env = Map.copyOf(env);
    }
}
