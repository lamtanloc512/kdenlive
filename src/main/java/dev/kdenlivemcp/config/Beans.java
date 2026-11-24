package dev.kdenlivemcp.config;

import dev.kdenlivemcp.backend.EditorBackend;
import dev.kdenlivemcp.session.ProjectSession;
import dev.kdenlivemcp.title.TitlePresets;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.nio.file.Path;

/** CDI producers for the non-CDI core classes. */
public class Beans {

    @Produces
    @Singleton
    TitlePresets titlePresets(KdenliveConfig config) {
        return TitlePresets.load(config.titlePresets().filter(s -> !s.isBlank()).map(Path::of).orElse(null));
    }

    /** One open project per server; every edit is autosaved through the backend. */
    @Produces
    @Singleton
    ProjectSession projectSession(EditorBackend backend, KdenliveConfig config) {
        return new ProjectSession(p -> backend.save(p, p.file()), config.undoDepth());
    }
}
