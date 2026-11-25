package dev.kdenlivemcp.title;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kdenlivemcp.model.EditException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The fixed list of title styles the AI may use. Loaded from {@code title-presets.json} on the classpath,
 * or from a file given with {@code kdenlive.title-presets} / {@code TITLE_PRESETS}.
 */
public final class TitlePresets {

    static final Set<String> POSITIONS = Set.of("top", "center", "lower_third", "bottom");

    private final Map<String, TitleStyle> styles;

    public TitlePresets(Map<String, TitleStyle> styles) {
        this.styles = new LinkedHashMap<>(styles);
    }

    public static TitlePresets load(Path override) {
        if (override != null && Files.isRegularFile(override)) {
            try (InputStream in = Files.newInputStream(override)) {
                return parse(in, override.toString());
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read title presets " + override, e);
            }
        }
        try (InputStream in = TitlePresets.class.getResourceAsStream("/title-presets.json")) {
            if (in == null) {
                throw new IllegalStateException("title-presets.json missing from classpath");
            }
            return parse(in, "title-presets.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static TitlePresets parse(InputStream in, String source) throws IOException {
        JsonNode root = new ObjectMapper().readTree(in);
        Map<String, TitleStyle> map = new LinkedHashMap<>();
        for (JsonNode n : root.path("presets")) {
            String id = n.path("id").asText();
            String position = n.path("position").asText("lower_third");
            if (id.isBlank() || !id.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException(source + ": preset id '" + id + "' must be lower_snake_case");
            }
            if (!POSITIONS.contains(position)) {
                throw new IllegalArgumentException(source + ": preset " + id + " has position '" + position
                        + "'; use one of " + POSITIONS);
            }
            map.put(id, new TitleStyle(id, n.path("description").asText(""), n.path("font").asText("DejaVu Sans"),
                    n.path("weight").asInt(400), n.path("size").asInt(64), n.path("color").asText("255,255,255,255"),
                    n.path("outline").asDouble(0), n.path("outlineColor").asText("0,0,0,255"), position,
                    n.path("fadeIn").asDouble(0), n.path("fadeOut").asDouble(0),
                    n.hasNonNull("box") && !n.path("box").asText().isBlank() ? n.path("box").asText() : null,
                    n.path("boxFit").asBoolean(false), n.path("boxRadius").asDouble(0)));
        }
        if (map.isEmpty()) {
            throw new IllegalArgumentException(source + " defines no presets");
        }
        return new TitlePresets(map);
    }

    public Collection<String> ids() {
        return styles.keySet();
    }

    public TitleStyle get(String id) {
        TitleStyle s = id == null ? null : styles.get(id);
        if (s == null) {
            throw new EditException("unknown title style '" + id + "'; styles: " + String.join(", ", styles.keySet()));
        }
        return s;
    }

    public boolean has(String id) {
        return id != null && styles.containsKey(id);
    }

    /** "lower_third: white 64px lower third, 0.3s fades; ..." for tool descriptions and errors. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        styles.values().forEach(s -> sb.append(s.id()).append(" (").append(s.description()).append("); "));
        return sb.substring(0, Math.max(0, sb.length() - 2));
    }
}
