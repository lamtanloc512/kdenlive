package dev.kdenlivemcp.assets;

import java.util.List;
import java.util.Optional;

/**
 * A Kdenlive effect or transition definition (data/effects/*.xml, data/transitions/*.xml).
 *
 * @param id Kdenlive id, written as {@code kdenlive_id} (defaults to the MLT service)
 * @param service MLT service ({@code tag} attribute), written as {@code mlt_service}
 * @param type the XML {@code type} attribute: audio, video, hidden, ... (empty for most video effects)
 * @param version the XML {@code version} attribute, 0 when absent; the highest version of an id wins
 * @param unique Kdenlive allows this effect only once per item
 */
public record AssetDef(Domain domain, String id, String service, String name, String description, String type,
                       double version, boolean unique, String category, List<AssetParam> params, String file) {

    public AssetDef {
        params = List.copyOf(params);
    }

    public boolean audio() {
        return "audio".equals(type) || "audiotransition".equals(type);
    }

    public boolean hidden() {
        return "hidden".equals(type);
    }

    public Optional<AssetParam> param(String name) {
        return params.stream().filter(p -> p.name() != null && p.name().equals(name)).findFirst();
    }

    public AssetDef withCategory(String newCategory) {
        return new AssetDef(domain, id, service, name, description, type, version, unique, newCategory, params, file);
    }

    public enum Domain { EFFECT, TRANSITION, GENERATOR }
}
