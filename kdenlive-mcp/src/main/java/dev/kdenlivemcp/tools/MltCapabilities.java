package dev.kdenlivemcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The MLT services the resolved melt reports via {@code melt -query <kind>}. */
public record MltCapabilities(Map<String, Set<String>> services, List<String> loadErrors) {

    public MltCapabilities {
        services = Map.copyOf(services);
        loadErrors = List.copyOf(loadErrors);
    }

    public MltCapabilities(Map<String, Set<String>> services) {
        this(services, List.of());
    }

    public static MltCapabilities none() {
        return new MltCapabilities(Map.of());
    }

    public boolean has(String kind, String service) {
        return services.getOrDefault(kind, Set.of()).contains(service);
    }

    public boolean canRenderTitles() {
        return has("producers", "kdenlivetitle");
    }

    /** Entries like "producers/kdenlivetitle" that are required but absent. */
    public List<String> missing(Map<String, List<String>> required) {
        List<String> out = new ArrayList<>();
        required.forEach((kind, names) -> names.stream()
                .filter(n -> !has(kind, n))
                .forEach(n -> out.add(kind + "/" + n)));
        out.sort(null);
        return out;
    }
}
