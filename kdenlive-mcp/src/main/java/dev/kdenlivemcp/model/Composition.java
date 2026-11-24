package dev.kdenlivemcp.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Kdenlive composition: an MLT transition in the sequence that combines a track with a lower one (or the black
 * background) between {@code start} and {@code start + length}, e.g. a wipe, a slide, picture-in-picture or a
 * blend mode.
 * <p>
 * {@code props} holds the transition's properties in file order except {@code a_track}/{@code b_track}, which are
 * derived from the track ids when written. Keyframes are relative to the composition start.
 *
 * @param track the upper track (Kdenlive's b_track), e.g. V2
 * @param withTrack the lower track it composites onto (a_track), or null for the black background
 * @param forceTrack Kdenlive's force_track: true when withTrack is not simply the next video track below
 */
public record Composition(String id, String track, String withTrack, boolean forceTrack, long start, long length,
                          Map<String, String> props) implements Transition {

    public Composition {
        if (length <= 0) {
            throw new IllegalArgumentException("composition length must be > 0");
        }
        props = Collections.unmodifiableMap(new LinkedHashMap<>(props));
    }

    public String assetId() {
        String id = props.get(Effect.KDENLIVE_ID);
        return id == null || id.isBlank() ? props.getOrDefault(Effect.SERVICE, "") : id;
    }

    public long end() {
        return start + length;
    }

    @Override
    public List<String> itemIds() {
        return List.of();
    }

    public Composition withTracks(String newTrack, String newWith, boolean newForce) {
        return new Composition(id, newTrack, newWith, newForce, start, length, props);
    }

    public Composition withSpan(long newStart, long newLength) {
        return new Composition(id, track, withTrack, forceTrack, newStart, newLength, props);
    }

    public Composition withProps(Map<String, String> newProps) {
        return new Composition(id, track, withTrack, forceTrack, start, length, newProps);
    }
}
