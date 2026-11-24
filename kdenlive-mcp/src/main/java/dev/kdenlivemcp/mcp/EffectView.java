package dev.kdenlivemcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.kdenlivemcp.assets.AssetCatalog;
import dev.kdenlivemcp.assets.AssetDef;
import dev.kdenlivemcp.assets.AssetParam;
import dev.kdenlivemcp.assets.ParamCodec;
import dev.kdenlivemcp.backend.kdenlive.MltTime;
import dev.kdenlivemcp.model.Effect;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TimelineItem;
import dev.kdenlivemcp.model.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Client-facing views of effects (values in UI units, keyframe times in seconds from the owner start). */
final class EffectView {

    private static final ObjectMapper JSON = new ObjectMapper();

    private EffectView() {
    }

    /** Where an effect sits: owner label, its timeline start and the anchor of its keyframe time base. */
    record Placement(String owner, long start, long anchor, List<Effect> effects) {
    }

    static List<Placement> placements(Project p) {
        List<Placement> out = new ArrayList<>();
        if (!p.masterEffects().isEmpty()) {
            out.add(new Placement(Project.MASTER, 0, 0, p.masterEffects()));
        }
        for (Track t : p.tracks()) {
            if (!t.effects().isEmpty()) {
                out.add(new Placement(t.id(), 0, 0, t.effects()));
            }
            for (TimelineItem i : t.items()) {
                if (!i.effects().isEmpty()) {
                    out.add(new Placement(i.id(), i.start(), i.sourceIn(), i.effects()));
                }
            }
        }
        return out;
    }

    /** One effect as JSON: id, effect, name, enabled, params (non-default or keyframed only unless all=true). */
    static ObjectNode json(Project p, AssetCatalog catalog, Placement pl, Effect fx, boolean all) {
        Fps fps = p.fps();
        ObjectNode n = JSON.createObjectNode();
        n.put("id", fx.id());
        n.put("effect", fx.assetId());
        Optional<AssetDef> def = catalog.effect(fx.assetId());
        n.put("name", def.map(AssetDef::name).orElse(fx.assetId()));
        if (fx.disabled()) {
            n.put("enabled", false);
        }
        ObjectNode params = n.putObject("params");
        if (def.isEmpty()) {
            fx.props().forEach((k, v) -> {
                if (!k.equals(Effect.SERVICE) && !k.equals(Effect.KDENLIVE_ID) && !k.startsWith("kdenlive:")) {
                    params.put(k, v);
                }
            });
            return n;
        }
        for (AssetParam ap : def.get().params()) {
            if (!ap.userSettable() || ap.names().size() > 1) {
                continue;
            }
            String raw = fx.props().get(ap.name());
            if (raw == null) {
                continue;
            }
            List<ParamCodec.Key> keys = ap.animated() && raw.contains("=")
                    ? ParamCodec.decode(raw, s -> MltTime.parse(s, fps)) : List.of();
            if (keys.size() > 1) {
                ArrayNode arr = params.putArray(ap.name());
                for (ParamCodec.Key k : keys) {
                    ObjectNode kn = arr.addObject();
                    kn.put("t", Formatters.sec(fps, k.frame() - pl.anchor()));
                    put(kn, "value", ParamCodec.toUi(ap, k.raw()));
                    if (!"linear".equals(k.ease())) {
                        kn.put("ease", k.ease());
                    }
                }
                continue;
            }
            String value = keys.size() == 1 ? keys.getFirst().raw() : raw;
            String dflt = ap.defaultValue() == null ? "" : ap.defaultValue();
            if (all || !value.equals(dflt)) {
                put(params, ap.name(), ParamCodec.toUi(ap, value));
            }
        }
        return n;
    }

    /** Compact one-liners for timeline_get: [{id, effect, keyframed:[...]}]. */
    static ArrayNode compact(Project p, AssetCatalog catalog, Placement pl) {
        ArrayNode arr = JSON.createArrayNode();
        for (Effect fx : pl.effects()) {
            ObjectNode full = json(p, catalog, pl, fx, false);
            ObjectNode n = arr.addObject();
            n.put("id", fx.id());
            n.put("effect", fx.assetId());
            if (fx.disabled()) {
                n.put("enabled", false);
            }
            if (!full.path("params").isEmpty()) {
                n.set("params", full.get("params"));
            }
        }
        return arr;
    }

    static String oneLine(Project p, AssetCatalog catalog, Effect fx) {
        String name = catalog.effect(fx.assetId()).map(AssetDef::name).orElse(fx.assetId());
        return fx.id() + " " + name + (fx.disabled() ? " (off)" : "");
    }

    private static void put(ObjectNode n, String key, Object v) {
        switch (v) {
            case null -> n.putNull(key);
            case Double d -> n.put(key, d);
            case Boolean b -> n.put(key, b);
            case Number num -> n.put(key, num.doubleValue());
            default -> n.put(key, v.toString());
        }
    }

    /** effect_info text: description and every settable parameter with type, range, default and choices. */
    static String info(AssetDef def, boolean available, ParamCodec.Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(def.id()).append(" — ").append(def.name()).append(" [").append(def.audio() ? "audio" : "video")
                .append(", ").append(def.category()).append("]");
        if (!available) {
            sb.append(" (NOT available in this melt: Kdenlive can use it, previews/renders here skip it)");
        }
        sb.append('\n').append(def.description()).append('\n');
        if (def.unique()) {
            sb.append("once per item\n");
        }
        sb.append("parameters (values in Kdenlive's units; * = keyframable):\n");
        int shown = 0;
        for (AssetParam ap : def.params()) {
            if (!ap.userSettable()) {
                continue;
            }
            shown++;
            sb.append("  ").append(ap.name()).append(ap.animated() ? "*" : "").append(" (").append(typeLabel(ap))
                    .append(")");
            if (!ap.title().equals(ap.name())) {
                sb.append(" \"").append(ap.title()).append('"');
            }
            sb.append(ParamCodec.range(ap));
            String dflt = ParamCodec.defaultRaw(ap, ctx);
            if (ap.animated() && dflt.contains("=") && !dflt.contains(";")) {
                dflt = dflt.substring(dflt.indexOf('=') + 1);
            }
            if (!dflt.isEmpty()) {
                Object ui = ParamCodec.toUi(ap, dflt);
                sb.append(" default ").append(ui instanceof Double d ? ParamCodec.num(d) : ui);
            }
            if (!ap.values().isEmpty()) {
                sb.append(" choices: ").append(ParamCodec.choices(ap));
            }
            if (!ap.comment().isBlank()) {
                sb.append(" — ").append(ap.comment().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip());
            }
            sb.append('\n');
        }
        if (shown == 0) {
            sb.append("  (none: the effect works as is)\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String typeLabel(AssetParam ap) {
        return switch (ap.kind()) {
            case NUMBER -> "number";
            case BOOL -> "bool";
            case LIST -> "choice";
            case COLOR -> "color #RRGGBB[AA]";
            case RECT -> "rect {x,y,w,h,opacity} px";
            case POINT -> "point {x,y}";
            case TEXT -> ap.type().toLowerCase(Locale.ROOT);
            case RAW -> "raw " + ap.type() + " string";
            default -> ap.type();
        };
    }
}
