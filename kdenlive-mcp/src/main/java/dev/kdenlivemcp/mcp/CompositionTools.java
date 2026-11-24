package dev.kdenlivemcp.mcp;

import dev.kdenlivemcp.assets.AssetCatalog;
import dev.kdenlivemcp.assets.AssetDef;
import dev.kdenlivemcp.assets.ParamCodec;
import dev.kdenlivemcp.backend.kdenlive.MltTime;
import dev.kdenlivemcp.edit.CompositionOps;
import dev.kdenlivemcp.edit.EffectOps;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.Profile;
import dev.kdenlivemcp.model.ProfilePreset;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.session.ProjectSession;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

import java.util.List;
import java.util.Map;

/** Kdenlive compositions: transitions between tracks (wipes, slides, picture-in-picture, blend modes). */
public class CompositionTools {

    private final ProjectSession session;
    private final AssetCatalog catalog;
    private final EffectOps.Env env;

    public CompositionTools(ProjectSession session, AssetCatalog catalog, EffectOps.Env env) {
        this.session = session;
        this.catalog = catalog;
        this.env = env;
    }

    @Tool(name = "composition_catalog", description = """
            Kdenlive's compositions (Compositions panel): how a track is combined with the track below over a time \
            span — wipe (with any wipe shape), dissolve, slide, Composite and transform (picture-in-picture, scale, \
            opacity, blend modes), region, affine, and blend modes such as screen, multiply, overlay, addition. \
            Then composition_info(type) for parameters. Example: composition_catalog(query="blend")""",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public String compositionCatalog(
            @ToolArg(description = "Words to match", required = false) String query,
            @ToolArg(description = "Also list ones this melt cannot render", defaultValue = "false")
            boolean includeUnavailable) {
        return ToolSupport.call("composition_catalog", () -> {
            List<AssetDef> hits = catalog.searchTransitions(query,
                    d -> includeUnavailable || env.transitionAvailable().test(d.service()));
            if (hits.isEmpty()) {
                return "no composition matches" + (includeUnavailable ? "" : " (try includeUnavailable=true)");
            }
            StringBuilder sb = new StringBuilder(hits.size() + " composition" + (hits.size() == 1 ? "" : "s") + ":\n");
            for (AssetDef d : hits) {
                String desc = d.description().replaceAll("\\s+", " ");
                sb.append("  ").append(d.id()).append(" — ").append(d.name()).append(d.audio() ? " [audio]" : "")
                        .append(": ").append(desc.length() > 90 ? desc.substring(0, 89) + "…" : desc)
                        .append(env.transitionAvailable().test(d.service()) ? "" : " (not in this melt)").append('\n');
            }
            return sb.toString().stripTrailing();
        });
    }

    @Tool(name = "composition_info", description = """
            Parameters of a composition (type, range, default, choices, * = keyframable), including the list of \
            wipe shapes. Example: composition_info(type="wipe")""",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public String compositionInfo(@ToolArg(description = "Composition id from composition_catalog") String type) {
        return ToolSupport.call("composition_info", () -> {
            AssetDef def = catalog.transition(type).orElseThrow(() -> new EditException("unknown composition '" + type
                    + "'; close matches: " + String.join(", ", catalog.suggestTransitions(type, 6))));
            Profile profile = session.peek().map(Project::profile).orElse(ProfilePreset.P1080_30.profile);
            Fps fps = profile.fps();
            ParamCodec.Context ctx = new ParamCodec.Context(profile.width(), profile.height(), 0, fps.frames(2),
                    profile.width(), profile.height(), f -> MltTime.clock(f, fps), env.lumas());
            return EffectView.info(def, env.transitionAvailable().test(def.service()), ctx, env.lumas());
        });
    }

    @Tool(name = "composition_add", description = """
            Combine a track with a lower one from 'at' for 'duration' seconds. track = the upper track (its clips \
            are the "B" picture), withTrack = the lower track (default: the next video track below; "background" = \
            black). Typical uses: wipe/dissolve between a clip on V2 and one on V1 (overlap them by the duration), \
            slide (params {"rect": {"from":"left","to":"center"}}), picture-in-picture with qtblend \
            (keyframes on rect {x,y,w,h,opacity} in pixels), blend modes (frei0r.screen, frei0r.multiply, ... or \
            qtblend compositing). keyframes: [{t, ease?, <param>: value}] with t from the composition start.
            Examples: composition_add(type="wipe", track="V2", at=4, duration=1, params={"luma":"Iris Circle"}); \
            composition_add(type="qtblend", track="V2", at=0, duration=5, params={"rect":{"x":1240,"y":60,"w":640,"h":360}})""")
    public String compositionAdd(
            @ToolArg(description = "Composition id, e.g. wipe, dissolve, slide, qtblend, frei0r.screen") String type,
            @ToolArg(description = "Upper track, e.g. V2") String track,
            @ToolArg(description = "Start, seconds on the timeline") double at,
            @ToolArg(description = "Duration in seconds") double duration,
            @ToolArg(description = "Lower track (default: next video track below; or background)", required = false)
            String withTrack,
            @ToolArg(description = "Fixed parameter values {name: value}", required = false) Map<String, Object> params,
            @ToolArg(description = "Keyframes [{t, ease?, <param>: value}]", required = false)
            List<Map<String, Object>> keyframes) {
        return ToolSupport.call("composition_add", () -> {
            Fps fps = session.current().fps();
            long atF = ToolSupport.frames(fps, "at", at);
            long len = ToolSupport.positive(fps, "duration", duration);
            return session.apply("composition_add " + type, p -> CompositionOps.add(p, env, type, track, withTrack, atF,
                    len, params, EffectTools.keys(fps, keyframes))).message();
        });
    }

    @Tool(name = "composition_set", description = """
            Change a composition: params, keyframes (replace the named parameters' animation), at/duration, \
            withTrack. Example: composition_set(compositionId="x14", params={"softness": 20}, duration=1.5)""")
    public String compositionSet(
            @ToolArg(description = "Composition id, e.g. x14") String compositionId,
            @ToolArg(description = "Fixed parameter values", required = false) Map<String, Object> params,
            @ToolArg(description = "Keyframes [{t, ease?, <param>: value}]", required = false)
            List<Map<String, Object>> keyframes,
            @ToolArg(description = "New start (seconds)", required = false) Double at,
            @ToolArg(description = "New duration (seconds)", required = false) Double duration,
            @ToolArg(description = "New lower track or background", required = false) String withTrack) {
        return ToolSupport.call("composition_set", () -> {
            Fps fps = session.current().fps();
            Long atF = ToolSupport.framesOrNull(fps, "at", at);
            Long len = duration == null ? null : ToolSupport.positive(fps, "duration", duration);
            return session.apply("composition_set " + compositionId, p -> CompositionOps.set(p, env, compositionId,
                    params, EffectTools.keys(fps, keyframes), atF, len, withTrack)).message();
        });
    }

    @Tool(name = "composition_remove", description = "Remove a composition. Example: composition_remove(compositionId=\"x14\")")
    public String compositionRemove(@ToolArg(description = "Composition id") String compositionId) {
        return ToolSupport.call("composition_remove", () -> session.apply("composition_remove " + compositionId,
                p -> CompositionOps.remove(p, compositionId)).message());
    }
}
