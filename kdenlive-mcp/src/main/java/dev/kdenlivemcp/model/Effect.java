package dev.kdenlivemcp.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An MLT filter as Kdenlive stores it: on a timeline item, a track, or the whole sequence (master).
 * <p>
 * {@code props} holds every {@code <property>} in file order, including {@code mlt_service} and
 * {@code kdenlive_id}, so effects made in Kdenlive are written back exactly. Animated values use MLT's keyframe
 * syntax ({@code 00:00:01.000=0.5;00:00:02.000~=1}); positions are source frames for items (the clip's in point
 * is the first frame) and timeline frames for tracks and the master.
 *
 * @param in  the filter's {@code in} attribute in frames, or null (whole item)
 * @param out the filter's {@code out} attribute in frames, or null
 */
public record Effect(String id, Map<String, String> props, Long in, Long out) {

    public static final String SERVICE = "mlt_service";
    public static final String KDENLIVE_ID = "kdenlive_id";
    public static final String DISABLE = "disable";

    public Effect {
        props = Collections.unmodifiableMap(new LinkedHashMap<>(props));
    }

    public String service() {
        return props.getOrDefault(SERVICE, "");
    }

    /** Kdenlive asset id (falls back to the MLT service, as Kdenlive does). */
    public String assetId() {
        String id = props.get(KDENLIVE_ID);
        return id == null || id.isBlank() ? service() : id;
    }

    public boolean disabled() {
        return "1".equals(props.get(DISABLE));
    }

    public Effect withId(String newId) {
        return new Effect(newId, props, in, out);
    }

    public Effect withProps(Map<String, String> newProps) {
        return new Effect(id, newProps, in, out);
    }

    public Effect withDisabled(boolean disabled) {
        Map<String, String> p = new LinkedHashMap<>(props);
        if (disabled) {
            p.put(DISABLE, "1");
        } else {
            p.remove(DISABLE);
        }
        return withProps(p);
    }
}
