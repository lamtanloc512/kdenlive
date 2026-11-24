package dev.kdenlivemcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.FadeToBlack;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.MediaClip;
import dev.kdenlivemcp.model.Mix;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TimelineItem;
import dev.kdenlivemcp.model.TitleItem;
import dev.kdenlivemcp.model.Track;
import dev.kdenlivemcp.model.Transition;

import java.util.List;
import java.util.Locale;

/** Compact, model-friendly text/JSON views of a project. */
final class Formatters {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Formatters() {
    }

    static double sec(Fps fps, long frames) {
        return Math.round(fps.seconds(frames) * 1000) / 1000.0;
    }

    static String timelineJson(Project p) {
        Fps fps = p.fps();
        ObjectNode root = JSON.createObjectNode();
        root.put("fps", fps.toDouble());
        root.put("duration", sec(fps, p.duration()));
        ArrayNode tracks = root.putArray("tracks");
        for (Track t : p.tracks()) {
            ObjectNode tn = tracks.addObject();
            tn.put("id", t.id());
            tn.put("kind", t.kind().name().toLowerCase(Locale.ROOT));
            ArrayNode items = tn.putArray("items");
            for (TimelineItem i : t.items()) {
                ObjectNode in = items.addObject();
                in.put("id", i.id());
                in.put("start", sec(fps, i.start()));
                in.put("end", sec(fps, i.end()));
                switch (i) {
                    case ClipItem c -> {
                        MediaClip m = p.media(c.mediaId()).orElse(null);
                        in.put("type", "clip");
                        in.put("clip", c.mediaId());
                        in.put("name", m == null ? c.mediaId() : m.name());
                        in.put("in", sec(fps, c.in()));
                        in.put("out", sec(fps, c.out()));
                        if (c.linkId() != null) {
                            in.put("link", c.linkId());
                        }
                        if (c.audio().volumeDb() != 0) {
                            in.put("volumeDb", c.audio().volumeDb());
                        }
                        if (c.audio().fadeIn() > 0) {
                            in.put("fadeIn", sec(fps, c.audio().fadeIn()));
                        }
                        if (c.audio().fadeOut() > 0) {
                            in.put("fadeOut", sec(fps, c.audio().fadeOut()));
                        }
                        if (!c.extras().xml().isEmpty()) {
                            in.put("otherEffects", c.extras().xml().size());
                        }
                    }
                    case TitleItem ti -> {
                        in.put("type", "title");
                        in.put("text", ti.text());
                        in.put("style", ti.styleId());
                    }
                }
            }
        }
        ArrayNode trans = root.putArray("transitions");
        for (Transition t : p.transitions()) {
            ObjectNode tn = trans.addObject();
            tn.put("id", t.id());
            switch (t) {
                case Mix m -> {
                    tn.put("type", m.type().name().toLowerCase(Locale.ROOT));
                    tn.put("itemA", m.itemA());
                    tn.put("itemB", m.itemB());
                    long cut = p.tracks().stream().flatMap(tr -> tr.items().stream())
                            .filter(i -> i.id().equals(m.itemB())).findFirst().map(TimelineItem::start).orElse(0L);
                    tn.put("start", sec(fps, cut - m.cutOffset()));
                    tn.put("end", sec(fps, cut - m.cutOffset() + m.length()));
                }
                case FadeToBlack f -> {
                    tn.put("type", "fade_to_black");
                    tn.put("item", f.itemId());
                    tn.put("duration", sec(fps, f.length()));
                }
            }
        }
        return root.toString();
    }

    static String summary(Project p, int undoSteps, List<String> warnings) {
        Fps fps = p.fps();
        StringBuilder sb = new StringBuilder();
        sb.append("project: ").append(p.file()).append('\n');
        sb.append(String.format(Locale.ROOT, "format: %dx%d @ %s fps (%s), duration %.3fs%n", p.profile().width(),
                p.profile().height(), fps.label(), p.profile().mltName(), fps.seconds(p.duration())));
        sb.append("tracks (top to bottom):\n");
        for (Track t : p.tracks()) {
            sb.append("  ").append(t.id()).append(' ').append(t.kind().name().toLowerCase(Locale.ROOT)).append(": ");
            if (t.items().isEmpty()) {
                sb.append("empty");
            }
            for (TimelineItem i : t.items()) {
                String name = switch (i) {
                    case ClipItem c -> p.media(c.mediaId()).map(MediaClip::name).orElse(c.mediaId());
                    case TitleItem ti -> "\"" + ti.text() + "\"";
                };
                sb.append(String.format(Locale.ROOT, "[%s %s %.3f–%.3f] ", i.id(), name, fps.seconds(i.start()),
                        fps.seconds(i.end())));
            }
            sb.append('\n');
        }
        sb.append("bin:\n");
        if (p.bin().isEmpty()) {
            sb.append("  empty (media_import to add files)\n");
        }
        for (MediaClip m : p.bin()) {
            sb.append("  ").append(m.id()).append(' ').append(m.name()).append(' ').append(m.info().describe()).append('\n');
        }
        if (!p.transitions().isEmpty()) {
            sb.append("transitions:\n");
            for (Transition t : p.transitions()) {
                sb.append("  ").append(switch (t) {
                    case Mix m -> String.format(Locale.ROOT, "%s %s %s→%s %.3fs", m.id(),
                            m.type().name().toLowerCase(Locale.ROOT), m.itemA(), m.itemB(), fps.seconds(m.length()));
                    case FadeToBlack f -> String.format(Locale.ROOT, "%s fade_to_black on %s, %.3fs", f.id(), f.itemId(),
                            fps.seconds(f.length()));
                }).append('\n');
            }
        }
        sb.append("undo steps available: ").append(undoSteps);
        warnings.forEach(w -> sb.append("\nwarning: ").append(w));
        return sb.toString();
    }
}
