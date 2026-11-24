package dev.kdenlivemcp.backend.kdenlive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kdenlivemcp.model.Fps;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What a Kdenlive document means, as sorted text lines: profile, bin clips, every track's entries (positions in
 * frames, source range, bin id, effects with their key parameters), mixes, A/V groups and duration.
 * Element ids, property order, ui state and metadata are ignored, so two files with equal summaries show the
 * same timeline. Used by the round-trip tests and to compare our output with what Kdenlive re-saves.
 */
public final class SemanticSummary {

    private SemanticSummary() {
    }

    public static List<String> of(Path file) {
        return of(Xml.parse(file));
    }

    public static List<String> of(String xml) {
        return of(Xml.parseString(xml));
    }

    public static List<String> of(Document doc) {
        Element mlt = doc.getDocumentElement();
        Map<String, Element> byId = new HashMap<>();
        for (Element e : Xml.children(mlt, null)) {
            if (e.hasAttribute("id")) {
                byId.put(e.getAttribute("id"), e);
            }
        }
        Element prof = Xml.children(mlt, "profile").getFirst();
        Fps fps = new Fps(Integer.parseInt(prof.getAttribute("frame_rate_num")),
                Integer.parseInt(prof.getAttribute("frame_rate_den")));
        List<String> out = new ArrayList<>();
        out.add("profile " + prof.getAttribute("width") + "x" + prof.getAttribute("height") + "@" + fps);

        Element bin = byId.get("main_bin");
        String seqId = Xml.prop(bin, "kdenlive:docproperties.activetimeline");
        Element seq = byId.get(seqId);
        if (seq == null) { // Kdenlive may write the sequence as tractorN with a kdenlive:uuid property
            seq = Xml.children(mlt, "tractor").stream().filter(t -> seqId != null && seqId.equals(Xml.prop(t, "kdenlive:uuid")))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("no sequence " + seqId));
        }
        final Element finalSeq = seq;
        TreeSet<String> binLines = new TreeSet<>();
        for (Element e : Xml.children(bin, "entry")) {
            Element p = byId.get(e.getAttribute("producer"));
            if (p == null || p == finalSeq) {
                continue;
            }
            binLines.add("bin " + Xml.prop(p, "kdenlive:id") + " " + Xml.prop(p, "mlt_service").replace("-novalidate", "")
                    + " " + name(Xml.prop(p, "resource")) + " len=" + Xml.prop(p, "length")
                    + " type=" + Xml.prop(p, "kdenlive:clip_type"));
        }
        out.addAll(binLines);

        int index = 0;
        for (Element t : Xml.children(seq, "track")) {
            Element tr = byId.get(t.getAttribute("producer"));
            if (!"tractor".equals(tr.getTagName())) {
                continue; // black background
            }
            List<Element> layers = Xml.children(tr, "track");
            out.add("track " + index + " " + ("1".equals(Xml.prop(tr, "kdenlive:audio_track")) ? "audio" : "video")
                    + " hide=" + layers.getFirst().getAttribute("hide"));
            for (int layer = 0; layer < layers.size(); layer++) {
                Element pl = byId.get(layers.get(layer).getAttribute("producer"));
                long cursor = 0;
                for (Element e : Xml.children(pl, null)) {
                    if (e.getTagName().equals("blank")) {
                        cursor += MltTime.parse(e.getAttribute("length"), fps);
                    } else if (e.getTagName().equals("entry")) {
                        long in = MltTime.parse(e.getAttribute("in"), fps);
                        long len = MltTime.parse(e.getAttribute("out"), fps) - in + 1;
                        Element p = byId.get(e.getAttribute("producer"));
                        StringBuilder sb = new StringBuilder("  L" + layer + " " + cursor + "+" + len + " in=" + in
                                + " bin=" + Xml.prop(e, "kdenlive:id") + " svc=" + Xml.prop(p, "mlt_service").replace("-novalidate", ""));
                        for (Element f : Xml.children(e, "filter")) {
                            sb.append(" fx[").append(Xml.prop(f, "kdenlive_id")).append(' ')
                                    .append(f.hasAttribute("in") ? MltTime.parse(f.getAttribute("in"), fps) : "-").append('-')
                                    .append(f.hasAttribute("out") ? MltTime.parse(f.getAttribute("out"), fps) : "-");
                            for (String key : List.of("level", "alpha", "gain", "end")) {
                                String v = Xml.prop(f, key);
                                if (v != null) {
                                    sb.append(' ').append(key).append('=').append(v.replace("00:00:00.000=", "0="));
                                }
                            }
                            sb.append(']');
                        }
                        out.add(sb.toString());
                        cursor += len;
                    }
                }
            }
            TreeSet<String> mixLines = new TreeSet<>(); // order inside the tractor carries no meaning
            for (Element x : Xml.children(tr, "transition")) {
                mixLines.add("  mix " + MltTime.parse(x.getAttribute("in"), fps) + "-" + MltTime.parse(x.getAttribute("out"), fps)
                        + " " + Xml.prop(x, "mlt_service") + " cut=" + Xml.prop(x, "kdenlive:mixcut")
                        + " wipe=" + (Xml.prop(x, "resource") != null && !Xml.prop(x, "resource").isBlank())
                        + " reverse=" + Xml.prop(x, "reverse"));
            }
            out.addAll(mixLines);
            for (Element f : Xml.children(tr, "filter")) {
                if (Xml.prop(f, "internal_added") == null) {
                    out.add("  trackfx " + Xml.prop(f, "kdenlive_id"));
                }
            }
            index++;
        }
        TreeSet<String> groups = new TreeSet<>();
        try {
            JsonNode g = new ObjectMapper().readTree(Xml.prop(seq, "kdenlive:sequenceproperties.groups"));
            for (JsonNode n : g) {
                TreeSet<String> leaves = new TreeSet<>();
                n.path("children").forEach(c -> leaves.add(c.path("data").asText().replaceFirst(":-1$", "")));
                groups.add("group " + n.path("type").asText() + " " + String.join(" ", leaves));
            }
        } catch (Exception e) {
            groups.add("group <unreadable>");
        }
        out.addAll(groups);
        for (Element x : Xml.children(seq, "transition")) {
            if (Xml.prop(x, "internal_added") == null) {
                out.add("composition " + Xml.prop(x, "mlt_service") + " " + Xml.prop(x, "a_track") + "->" + Xml.prop(x, "b_track"));
            } else {
                out.add("internal " + Xml.prop(x, "mlt_service") + " " + Xml.prop(x, "b_track"));
            }
        }
        out.add("duration " + Xml.prop(seq, "kdenlive:maxduration"));
        return out;
    }

    private static String name(String resource) {
        if (resource == null) {
            return "-";
        }
        int slash = Math.max(resource.lastIndexOf('/'), resource.lastIndexOf('\\'));
        return resource.substring(slash + 1);
    }
}
