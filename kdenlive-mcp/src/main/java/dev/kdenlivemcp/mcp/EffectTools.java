package dev.kdenlivemcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.kdenlivemcp.assets.AssetCatalog;
import dev.kdenlivemcp.assets.AssetDef;
import dev.kdenlivemcp.assets.ParamCodec;
import dev.kdenlivemcp.backend.kdenlive.MltTime;
import dev.kdenlivemcp.edit.EffectOps;
import dev.kdenlivemcp.edit.TrackOps;
import dev.kdenlivemcp.edit.TransformOps;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Effect;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.session.ProjectSession;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Kdenlive's effect catalog, effect stacks with keyframes, camera moves and tracks. */
public class EffectTools {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LIST = 80;

    private final ProjectSession session;
    private final AssetCatalog catalog;
    private final EffectOps.Env env;

    public EffectTools(ProjectSession session, AssetCatalog catalog, EffectOps.Env env) {
        this.session = session;
        this.catalog = catalog;
        this.env = env;
    }

    // ------------------------------------------------------------------ catalog

    @Tool(name = "effect_catalog", description = """
            Search Kdenlive's effect catalog (the same ~450 effects as Kdenlive's Effects panel: color, blur, \
            transform, keying, distort, stylize, audio EQ/compressor/reverb, ...). Without query: categories with \
            counts. With query: matching effects (id — name: description). Then effect_info(effect) for parameters.
            Examples: effect_catalog(query="blur"); effect_catalog(query="color", kind="video"); effect_catalog()""",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public String effectCatalog(
            @ToolArg(description = "Words to match in id, name, description or category", required = false) String query,
            @ToolArg(description = "video | audio", required = false) String kind,
            @ToolArg(description = "Exact category name from the overview", required = false) String category,
            @ToolArg(description = "Also list effects this melt cannot render", defaultValue = "false")
            boolean includeUnavailable) {
        return ToolSupport.call("effect_catalog", () -> {
            String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && !k.equals("video") && !k.equals("audio")) {
                throw new EditException("kind must be video or audio");
            }
            List<AssetDef> hits = catalog.searchEffects(query, d -> !d.hidden()
                    && (k.isEmpty() || d.audio() == k.equals("audio"))
                    && (category == null || category.isBlank() || d.category().equalsIgnoreCase(category.trim()))
                    && (includeUnavailable || env.serviceAvailable().test(d.service())));
            boolean overview = (query == null || query.isBlank()) && (category == null || category.isBlank());
            if (overview) {
                return categoryOverview(hits, includeUnavailable);
            }
            return hits.isEmpty() ? "no effect matches" + (includeUnavailable ? "" : " (try includeUnavailable=true)")
                    : matchList(hits);
        });
    }

    private static String categoryOverview(List<AssetDef> hits, boolean includeUnavailable) {
        Map<String, List<String>> byCat = new TreeMap<>();
        hits.forEach(d -> byCat.computeIfAbsent(d.category() + (d.audio() ? " (audio)" : ""), c -> new ArrayList<>())
                .add(d.id()));
        StringBuilder sb = new StringBuilder(hits.size() + " effects"
                + (includeUnavailable ? "" : " available in this melt") + ". Categories:\n");
        byCat.forEach((c, ids) -> sb.append("  ").append(c).append(": ").append(ids.size()).append(" (e.g. ")
                .append(String.join(", ", ids.subList(0, Math.min(4, ids.size())))).append(")\n"));
        return sb.append("Search with effect_catalog(query=...) or category=...").toString();
    }

    private String matchList(List<AssetDef> hits) {
        StringBuilder sb = new StringBuilder(hits.size() + " match" + (hits.size() == 1 ? "" : "es") + ":\n");
        for (AssetDef d : hits.subList(0, Math.min(MAX_LIST, hits.size()))) {
            String desc = d.description().replaceAll("\\s+", " ");
            sb.append("  ").append(d.id()).append(" — ").append(d.name()).append(d.audio() ? " [audio]" : "")
                    .append(": ").append(desc.length() > 90 ? desc.substring(0, 89) + "…" : desc)
                    .append(env.serviceAvailable().test(d.service()) ? "" : " (not in this melt)").append('\n');
        }
        if (hits.size() > MAX_LIST) {
            sb.append("  … ").append(hits.size() - MAX_LIST).append(" more: refine the query\n");
        }
        return sb.toString().stripTrailing();
    }

    @Tool(name = "effect_info", description = """
            Parameters of one effect: name, type, range, default, choices, and which ones can be keyframed (*). \
            Values are in the units Kdenlive shows (e.g. percent). Example: effect_info(effect="qtblend")""",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public String effectInfo(@ToolArg(description = "Effect id from effect_catalog") String effect) {
        return ToolSupport.call("effect_info", () -> {
            AssetDef def = catalog.effect(effect).orElseThrow(() -> new EditException("unknown effect '" + effect
                    + "'; close matches: " + String.join(", ", catalog.suggestEffects(effect, 6))));
            var profile = session.peek().map(Project::profile)
                    .orElse(dev.kdenlivemcp.model.ProfilePreset.P1080_30.profile);
            Fps fps = profile.fps();
            ParamCodec.Context ctx = new ParamCodec.Context(profile.width(), profile.height(), 0, fps.frames(5),
                    profile.width(), profile.height(), f -> MltTime.clock(f, fps));
            return EffectView.info(def, env.serviceAvailable().test(def.service()), ctx);
        });
    }

    // ------------------------------------------------------------------ stacks

    @Tool(name = "effect_add", description = """
            Add a Kdenlive effect to a timeline item (c4, t7), a whole track (V1, A1) or the master (master = whole \
            timeline). params sets fixed values; keyframes animates parameters marked * in effect_info: a list of \
            {t, ease?, <param>: value, ...}, t in seconds from the start of the target ("end" = last frame). ease: \
            linear (default), smooth, discrete, ease_in, ease_out, ease_in_out, or sine/quad/cubic/quart/quint/expo/\
            circ/back/elastic/bounce + _in/_out/_in_out; it shapes the move to the next keyframe. Audio effects on an \
            A/V clip go to its audio part. Returns the effect id (e12).
            Examples: effect_add(target="c4", effect="avfilter.gblur", params={"av.sigma": 8}); \
            effect_add(target="c4", effect="brightness", keyframes=[{"t":0,"level":0},{"t":1,"level":100}]); \
            effect_add(target="V1", effect="sepia")""")
    public String effectAdd(
            @ToolArg(description = "Item id, track id, or master") String target,
            @ToolArg(description = "Effect id from effect_catalog") String effect,
            @ToolArg(description = "Fixed parameter values {name: value}", required = false) Map<String, Object> params,
            @ToolArg(description = "Keyframes [{t, ease?, <param>: value}]", required = false)
            List<Map<String, Object>> keyframes,
            @ToolArg(description = "Position in the target's stack (0 = first); default last", required = false)
            Integer position) {
        return ToolSupport.call("effect_add", () -> {
            Fps fps = session.current().fps();
            return session.apply("effect_add " + effect + " on " + target,
                    p -> EffectOps.add(p, env, target, effect, params, keys(fps, keyframes), position)).message();
        });
    }

    @Tool(name = "effect_set", description = """
            Change an effect: params replace fixed values (a keyframed parameter becomes constant), keyframes \
            replace the animation of the parameters they mention (same format as effect_add), enabled turns it \
            off/on. Example: effect_set(effectId="e12", params={"av.sigma": 3}, enabled=true)""")
    public String effectSet(
            @ToolArg(description = "Effect id, e.g. e12") String effectId,
            @ToolArg(description = "Fixed parameter values {name: value}", required = false) Map<String, Object> params,
            @ToolArg(description = "Keyframes [{t, ease?, <param>: value}]", required = false)
            List<Map<String, Object>> keyframes,
            @ToolArg(description = "false = bypass the effect, true = active", required = false) Boolean enabled) {
        return ToolSupport.call("effect_set", () -> {
            Fps fps = session.current().fps();
            return session.apply("effect_set " + effectId,
                    p -> EffectOps.set(p, env, effectId, params, keys(fps, keyframes), enabled)).message();
        });
    }

    @Tool(name = "effect_remove", description = "Remove an effect. Example: effect_remove(effectId=\"e12\")")
    public String effectRemove(@ToolArg(description = "Effect id") String effectId) {
        return ToolSupport.call("effect_remove",
                () -> session.apply("effect_remove " + effectId, p -> EffectOps.remove(p, effectId)).message());
    }

    @Tool(name = "effect_move", description = """
            Reorder an effect in its stack (effects run first to last). Example: effect_move(effectId="e12", index=0)""")
    public String effectMove(
            @ToolArg(description = "Effect id") String effectId,
            @ToolArg(description = "New position, 0 = first") int index) {
        return ToolSupport.call("effect_move",
                () -> session.apply("effect_move " + effectId, p -> EffectOps.move(p, effectId, index)).message());
    }

    @Tool(name = "effect_list", description = """
            Effects with their current values and keyframes (t in seconds from the target start), for one target \
            (item, track, master) or the whole project. Example: effect_list(target="c4")""",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public String effectList(
            @ToolArg(description = "Item id, track id or master; omit for all", required = false) String target,
            @ToolArg(description = "Show every parameter, not only changed ones", defaultValue = "false") boolean all) {
        return ToolSupport.call("effect_list", () -> {
            Project p = session.current();
            ArrayNode out = JSON.createArrayNode();
            for (EffectView.Placement pl : EffectView.placements(p)) {
                if (target != null && !target.isBlank() && !pl.owner().equalsIgnoreCase(target.trim())) {
                    continue;
                }
                for (Effect fx : pl.effects()) {
                    out.add(EffectView.json(p, catalog, pl, fx, all).put("target", pl.owner()));
                }
            }
            return out.isEmpty() ? "no effects" + (target == null || target.isBlank() ? "" : " on " + target)
                    : out.toString();
        });
    }

    // ------------------------------------------------------------------ camera

    @Tool(name = "clip_transform", description = """
            Pan / zoom / rotate / fade a picture over time (Ken Burns, punch-in, slow push, reveal) using Kdenlive's \
            Transform effect. keyframes: [{t, zoom, x?, y?, rotation?, opacity?, ease?}] with t in seconds from the \
            target start ("end" = last frame); zoom 1 = normal, 1.5 = closer, 0.5 = smaller picture; x/y = point of \
            the picture to center on (0..1, default 0.5); rotation in degrees; opacity 0..1. With clamp (default) \
            a zoomed picture never shows black edges. Calling again replaces the move.
            Example: clip_transform(target="c4", keyframes=[{"t":0,"zoom":1},{"t":"end","zoom":1.3,"x":0.7,"y":0.4,"ease":"ease_in_out"}])""")
    public String clipTransform(
            @ToolArg(description = "Item id (or a video track id)") String target,
            @ToolArg(description = "Camera keyframes [{t, zoom, x?, y?, rotation?, opacity?, ease?}]")
            List<Map<String, Object>> keyframes,
            @ToolArg(description = "Keep zoomed pictures covering the frame", defaultValue = "true") boolean clamp) {
        return ToolSupport.call("clip_transform", () -> {
            Fps fps = session.current().fps();
            List<TransformOps.CameraKey> cams = new ArrayList<>();
            if (keyframes == null) {
                throw new EditException("keyframes are required");
            }
            for (Map<String, Object> k : keyframes) {
                cams.add(new TransformOps.CameraKey(offset(fps, k.get("t")), dbl(k, "zoom", 1.0), dblOrNull(k, "x"),
                        dblOrNull(k, "y"), dblOrNull(k, "rotation"), dblOrNull(k, "opacity"),
                        k.get("ease") == null ? null : k.get("ease").toString()));
            }
            return session.apply("clip_transform " + target, p -> TransformOps.camera(p, env, target, cams, clamp))
                    .message();
        });
    }

    // ------------------------------------------------------------------ tracks

    @Tool(name = "track_add", description = """
            Add a track. A new video track goes on top (it covers the tracks below: overlays, picture-in-picture, \
            titles); a new audio track goes at the bottom. Example: track_add(kind="video", name="Overlays")""")
    public String trackAdd(
            @ToolArg(description = "video | audio") String kind,
            @ToolArg(description = "Optional track name", required = false) String name) {
        return ToolSupport.call("track_add",
                () -> session.apply("track_add " + kind, p -> TrackOps.add(p, kind, name)).message());
    }

    @Tool(name = "track_set", description = """
            Mute an audio track, hide a video track, lock a track or rename it. Omitted values stay.
            Example: track_set(track="A2", muted=true)""")
    public String trackSet(
            @ToolArg(description = "Track id") String track,
            @ToolArg(description = "Mute (audio tracks)", required = false) Boolean muted,
            @ToolArg(description = "Hide (video tracks)", required = false) Boolean hidden,
            @ToolArg(description = "Lock against edits in Kdenlive", required = false) Boolean locked,
            @ToolArg(description = "New name (\"\" clears it)", required = false) String name) {
        return ToolSupport.call("track_set", () -> session.apply("track_set " + track,
                p -> TrackOps.set(p, track, muted, hidden, locked, name)).message());
    }

    @Tool(name = "track_remove", description = """
            Remove a track with everything on it (linked parts on other tracks too). Tracks above/below are \
            renumbered like Kdenlive does. Example: track_remove(track="V3")""")
    public String trackRemove(@ToolArg(description = "Track id") String track) {
        return ToolSupport.call("track_remove",
                () -> session.apply("track_remove " + track, p -> TrackOps.remove(p, track)).message());
    }

    // ------------------------------------------------------------------ helpers

    private static final Set<String> KEY_FIELDS = Set.of("t", "ease", "values");

    static List<EffectOps.KeySpec> keys(Fps fps, List<Map<String, Object>> keyframes) {
        if (keyframes == null) {
            return null;
        }
        List<EffectOps.KeySpec> out = new ArrayList<>();
        for (Map<String, Object> k : keyframes) {
            if (k == null) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            if (k.get("values") instanceof Map<?, ?> m) {
                m.forEach((a, b) -> values.put(a.toString(), b));
            }
            k.forEach((a, b) -> {
                if (!KEY_FIELDS.contains(a)) {
                    values.put(a, b);
                }
            });
            out.add(new EffectOps.KeySpec(offset(fps, k.get("t")), k.get("ease") == null ? null : k.get("ease").toString(),
                    values));
        }
        return out;
    }

    /** Seconds from the target start; "end" or negative values count from the end (-1 frame = last frame). */
    static long offset(Fps fps, Object t) {
        if (t == null) {
            throw new EditException("every keyframe needs t (seconds from the start of the target, or \"end\")");
        }
        if (t instanceof Number n) {
            double s = n.doubleValue();
            return s < 0 ? -Math.max(1, fps.frames(-s)) : fps.frames(s);
        }
        String s = t.toString().trim();
        if (s.equalsIgnoreCase("end")) {
            return -1;
        }
        try {
            return offset(fps, Double.parseDouble(s));
        } catch (NumberFormatException e) {
            throw new EditException("t must be seconds or \"end\", not '" + t + "'");
        }
    }

    private static double dbl(Map<String, Object> m, String k, double dflt) {
        Double d = dblOrNull(m, k);
        return d == null ? dflt : d;
    }

    private static Double dblOrNull(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new EditException(k + " must be a number, not '" + v + "'");
        }
    }
}
