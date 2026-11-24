package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.assets.AssetCatalog;
import dev.kdenlivemcp.assets.AssetDef;
import dev.kdenlivemcp.assets.AssetParam;
import dev.kdenlivemcp.assets.ParamCodec;
import dev.kdenlivemcp.backend.kdenlive.MltTime;
import dev.kdenlivemcp.edit.Draft.EffectRef;
import dev.kdenlivemcp.edit.Draft.Located;
import dev.kdenlivemcp.edit.Draft.Owner;
import dev.kdenlivemcp.edit.Draft.OwnerKind;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Effect;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TrackKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Generic effects from Kdenlive's own catalog: add, change, keyframe, enable, reorder and remove effects on
 * timeline items, tracks and the master. Values are in Kdenlive's UI units (what its effect panel shows).
 */
public final class EffectOps {

    /**
     * One keyframe from the client.
     *
     * @param offset frames from the start of the owner (item start, or timeline 0 for tracks and the master)
     * @param ease interpolation from this keyframe to the next ({@link ParamCodec#EASES})
     * @param values parameter name -> value at this keyframe
     */
    public record KeySpec(long offset, String ease, Map<String, Object> values) {
    }

    /** What EffectOps needs from the server: the catalog and which MLT services this melt has. */
    public record Env(AssetCatalog catalog, Predicate<String> serviceAvailable, Predicate<String> transitionAvailable,
                      dev.kdenlivemcp.assets.LumaLibrary lumas) {

        public Env(AssetCatalog catalog, Predicate<String> serviceAvailable) {
            this(catalog, serviceAvailable, serviceAvailable, new dev.kdenlivemcp.assets.LumaLibrary(List.of()));
        }
    }

    private EffectOps() {
    }

    // ------------------------------------------------------------------ add

    public static EditResult add(Project p, Env env, String target, String assetId, Map<String, Object> params,
                                 List<KeySpec> keys, Integer position) {
        Draft d = new Draft(p);
        AssetDef def = definition(env, assetId);
        Owner owner = ownerFor(d, d.owner(target), def);
        List<Effect> stack = new ArrayList<>(d.effects(owner));
        if (def.unique() && stack.stream().anyMatch(e -> e.assetId().equals(def.id()))) {
            throw new EditException(def.name() + " (" + def.id() + ") can be used once per item and "
                    + owner.label() + " already has it; change it with effect_set");
        }
        ParamCodec.Context ctx = context(d, owner, env);
        Map<String, String> props = new LinkedHashMap<>();
        Long[] inOut = new Long[2];
        for (AssetParam ap : def.params()) {
            if (ap.name() == null || ap.name().isBlank()) {
                continue;
            }
            String dv = ParamCodec.defaultRaw(ap, ctx);
            if (ap.name().equals("in") || ap.name().equals("out")) {
                // filter in/out (fades): offsets from the owner start
                Double v = ParamCodec.parseDouble(dv);
                if (v != null) {
                    inOut[ap.name().equals("in") ? 0 : 1] = owner.anchor() + Math.round(v);
                }
                continue;
            }
            List<String> names = ap.names();
            if (names.size() > 1) { // multiswitch: one value per property, newline separated
                String[] vals = dv.split("\n", -1);
                for (int k = 0; k < names.size(); k++) {
                    props.put(names.get(k), k < vals.length ? vals[k] : "");
                }
            } else {
                props.put(ap.name(), dv);
            }
        }
        if (inOut[0] != null && inOut[1] != null && inOut[1] < inOut[0]) {
            inOut[1] = inOut[0];
        }
        props.put(Effect.SERVICE, def.service());
        props.put(Effect.KDENLIVE_ID, def.id());
        props.put("kdenlive:collapsed", "0");
        Effect fx = new Effect(d.newId("e"), props, inOut[0], inOut[1]);
        List<String> changes = new ArrayList<>();
        fx = apply(d, fx, def, owner, ctx, params, keys, changes);
        int at = position == null ? stack.size() : position;
        if (at < 0 || at > stack.size()) {
            throw new EditException("position " + at + " is outside the stack of " + owner.label() + " (0.." + stack.size()
                    + ")");
        }
        stack.add(at, fx);
        d.setEffects(owner, stack);
        if (!env.serviceAvailable().test(def.service())) {
            d.notes.add("this melt has no '" + def.service() + "' filter: Kdenlive will apply " + def.name()
                    + ", but previews and renders here skip it (install the full tools)");
        }
        String summary = "added " + fx.id() + " " + def.name() + " (" + def.id() + ") to " + describeOwner(d, owner)
                + (stack.size() > 1 ? " at position " + at + " of " + stack.size() : "")
                + (changes.isEmpty() ? " with default values" : ": " + String.join("; ", changes));
        return new EditResult(d.build(), summary, d.notes, List.of(fx.id()));
    }

    // ------------------------------------------------------------------ set / enable / move / remove

    public static EditResult set(Project p, Env env, String effectId, Map<String, Object> params, List<KeySpec> keys,
                                 Boolean enabled) {
        Draft d = new Draft(p);
        EffectRef ref = d.locateEffect(effectId);
        AssetDef def = env.catalog().effect(ref.effect().assetId()).orElse(null);
        boolean hasValues = (params != null && !params.isEmpty()) || (keys != null && !keys.isEmpty());
        if (def == null && hasValues) {
            throw new EditException(ref.effect().id() + " is '" + ref.effect().assetId()
                    + "', which is not in Kdenlive's catalog, so its parameters are unknown; only enabled can change");
        }
        if (!hasValues && enabled == null) {
            throw new EditException("nothing to change: give params, keyframes and/or enabled");
        }
        List<String> changes = new ArrayList<>();
        Effect fx = ref.effect();
        if (hasValues) {
            fx = apply(d, fx, def, ref.owner(), context(d, ref.owner(), env), params, keys, changes);
        }
        if (enabled != null && enabled == fx.disabled()) {
            fx = fx.withDisabled(!enabled);
            changes.add(enabled ? "enabled" : "disabled");
        }
        List<Effect> stack = new ArrayList<>(d.effects(ref.owner()));
        stack.set(ref.index(), fx);
        d.setEffects(ref.owner(), stack);
        String name = def == null ? fx.assetId() : def.name();
        return new EditResult(d.build(), "updated " + fx.id() + " " + name + " on " + describeOwner(d, ref.owner()) + ": "
                + (changes.isEmpty() ? "no change" : String.join("; ", changes)), d.notes, List.of(fx.id()));
    }

    public static EditResult remove(Project p, String effectId) {
        Draft d = new Draft(p);
        EffectRef ref = d.locateEffect(effectId);
        List<Effect> stack = new ArrayList<>(d.effects(ref.owner()));
        stack.remove(ref.index());
        d.setEffects(ref.owner(), stack);
        return new EditResult(d.build(), "removed " + ref.effect().id() + " (" + ref.effect().assetId() + ") from "
                + describeOwner(d, ref.owner()), d.notes, List.of(ref.effect().id()));
    }

    public static EditResult move(Project p, String effectId, int index) {
        Draft d = new Draft(p);
        EffectRef ref = d.locateEffect(effectId);
        List<Effect> stack = new ArrayList<>(d.effects(ref.owner()));
        if (index < 0 || index >= stack.size()) {
            throw new EditException("index " + index + " is outside the stack of " + ref.owner().label() + " (0.."
                    + (stack.size() - 1) + ")");
        }
        stack.remove(ref.index());
        stack.add(index, ref.effect());
        d.setEffects(ref.owner(), stack);
        return new EditResult(d.build(), "moved " + ref.effect().id() + " to position " + index + " on "
                + describeOwner(d, ref.owner()) + "; order now: "
                + String.join(", ", stack.stream().map(e -> e.id() + " " + e.assetId()).toList()), d.notes,
                List.of(ref.effect().id()));
    }

    // ------------------------------------------------------------------ shared

    /** Applies static values, then keyframes, to an effect. */
    static Effect apply(Draft d, Effect fx, AssetDef def, Owner owner, ParamCodec.Context ctx, Map<String, Object> params,
                        List<KeySpec> keys, List<String> changes) {
        return fx.withProps(applyValues(d.fps(), fx.props(), def, owner.label(), owner.start(), owner.anchor(),
                owner.length(), ctx, params, keys, changes));
    }

    /**
     * Applies client values to an asset's properties (effects and compositions).
     *
     * @param start timeline frame of the owner start (for messages)
     * @param anchor owner start in the asset's keyframe time base
     */
    static Map<String, String> applyValues(Fps fps, Map<String, String> original, AssetDef def, String label, long start,
                                           long anchor, long length, ParamCodec.Context ctx, Map<String, Object> params,
                                           List<KeySpec> keys, List<String> changes) {
        Map<String, String> props = new LinkedHashMap<>(original);
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                AssetParam ap = param(def, e.getKey());
                String raw = ParamCodec.toRaw(ap, e.getValue(), ctx);
                if (ap.animated() && ap.kind() != AssetParam.Kind.RAW) {
                    raw = ctx.clock().apply(anchor) + "=" + raw;
                }
                putParam(props, ap, raw);
                changes.add(ap.name() + "=" + ParamCodec.toUi(ap, ParamCodec.toRaw(ap, e.getValue(), ctx)));
            }
        }
        if (keys != null && !keys.isEmpty()) {
            Map<String, List<ParamCodec.Key>> perParam = new LinkedHashMap<>();
            for (KeySpec k : keys) {
                if (k.values() == null || k.values().isEmpty()) {
                    throw new EditException("keyframe at " + fps.sec(k.offset()) + "s has no values");
                }
                long off = k.offset() < 0 ? length + k.offset() : k.offset();
                if (off < 0 || off > length) {
                    throw new EditException("keyframe at " + fps.sec(k.offset()) + "s is outside " + label
                            + " (0.." + fps.sec(length) + "s from its start)");
                }
                off = Math.min(off, length - 1);
                String ease = k.ease() == null || k.ease().isBlank() ? "linear" : k.ease();
                ParamCodec.easeChar(ease); // validates
                for (Map.Entry<String, Object> e : k.values().entrySet()) {
                    AssetParam ap = param(def, e.getKey());
                    if (!ap.animated()) {
                        throw new EditException(ap.name() + " of " + def.id() + " cannot be keyframed; set it with params"
                                + animatedHint(def));
                    }
                    perParam.computeIfAbsent(ap.name(), n -> new ArrayList<>())
                            .add(new ParamCodec.Key(anchor + off, ease, ParamCodec.toRaw(ap, e.getValue(), ctx)));
                }
            }
            for (Map.Entry<String, List<ParamCodec.Key>> e : perParam.entrySet()) {
                AssetParam ap = param(def, e.getKey());
                List<ParamCodec.Key> list = dedupe(e.getValue());
                putParam(props, ap, ParamCodec.encode(list, ctx.clock()));
                changes.add(ap.name() + " keyframed at " + String.join(", ", list.stream()
                        .map(k -> fps.sec(start + k.frame() - anchor) + "s").toList()));
            }
            alignKeyframes(props, def, fps);
        }
        return props;
    }

    /**
     * Kdenlive keeps one set of keyframe positions per effect (KeyframeModelList::checkConsistency): constant
     * animated parameters get a key at every position used by the keyframed ones, so Kdenlive does not have to
     * repair the effect on load.
     */
    static void alignKeyframes(Map<String, String> props, AssetDef def, Fps fps) {
        java.util.function.ToLongFunction<String> time = s -> MltTime.parse(s, fps);
        Map<String, List<ParamCodec.Key>> anim = new LinkedHashMap<>();
        for (AssetParam ap : def.params()) {
            String raw = props.get(ap.name());
            if (ap.userSettable() && ap.animated() && ap.kind() != AssetParam.Kind.RAW && ap.names().size() == 1
                    && raw != null && raw.contains("=")) {
                List<ParamCodec.Key> keys = ParamCodec.decode(raw, time);
                if (keys.size() > 1 && keys.stream().map(ParamCodec.Key::raw).distinct().count() == 1) {
                    keys = List.of(keys.getFirst()); // the same value everywhere: a constant
                }
                anim.put(ap.name(), keys);
            }
        }
        java.util.TreeMap<Long, String> positions = new java.util.TreeMap<>();
        anim.values().stream().filter(l -> l.size() > 1).flatMap(List::stream)
                .forEach(k -> positions.putIfAbsent(k.frame(), k.ease()));
        java.util.function.LongFunction<String> clock = f -> MltTime.clock(f, fps);
        if (positions.size() <= 1) {
            anim.forEach((name, keys) -> {
                if (keys.size() == 1 && ParamCodec.decode(props.get(name), time).size() > 1) {
                    props.put(name, ParamCodec.encode(keys, clock)); // collapse an old spread
                }
            });
            return;
        }
        anim.forEach((name, keys) -> {
            if (keys.size() == 1) {
                String v = keys.getFirst().raw();
                List<ParamCodec.Key> spread = positions.entrySet().stream()
                        .map(e -> new ParamCodec.Key(e.getKey(), e.getValue(), v)).toList();
                props.put(name, ParamCodec.encode(spread, clock));
            }
        });
    }

    private static void putParam(Map<String, String> props, AssetParam ap, String raw) {
        List<String> names = ap.names();
        if (names.size() > 1) {
            String[] vals = raw.split("\n", -1);
            for (int k = 0; k < names.size(); k++) {
                props.put(names.get(k), k < vals.length ? vals[k] : "");
            }
        } else {
            props.put(ap.name(), raw);
        }
    }

    private static List<ParamCodec.Key> dedupe(List<ParamCodec.Key> keys) {
        Map<Long, ParamCodec.Key> byFrame = new LinkedHashMap<>();
        keys.forEach(k -> byFrame.put(k.frame(), k));
        return new ArrayList<>(byFrame.values());
    }

    static AssetParam param(AssetDef def, String name) {
        if (name == null) {
            throw new EditException("parameter name missing");
        }
        Optional<AssetParam> exact = def.params().stream().filter(AssetParam::userSettable)
                .filter(p -> p.name().equals(name)).findFirst();
        if (exact.isPresent()) {
            return exact.get();
        }
        Optional<AssetParam> byTitle = def.params().stream().filter(AssetParam::userSettable)
                .filter(p -> p.title().equalsIgnoreCase(name) || p.name().equalsIgnoreCase(name)
                        || p.name().equalsIgnoreCase("av." + name))
                .findFirst();
        return byTitle.orElseThrow(() -> new EditException(def.id() + " has no parameter '" + name + "'; parameters: "
                + String.join(", ", def.params().stream().filter(AssetParam::userSettable)
                .map(p -> p.name() + (p.title().equals(p.name()) ? "" : " (" + p.title() + ")")).toList())));
    }

    private static String animatedHint(AssetDef def) {
        List<String> anim = def.params().stream().filter(AssetParam::userSettable).filter(AssetParam::animated)
                .map(AssetParam::name).toList();
        return anim.isEmpty() ? " (" + def.id() + " has no keyframable parameters)"
                : "; keyframable: " + String.join(", ", anim);
    }

    static AssetDef definition(Env env, String assetId) {
        if (assetId == null || assetId.isBlank()) {
            throw new EditException("effect id is required; find one with effect_catalog(query=...)");
        }
        return env.catalog().effect(assetId.trim()).orElseThrow(() -> new EditException("unknown effect '" + assetId
                + "'; close matches: " + String.join(", ", env.catalog().suggestEffects(assetId, 6))
                + ". Search with effect_catalog(query=...)"));
    }

    /** Audio effects go to the audio part of an A/V clip, video effects to its video part. */
    private static Owner ownerFor(Draft d, Owner owner, AssetDef def) {
        if (owner.kind() == OwnerKind.MASTER) {
            return owner;
        }
        TrackKind wanted = def.audio() ? TrackKind.AUDIO : TrackKind.VIDEO;
        if (owner.trackKind() == wanted) {
            return owner;
        }
        if (owner.kind() == OwnerKind.ITEM) {
            Located loc = d.locate(owner.key());
            Optional<Located> partner = d.partner(loc.item());
            if (partner.isPresent() && partner.get().track().kind() == wanted) {
                d.notes.add(def.name() + " is " + (def.audio() ? "an audio" : "a video") + " effect: applied to "
                        + partner.get().item().id() + ", the " + (def.audio() ? "audio" : "video") + " part of "
                        + owner.key());
                return d.ownerOf(partner.get());
            }
        }
        throw new EditException(def.name() + " (" + def.id() + ") is " + (def.audio() ? "an audio" : "a video")
                + " effect but " + owner.label() + " is on a " + owner.trackKind().name().toLowerCase(Locale.ROOT)
                + " track; use a " + wanted.name().toLowerCase(Locale.ROOT) + " item/track or master");
    }

    static ParamCodec.Context context(Draft d, Owner o, Env env) {
        Fps fps = d.fps();
        return new ParamCodec.Context(d.base.profile().width(), d.base.profile().height(), o.anchor(), o.length(),
                o.contentWidth(), o.contentHeight(), f -> MltTime.clock(f, fps), env.lumas());
    }

    static String describeOwner(Draft d, Owner o) {
        return switch (o.kind()) {
            case ITEM -> d.describe(d.locate(o.key()).item());
            case TRACK -> "track " + o.key();
            case MASTER -> "the master (whole timeline)";
        };
    }
}
