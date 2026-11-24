package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.edit.Draft.Located;
import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Effect;
import dev.kdenlivemcp.model.MediaClip;
import dev.kdenlivemcp.model.Project;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clip speed (Kdenlive's "Change Speed"): slow motion and fast motion. The clip keeps its start and the same part of
 * the source, so its length on the timeline changes; its linked audio/video part changes with it.
 */
public final class SpeedOps {

    public static final double MIN = 0.01;
    public static final double MAX = 100;
    private static final Pattern KEY = Pattern.compile("(\\d\\d):(\\d\\d):(\\d\\d)\\.(\\d{3})([^0-9=;]?)=");

    private SpeedOps() {
    }

    public static EditResult set(Project p, String itemId, double speed, boolean keepPitch) {
        if (!(speed >= MIN && speed <= MAX)) {
            throw new EditException("speed must be between " + MIN + " and " + MAX + " (1 = normal, 0.5 = half speed, "
                    + "2 = double); reverse playback is not supported");
        }
        Draft d = new Draft(p);
        Located loc = d.locate(itemId);
        if (!(loc.item() instanceof ClipItem c)) {
            throw new EditException(itemId + " is a title; speed applies to video/audio clips");
        }
        MediaClip m = d.media(c.mediaId());
        if (m.info().stillImage()) {
            throw new EditException(c.id() + " is a still image; change its duration with clip_trim instead");
        }
        Optional<Located> partner = d.partner(c);
        double ratio = c.speed() / speed;
        long newIn = Math.round(c.in() * ratio);
        long newLen = Math.max(1, Math.round(c.length() * ratio));
        long available = (long) Math.floor(m.lengthFrames(d.fps()) / speed);
        if (newIn + newLen > available) {
            newLen = Math.max(1, available - newIn);
        }
        Set<String> ignore = new HashSet<>(Set.of(c.id()));
        partner.ifPresent(pl -> ignore.add(pl.item().id()));
        d.requireFree(loc.track().id(), c.start(), newLen, ignore);
        final long len = newLen;
        partner.ifPresent(pl -> d.requireFree(pl.track().id(), pl.item().start(), len, ignore));
        d.replace(retime(c, speed, keepPitch, newIn, newLen, ratio));
        partner.ifPresent(pl -> d.replace(retime((ClipItem) pl.item(), speed, keepPitch, newIn, len, ratio)));
        TransitionOps.prune(d);
        String summary = "speed of " + d.describe(c) + " set to " + dev.kdenlivemcp.assets.ParamCodec.num(speed * 100)
                + "%" + (keepPitch ? " (pitch kept)" : "") + ": now " + d.sec(c.start()) + "–" + d.sec(c.start() + len)
                + " (" + d.sec(len) + "s, was " + d.sec(c.length()) + "s)"
                + partner.map(pl -> "; linked " + pl.item().id() + " too").orElse("");
        return new EditResult(d.build(), summary, d.notes, List.of(c.id()));
    }

    private static ClipItem retime(ClipItem c, double speed, boolean keepPitch, long newIn, long newLen, double ratio) {
        List<Effect> effects = new ArrayList<>();
        for (Effect e : c.effects()) {
            Map<String, String> props = new LinkedHashMap<>();
            e.props().forEach((k, v) -> props.put(k, rescaleKeys(v, ratio)));
            effects.add(new Effect(e.id(), props, e.in() == null ? null : Math.round(e.in() * ratio),
                    e.out() == null ? null : Math.round(e.out() * ratio)));
        }
        ClipItem n = c.withSpeed(speed, keepPitch, newIn, newLen, effects);
        var a = n.audio();
        long fi = Math.min(a.fadeIn(), newLen);
        long fo = Math.min(a.fadeOut(), newLen - fi);
        return n.withAudio(new dev.kdenlivemcp.model.AudioSettings(a.volumeDb(), fi, fo));
    }

    /** Effect keyframes sit on retimed frames, so they move with the speed change ("00:00:02.000=1" style values). */
    static String rescaleKeys(String value, double ratio) {
        if (value == null || !value.contains("=") || !KEY.matcher(value).find()) {
            return value;
        }
        Matcher m = KEY.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            long ms = ((Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3)))
                    * 1000 + Long.parseLong(m.group(4));
            long scaled = Math.round(ms * ratio);
            m.appendReplacement(sb, String.format(java.util.Locale.ROOT, "%02d:%02d:%02d.%03d%s=", scaled / 3_600_000,
                    (scaled / 60_000) % 60, (scaled / 1000) % 60, scaled % 1000, Matcher.quoteReplacement(m.group(5))));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
