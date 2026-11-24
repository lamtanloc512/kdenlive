package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.assets.AssetDef;
import dev.kdenlivemcp.assets.AssetParam;
import dev.kdenlivemcp.assets.ParamCodec;
import dev.kdenlivemcp.backend.kdenlive.MltTime;
import dev.kdenlivemcp.model.Composition;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Effect;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.Track;
import dev.kdenlivemcp.model.TrackKind;
import dev.kdenlivemcp.model.Transition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Kdenlive compositions: a track combined with a lower track (or the black background) over a time span, using any
 * transition from Kdenlive's catalog (wipes, slides, Composite and transform, blend modes, ...).
 */
public final class CompositionOps {

    private CompositionOps() {
    }

    /**
     * @param withTrack lower track id, "background" for the black background, or null for the next video track below
     */
    public static EditResult add(Project p, EffectOps.Env env, String assetId, String trackId, String withTrack, long at,
                                 long length, Map<String, Object> params, List<EffectOps.KeySpec> keys) {
        Draft d = new Draft(p);
        AssetDef def = definition(env, assetId);
        Track track = d.trackShell(trackId);
        TrackKind wanted = def.audio() ? TrackKind.AUDIO : TrackKind.VIDEO;
        if (track.kind() != wanted) {
            throw new EditException(def.name() + " is a" + (def.audio() ? "n audio" : " video") + " composition; "
                    + track.id() + " is " + track.kind().name().toLowerCase(Locale.ROOT) + ". Use a "
                    + wanted.name().toLowerCase(Locale.ROOT) + " track: " + TimelineOps.tracksOf(p, wanted));
        }
        if (at < 0 || length <= 0) {
            throw new EditException("composition needs at >= 0 and a positive duration");
        }
        String below = defaultBelow(d, track);
        String with = resolveWith(d, track, withTrack, below);
        requireFree(d, track.id(), at, length, null);
        ParamCodec.Context ctx = context(d, env, length);
        Map<String, String> props = new LinkedHashMap<>();
        for (AssetParam ap : def.params()) {
            if (ap.name() == null || ap.name().isBlank()) {
                continue;
            }
            String dv = ParamCodec.defaultRaw(ap, ctx);
            List<String> names = ap.names();
            if (names.size() > 1) {
                String[] vals = dv.split("\n", -1);
                for (int k = 0; k < names.size(); k++) {
                    props.put(names.get(k), k < vals.length ? vals[k] : "");
                }
            } else {
                props.put(ap.name(), dv);
            }
        }
        props.put(Effect.SERVICE, def.service());
        props.put(Effect.KDENLIVE_ID, def.id());
        List<String> changes = new ArrayList<>();
        props = EffectOps.applyValues(d.fps(), props, def, "the composition", at, 0, length, ctx, params, keys, changes);
        Composition c = new Composition(d.newId("x"), track.id(), with, !java.util.Objects.equals(with, below), at,
                length, props);
        d.transitions.add(c);
        if (!env.transitionAvailable().test(def.service())) {
            d.notes.add("this melt has no '" + def.service() + "' transition: Kdenlive will apply " + def.name()
                    + ", but previews and renders here skip it");
        }
        String summary = "added composition " + c.id() + " " + def.name() + " (" + def.id() + ") on " + track.id()
                + " over " + (with == null ? "the background" : with) + ", " + d.sec(at) + "–" + d.sec(at + length)
                + (changes.isEmpty() ? "" : ": " + String.join("; ", changes));
        return new EditResult(d.build(), summary, d.notes, List.of(c.id()));
    }

    public static EditResult set(Project p, EffectOps.Env env, String id, Map<String, Object> params,
                                 List<EffectOps.KeySpec> keys, Long at, Long length, String withTrack) {
        Draft d = new Draft(p);
        Composition c = locate(d, id);
        AssetDef def = env.catalog().transition(c.assetId()).orElse(null);
        boolean values = (params != null && !params.isEmpty()) || (keys != null && !keys.isEmpty());
        if (!values && at == null && length == null && withTrack == null) {
            throw new EditException("nothing to change: give params, keyframes, at, duration and/or withTrack");
        }
        if (def == null && values) {
            throw new EditException(c.id() + " is '" + c.assetId() + "', which is not in Kdenlive's catalog");
        }
        List<String> changes = new ArrayList<>();
        Composition n = c;
        if (at != null || length != null) {
            long s = at == null ? c.start() : at;
            long l = length == null ? c.length() : length;
            if (s < 0 || l <= 0) {
                throw new EditException("composition needs at >= 0 and a positive duration");
            }
            requireFree(d, c.track(), s, l, c.id());
            n = n.withSpan(s, l);
            changes.add("span " + d.sec(s) + "–" + d.sec(s + l));
        }
        if (withTrack != null) {
            Track track = d.trackShell(c.track());
            String below = defaultBelow(d, track);
            String with = resolveWith(d, track, withTrack, below);
            n = n.withTracks(c.track(), with, !java.util.Objects.equals(with, below));
            changes.add("over " + (with == null ? "the background" : with));
        }
        if (values) {
            n = n.withProps(EffectOps.applyValues(d.fps(), n.props(), def, "the composition", n.start(), 0, n.length(),
                    context(d, env, n.length()), params, keys, changes));
        }
        d.transitions.set(d.transitions.indexOf(c), n);
        return new EditResult(d.build(), "updated composition " + c.id() + ": " + String.join("; ", changes), d.notes,
                List.of(c.id()));
    }

    public static EditResult remove(Project p, String id) {
        Draft d = new Draft(p);
        Composition c = locate(d, id);
        d.transitions.remove(c);
        return new EditResult(d.build(), "removed composition " + c.id() + " (" + c.assetId() + ") from " + c.track(),
                d.notes, List.of(c.id()));
    }

    // ------------------------------------------------------------------ helpers

    static AssetDef definition(EffectOps.Env env, String assetId) {
        if (assetId == null || assetId.isBlank()) {
            throw new EditException("composition type is required; find one with composition_catalog()");
        }
        return env.catalog().transition(assetId.trim()).orElseThrow(() -> new EditException("unknown composition '"
                + assetId + "'; close matches: " + String.join(", ", env.catalog().suggestTransitions(assetId, 6))
                + ". List them with composition_catalog()"));
    }

    private static Composition locate(Draft d, String id) {
        String key = id == null ? "" : id.trim();
        Optional<Composition> c = d.transitions.stream().filter(t -> t instanceof Composition && t.id().equals(key))
                .map(t -> (Composition) t).findFirst();
        return c.orElseThrow(() -> new EditException("unknown composition '" + id + "'; compositions: "
                + String.join(", ", d.transitions.stream().filter(t -> t instanceof Composition).map(Transition::id)
                .toList())));
    }

    /** The next track of the same kind below in Kdenlive's stack (null = background). */
    static String defaultBelow(Draft d, Track track) {
        int i = d.tracks.indexOf(track);
        if (track.kind() == TrackKind.VIDEO) {
            for (int k = i + 1; k < d.tracks.size(); k++) {
                if (d.tracks.get(k).kind() == TrackKind.VIDEO) {
                    return d.tracks.get(k).id();
                }
            }
            return null;
        }
        for (int k = i - 1; k >= 0; k--) { // audio tracks are numbered downwards; "below" in the mix is above
            if (d.tracks.get(k).kind() == TrackKind.AUDIO) {
                return d.tracks.get(k).id();
            }
        }
        return null;
    }

    private static String resolveWith(Draft d, Track track, String withTrack, String below) {
        if (withTrack == null || withTrack.isBlank()) {
            return below;
        }
        String w = withTrack.trim();
        if (w.equalsIgnoreCase("background") || w.equalsIgnoreCase("black") || w.equalsIgnoreCase("none")) {
            return null;
        }
        Track lower = d.trackShell(w);
        if (lower.kind() != track.kind() || lower.id().equals(track.id())) {
            throw new EditException("withTrack must be another " + track.kind().name().toLowerCase(Locale.ROOT)
                    + " track below " + track.id() + " (or background)");
        }
        if (track.kind() == TrackKind.VIDEO && d.tracks.indexOf(lower) < d.tracks.indexOf(track)) {
            throw new EditException(lower.id() + " is above " + track.id() + "; a composition combines a track with one "
                    + "below it. Put the composition on " + lower.id() + " with withTrack=" + track.id() + " instead");
        }
        return lower.id();
    }

    private static void requireFree(Draft d, String track, long at, long length, String ignoreId) {
        for (Transition t : d.transitions) {
            if (t instanceof Composition c && c.track().equals(track) && !c.id().equals(ignoreId)
                    && c.start() < at + length && at < c.end()) {
                throw new EditException(track + " already has composition " + c.id() + " (" + c.assetId() + ") from "
                        + d.sec(c.start()) + "–" + d.sec(c.end()) + "; compositions on one track cannot overlap");
            }
        }
    }

    private static ParamCodec.Context context(Draft d, EffectOps.Env env, long length) {
        Fps fps = d.fps();
        Project p = d.base;
        return new ParamCodec.Context(p.profile().width(), p.profile().height(), 0, length, p.profile().width(),
                p.profile().height(), f -> MltTime.clock(f, fps), env.lumas());
    }
}
