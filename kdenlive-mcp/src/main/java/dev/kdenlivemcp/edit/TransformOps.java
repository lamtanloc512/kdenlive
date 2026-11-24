package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.assets.ParamCodec;
import dev.kdenlivemcp.edit.Draft.Owner;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Effect;
import dev.kdenlivemcp.model.Project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pan / zoom / rotate / opacity animation ("Ken Burns") expressed as camera moves, written as Kdenlive's
 * Transform effect (qtblend) so it stays editable in Kdenlive's monitor.
 */
public final class TransformOps {

    static final String TRANSFORM = "qtblend";

    /**
     * One camera keyframe.
     *
     * @param offset frames from the start of the target
     * @param zoom 1 = the normal picture, 2 = twice as close, 0.5 = picture shrunk to half size
     * @param x horizontal point of the picture to center on, 0 (left edge) .. 1 (right edge); null = 0.5
     * @param y vertical point to center on, 0 (top) .. 1 (bottom); null = 0.5
     * @param rotation degrees clockwise, or null
     * @param opacity 0..1, or null (= 1)
     */
    public record CameraKey(long offset, double zoom, Double x, Double y, Double rotation, Double opacity, String ease) {
    }

    private TransformOps() {
    }

    /**
     * @param clamp keep the picture covering the frame when zoom >= 1 (no black edges while panning)
     */
    public static EditResult camera(Project p, EffectOps.Env env, String target, List<CameraKey> keys, boolean clamp) {
        if (keys == null || keys.isEmpty()) {
            throw new EditException("give at least one keyframe, e.g. [{t:0, zoom:1}, {t:3, zoom:1.4, x:0.6, y:0.4}]");
        }
        Draft probe = new Draft(p);
        Owner owner = probe.owner(target);
        int w = p.profile().width();
        int h = p.profile().height();
        List<EffectOps.KeySpec> specs = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        boolean rotates = keys.stream().anyMatch(k -> k.rotation() != null && k.rotation() != 0);
        for (CameraKey k : keys) {
            if (!(k.zoom() > 0) || k.zoom() > 50) {
                throw new EditException("zoom must be > 0 and <= 50, not " + k.zoom());
            }
            double fx = k.x() == null ? 0.5 : k.x();
            double fy = k.y() == null ? 0.5 : k.y();
            if (fx < -1 || fx > 2 || fy < -1 || fy > 2) {
                throw new EditException("x and y are fractions of the picture (0..1), got x=" + fx + " y=" + fy);
            }
            double opacity = k.opacity() == null ? 1 : k.opacity();
            double rw = w * k.zoom();
            double rh = h * k.zoom();
            double rx = w / 2.0 - fx * rw;
            double ry = h / 2.0 - fy * rh;
            if (clamp && k.zoom() >= 1) {
                rx = Math.max(w - rw, Math.min(0, rx));
                ry = Math.max(h - rh, Math.min(0, ry));
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("rect", Math.round(rx) + " " + Math.round(ry) + " " + Math.round(rw) + " " + Math.round(rh) + " "
                    + ParamCodec.num(opacity));
            if (rotates) {
                values.put("rotation", k.rotation() == null ? 0 : k.rotation());
            }
            specs.add(new EffectOps.KeySpec(k.offset(), k.ease(), values));
            long shown = k.offset() < 0 ? Math.max(0, owner.length() + k.offset()) : k.offset();
            lines.add(probe.sec(shown) + "s zoom " + ParamCodec.num(k.zoom()) + " at (" + ParamCodec.num(fx) + ", "
                    + ParamCodec.num(fy) + ")" + (k.rotation() == null ? "" : " rot " + ParamCodec.num(k.rotation()))
                    + (k.opacity() == null ? "" : " opacity " + ParamCodec.num(opacity))
                    + (k.ease() == null ? "" : " " + k.ease()));
        }
        Optional<Effect> existing = probe.effects(owner).stream().filter(e -> e.assetId().equals(TRANSFORM)).findFirst();
        EditResult r = existing.isPresent()
                ? EffectOps.set(p, env, existing.get().id(), null, specs, null)
                : EffectOps.add(p, env, owner.key(), TRANSFORM, null, specs, null);
        String head = existing.isPresent() ? "camera move on " + owner.label() + " (updated Transform "
                + existing.get().id() + ")" : "camera move on " + owner.label() + " (Transform " + r.ids().getFirst()
                + ")";
        return new EditResult(r.project(), head + ": " + String.join(" → ", lines), r.notes(), r.ids());
    }
}
