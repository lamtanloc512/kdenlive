package dev.kdenlivemcp.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import dev.kdenlivemcp.model.DocumentMeta;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Extras;
import dev.kdenlivemcp.model.MediaClip;
import dev.kdenlivemcp.model.Project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kdenlive guides (timeline markers, {@code kdenlive:sequenceproperties.guides} on the sequence) and clip markers
 * ({@code kdenlive:markers} on a bin clip). Both are JSON lists of {pos, comment, type, duration} in frames, type
 * being one of Kdenlive's 9 marker categories (0 purple, 1 blue, 2 cyan, 3 green, 4 yellow, 5 orange, 6 dark
 * orange, 7 red-orange, 8 red).
 */
public final class MarkerOps {

    static final String GUIDES = "seq:kdenlive:sequenceproperties.guides";
    static final String CLIP_MARKERS = "kdenlive:markers";
    public static final List<String> CATEGORY_COLORS = List.of("purple", "blue", "cyan", "green", "yellow", "orange",
            "dark orange", "red-orange", "red");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public record Marker(long pos, String comment, int category, long duration) {
    }

    private MarkerOps() {
    }

    /** @param clipId bin clip id for a clip marker (pos = source frame), or null for a timeline guide */
    public static EditResult add(Project p, String clipId, long pos, String comment, int category, long duration) {
        if (category < 0 || category > 8) {
            throw new EditException("category must be 0..8 (" + String.join(", ", CATEGORY_COLORS) + ")");
        }
        if (pos < 0 || duration < 0) {
            throw new EditException("marker position and duration must be >= 0");
        }
        Draft d = new Draft(p);
        List<Marker> list = new ArrayList<>(read(d, clipId));
        list.removeIf(m -> m.pos() == pos);
        list.add(new Marker(pos, comment == null ? "" : comment, category, duration));
        write(d, clipId, list);
        return new EditResult(d.build(), (clipId == null ? "guide" : "marker on " + clipId) + " at " + d.sec(pos) + "s"
                + (duration > 0 ? " for " + d.sec(duration) + "s" : "") + " \"" + (comment == null ? "" : comment)
                + "\" (" + CATEGORY_COLORS.get(category) + ")", d.notes, List.of());
    }

    public static EditResult remove(Project p, String clipId, Long pos) {
        Draft d = new Draft(p);
        List<Marker> list = new ArrayList<>(read(d, clipId));
        int before = list.size();
        if (pos == null) {
            list.clear();
        } else {
            list.removeIf(m -> m.pos() == pos);
        }
        if (list.size() == before) {
            throw new EditException("no " + (clipId == null ? "guide" : "marker on " + clipId)
                    + (pos == null ? "s" : " at " + d.sec(pos) + "s") + "; existing: " + String.join(", ",
                    read(d, clipId).stream().map(m -> d.sec(m.pos()) + "s").toList()));
        }
        write(d, clipId, list);
        return new EditResult(d.build(), "removed " + (before - list.size()) + " "
                + (clipId == null ? "guide(s)" : "marker(s) on " + clipId), d.notes, List.of());
    }

    public static List<Marker> list(Project p, String clipId) {
        return read(new Draft(p), clipId);
    }

    // ------------------------------------------------------------------ storage

    private static List<Marker> read(Draft d, String clipId) {
        String json = clipId == null ? d.meta.extras().properties().get(GUIDES)
                : d.media(clipId).extras().properties().get(CLIP_MARKERS);
        List<Marker> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            for (JsonNode n : JSON.readTree(json)) {
                out.add(new Marker(n.path("pos").asLong(), n.path("comment").asText(""), n.path("type").asInt(0),
                        n.path("duration").asLong(0)));
            }
        } catch (Exception e) {
            throw new EditException("cannot read the existing markers: " + e.getMessage());
        }
        out.sort(Comparator.comparingLong(Marker::pos));
        return out;
    }

    private static void write(Draft d, String clipId, List<Marker> list) {
        ArrayNode arr = JSON.createArrayNode();
        list.stream().sorted(Comparator.comparingLong(Marker::pos)).forEach(m -> {
            ObjectNode o = arr.addObject();
            o.put("comment", m.comment());
            o.put("duration", m.duration());
            o.put("pos", m.pos());
            o.put("type", m.category());
        });
        String json;
        try {
            // QJsonDocument::toJson layout: 4-space indent, trailing newline
            DefaultPrettyPrinter pp = new DefaultPrettyPrinter().withArrayIndenter(new DefaultIndenter("    ", "\n"))
                    .withObjectIndenter(new DefaultIndenter("    ", "\n"));
            json = arr.isEmpty() ? "[\n]\n" : JSON.writer(pp).writeValueAsString(arr).replace(" : ", ": ") + "\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (clipId == null) {
            Map<String, String> props = new LinkedHashMap<>(d.meta.extras().properties());
            props.put(GUIDES, json);
            d.meta = new DocumentMeta(d.meta.sequenceUuid(), d.meta.documentId(), new Extras(props, d.meta.extras().xml()));
        } else {
            MediaClip m = d.media(clipId);
            Map<String, String> props = new LinkedHashMap<>(m.extras().properties());
            if (list.isEmpty()) {
                props.remove(CLIP_MARKERS);
            } else {
                props.put(CLIP_MARKERS, json);
            }
            d.bin.set(d.bin.indexOf(m), new MediaClip(m.id(), m.binId(), m.path(), m.name(), m.info(),
                    new Extras(props, m.extras().xml())));
        }
    }
}
