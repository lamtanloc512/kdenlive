package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Extras;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TimelineItem;
import dev.kdenlivemcp.model.Track;
import dev.kdenlivemcp.model.TrackKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adding, flagging and removing tracks. Track ids are positional as in Kdenlive (V1 is the lowest video track,
 * A1 the audio track nearest the video), so new video tracks go on top and new audio tracks at the bottom, and
 * removing a track renumbers the ones after it. Item ids never change.
 */
public final class TrackOps {

    static final String NAME_PROP = "kdenlive:track_name";

    private TrackOps() {
    }

    public static EditResult add(Project p, String kind, String name) {
        Draft d = new Draft(p);
        TrackKind k = kind(kind);
        long count = d.tracks.stream().filter(t -> t.kind() == k).count();
        String id = k.prefix() + (count + 1);
        Extras extras = name == null || name.isBlank() ? Extras.NONE : new Extras(Map.of(NAME_PROP, name.trim()), List.of());
        Track t = new Track(id, k, List.of(), false, false, false, extras, List.of());
        if (k == TrackKind.VIDEO) {
            d.tracks.addFirst(t); // top of the stack
        } else {
            d.tracks.addLast(t);
        }
        d.items.put(id, new ArrayList<>());
        return new EditResult(d.build(), "added " + k.name().toLowerCase(Locale.ROOT) + " track " + id
                + (name == null || name.isBlank() ? "" : " \"" + name.trim() + "\"")
                + (k == TrackKind.VIDEO ? " on top of the other video tracks (it covers the tracks below)"
                : " below the other audio tracks") + "; tracks: " + d.trackIds(), d.notes, List.of(id));
    }

    public static EditResult set(Project p, String trackId, Boolean muted, Boolean hidden, Boolean locked, String name) {
        Draft d = new Draft(p);
        Track t = d.trackShell(trackId);
        if (muted == null && hidden == null && locked == null && name == null) {
            throw new EditException("nothing to change: give muted, hidden, locked and/or name");
        }
        if (hidden != null && t.kind() == TrackKind.AUDIO) {
            throw new EditException(t.id() + " is an audio track; use muted (hidden is for video tracks)");
        }
        if (muted != null && t.kind() == TrackKind.VIDEO && muted) {
            d.notes.add("video tracks carry no sound here: muted only affects audio tracks in this model");
        }
        Track n = t.withFlags(muted == null ? t.muted() : muted, hidden == null ? t.hidden() : hidden,
                locked == null ? t.locked() : locked);
        if (name != null) {
            Map<String, String> props = new LinkedHashMap<>(n.extras().properties());
            if (name.isBlank()) {
                props.remove(NAME_PROP);
            } else {
                props.put(NAME_PROP, name.trim());
            }
            n = new Track(n.id(), n.kind(), n.items(), n.muted(), n.hidden(), n.locked(),
                    new Extras(props, n.extras().xml()), n.effects());
        }
        d.tracks.set(d.tracks.indexOf(t), n);
        List<String> what = new ArrayList<>();
        if (muted != null) {
            what.add(muted ? "muted" : "unmuted");
        }
        if (hidden != null) {
            what.add(hidden ? "hidden" : "visible");
        }
        if (locked != null) {
            what.add(locked ? "locked" : "unlocked");
        }
        if (name != null) {
            what.add(name.isBlank() ? "name cleared" : "named \"" + name.trim() + "\"");
        }
        return new EditResult(d.build(), "track " + t.id() + ": " + String.join(", ", what), d.notes, List.of(t.id()));
    }

    public static EditResult remove(Project p, String trackId) {
        Draft d = new Draft(p);
        Track t = d.trackShell(trackId);
        long sameKind = d.tracks.stream().filter(x -> x.kind() == t.kind()).count();
        if (sameKind <= 1) {
            throw new EditException("cannot remove " + t.id() + ": it is the only " + t.kind().name().toLowerCase(Locale.ROOT)
                    + " track");
        }
        List<TimelineItem> items = d.items.get(t.id());
        List<String> removed = new ArrayList<>();
        for (TimelineItem i : List.copyOf(items)) {
            d.partner(i).ifPresent(pl -> {
                d.remove(pl.item().id());
                removed.add(pl.item().id());
            });
            removed.add(i.id());
        }
        d.tracks.remove(t);
        d.items.remove(t.id());
        TransitionOps.prune(d);
        Map<String, String> renames = renumber(d);
        String summary = "removed track " + t.id()
                + (removed.isEmpty() ? "" : " and its items " + String.join(", ", removed))
                + (renames.isEmpty() ? "" : "; renamed " + String.join(", ", renames.entrySet().stream()
                .map(e -> e.getKey() + "→" + e.getValue()).toList()))
                + "; tracks: " + d.trackIds();
        return new EditResult(d.build(), summary, d.notes, List.of(t.id()));
    }

    /** Re-applies positional ids: video V(n)..V1 top to bottom, audio A1..A(n). Returns old -> new. */
    private static Map<String, String> renumber(Draft d) {
        Map<String, String> renames = new LinkedHashMap<>();
        long videos = d.tracks.stream().filter(t -> t.kind() == TrackKind.VIDEO).count();
        int v = (int) videos;
        int a = 1;
        Map<String, List<TimelineItem>> newItems = new LinkedHashMap<>();
        for (int k = 0; k < d.tracks.size(); k++) {
            Track t = d.tracks.get(k);
            String id = t.kind() == TrackKind.VIDEO ? "V" + v-- : "A" + a++;
            if (!id.equals(t.id())) {
                renames.put(t.id(), id);
            }
            newItems.put(id, d.items.get(t.id()));
            d.tracks.set(k, t.withId(id));
        }
        d.items.clear();
        d.items.putAll(newItems);
        return renames;
    }

    static TrackKind kind(String kind) {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "video", "v" -> TrackKind.VIDEO;
            case "audio", "a" -> TrackKind.AUDIO;
            default -> throw new EditException("kind must be video or audio, not '" + kind + "'");
        };
    }
}
