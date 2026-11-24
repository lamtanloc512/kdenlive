package dev.kdenlivemcp.config;

import dev.kdenlivemcp.assets.AssetCatalog;
import dev.kdenlivemcp.backend.EditorBackend;
import dev.kdenlivemcp.edit.EffectOps;
import dev.kdenlivemcp.session.ProjectSession;
import dev.kdenlivemcp.title.TitlePresets;
import dev.kdenlivemcp.tools.ExternalTools;
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

    /** Kdenlive's effect and transition definitions (bundled copies of its data/ XML). */
    @Produces
    @Singleton
    AssetCatalog assetCatalog() {
        return AssetCatalog.load();
    }

    /** Catalog plus "does this melt have the filter", queried lazily from melt -query. */
    @Produces
    @Singleton
    EffectOps.Env effectEnv(AssetCatalog catalog, ExternalTools tools) {
        return new EffectOps.Env(catalog, service -> tools.mltCapabilities().has("filters", service),
                service -> tools.mltCapabilities().has("transitions", service),
                dev.kdenlivemcp.assets.LumaLibrary.discover(tools.find(dev.kdenlivemcp.tools.Binary.MELT)
                        .map(dev.kdenlivemcp.tools.ResolvedBinary::path).orElse(null)));
    }
}
