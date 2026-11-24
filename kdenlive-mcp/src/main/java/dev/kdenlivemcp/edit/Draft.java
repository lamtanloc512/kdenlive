package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Effect;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.MediaClip;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TimelineItem;
import dev.kdenlivemcp.model.TitleItem;
import dev.kdenlivemcp.model.Track;
import dev.kdenlivemcp.model.TrackKind;
import dev.kdenlivemcp.model.Transition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Mutable working copy of a {@link Project} used inside one edit operation. */
final class Draft {

    final Project base;
    final List<MediaClip> bin;
    final Map<String, List<TimelineItem>> items = new LinkedHashMap<>();
    final List<Transition> transitions;
    final List<String> notes = new ArrayList<>();
    /** Track shells (items live in {@link #items}); mutable so tracks can be added, renamed and flagged. */
    final List<Track> tracks;
    final List<Effect> masterEffects;
    private long nextId;

    record Located(Track track, TimelineItem item) {
    }

    Draft(Project p) {
        this.base = p;
        this.bin = new ArrayList<>(p.bin());
        for (Track t : p.tracks()) {
            items.put(t.id(), new ArrayList<>(t.items()));
        }
        this.transitions = new ArrayList<>(p.transitions());
        this.tracks = new ArrayList<>(p.tracks());
        this.masterEffects = new ArrayList<>(p.masterEffects());
        this.nextId = p.nextId();
    }

    Fps fps() {
        return base.fps();
    }

    String sec(long frames) {
        return fps().sec(frames);
    }

    String newId(String prefix) {
        return prefix + (nextId++);
    }

    // ---------------------------------------------------------------- effect owners

    enum OwnerKind { ITEM, TRACK, MASTER }

    /**
     * Something that has an effect stack.
     *
     * @param key item id, track id, or {@link Project#MASTER}
     * @param trackKind kind of the item's/track's track; null for the master
     * @param start timeline frame where the owner starts
     * @param anchor frame of the owner's start in the effect time base (source frames for items, else timeline)
     */
    record Owner(OwnerKind kind, String key, TrackKind trackKind, long start, long length, long anchor,
                 int contentWidth, int contentHeight) {

        String label() {
            return switch (kind) {
                case ITEM -> key;
                case TRACK -> "track " + key;
                case MASTER -> "master";
            };
        }
    }

    record EffectRef(Owner owner, int index, Effect effect) {
    }

    /** Resolves an item id, a track id (V1, A2) or "master". */
    Owner owner(String target) {
        if (target == null || target.isBlank()) {
            throw new EditException("target is required: an item id (c4, t7), a track id (" + trackIds()
                    + ") or master");
        }
        String t = target.trim();
        long duration = Math.max(1, build().duration());
        int w = base.profile().width();
        int h = base.profile().height();
        if (t.equalsIgnoreCase(Project.MASTER)) {
            return new Owner(OwnerKind.MASTER, Project.MASTER, null, 0, duration, 0, w, h);
        }
        Optional<Track> track = tracks.stream().filter(x -> x.id().equalsIgnoreCase(t)).findFirst();
        if (track.isPresent()) {
            return new Owner(OwnerKind.TRACK, track.get().id(), track.get().kind(), 0, duration, 0, w, h);
        }
        Optional<Located> loc = find(t);
        if (loc.isEmpty()) {
            throw new EditException("unknown target '" + target + "': use an item id"
                    + (allItemIds().isEmpty() ? "" : " (" + String.join(", ", allItemIds()) + ")")
                    + ", a track id (" + trackIds() + ") or master");
        }
        return ownerOf(loc.get());
    }

    Owner ownerOf(Located loc) {
        TimelineItem i = loc.item();
        int cw = base.profile().width();
        int ch = base.profile().height();
        if (i instanceof ClipItem c) {
            MediaClip m = media(c.mediaId());
            if (m.info().displayWidth() > 0 && m.info().displayHeight() > 0) {
                cw = m.info().displayWidth();
                ch = m.info().displayHeight();
            }
        }
        return new Owner(OwnerKind.ITEM, i.id(), loc.track().kind(), i.start(), i.length(), i.sourceIn(), cw, ch);
    }

    String trackIds() {
        return tracks.stream().map(Track::id).collect(Collectors.joining(", "));
    }

    List<Effect> effects(Owner o) {
        return switch (o.kind()) {
            case MASTER -> masterEffects;
            case TRACK -> trackShell(o.key()).effects();
            case ITEM -> locate(o.key()).item().effects();
        };
    }

    void setEffects(Owner o, List<Effect> list) {
        switch (o.kind()) {
            case MASTER -> {
                masterEffects.clear();
                masterEffects.addAll(list);
            }
            case TRACK -> {
                Track t = trackShell(o.key());
                tracks.set(tracks.indexOf(t), t.withEffects(list));
            }
            case ITEM -> replace(locate(o.key()).item().withEffects(list));
        }
    }

    Track trackShell(String id) {
        return tracks.stream().filter(t -> t.id().equalsIgnoreCase(id)).findFirst()
                .orElseThrow(() -> new EditException("track '" + id + "' does not exist; tracks: " + trackList()));
    }

    /** Finds an effect by id anywhere in the project. */
    EffectRef locateEffect(String effectId) {
        String id = effectId == null ? "" : effectId.trim();
        List<Owner> owners = new ArrayList<>();
        owners.add(owner(Project.MASTER));
        for (Track t : tracks) {
            owners.add(owner(t.id()));
            for (TimelineItem i : items.get(t.id())) {
                owners.add(ownerOf(new Located(t, i)));
            }
        }
        List<String> all = new ArrayList<>();
        for (Owner o : owners) {
            List<Effect> list = effects(o);
            for (int k = 0; k < list.size(); k++) {
                if (list.get(k).id().equals(id)) {
                    return new EffectRef(o, k, list.get(k));
                }
                all.add(list.get(k).id());
            }
        }
        throw new EditException("unknown effect id '" + effectId + "'"
                + (all.isEmpty() ? "; the project has no effects yet" : "; effects: " + String.join(", ", all)));
    }

    /** Copies of effects with fresh ids (for split parts). */
    List<Effect> copyEffects(List<Effect> effects) {
        return effects.stream().map(e -> e.withId(newId("e"))).toList();
    }

    // ---------------------------------------------------------------- lookups

    Track track(String id) {
        if (id == null || id.isBlank()) {
            throw new EditException("track is required; tracks: " + trackList());
        }
        return tracks.stream().filter(t -> t.id().equalsIgnoreCase(id.trim())).findFirst()
                .orElseThrow(() -> new EditException("track '" + id + "' does not exist; tracks: " + trackList()));
    }

    String trackList() {
        return tracks.stream()
                .map(t -> t.id() + " (" + t.kind().name().toLowerCase() + ")")
                .collect(Collectors.joining(", "));
    }

    MediaClip media(String id) {
        return bin.stream().filter(m -> m.id().equals(id)).findFirst()
                .orElseThrow(() -> new EditException("unknown clip id '" + id + "'; bin: "
                        + (bin.isEmpty() ? "empty (use media_import first)"
                        : bin.stream().map(MediaClip::label).collect(Collectors.joining(", ")))));
    }

    Located locate(String itemId) {
        return find(itemId).orElseThrow(() -> new EditException("unknown item id '" + itemId + "'"
                + (allItemIds().isEmpty() ? "; the timeline is empty" : "; items: " + String.join(", ", allItemIds()))));
    }

    Optional<Located> find(String itemId) {
        if (itemId == null) {
            return Optional.empty();
        }
        for (Track t : tracks) {
            for (TimelineItem i : items.get(t.id())) {
                if (i.id().equals(itemId.trim())) {
                    return Optional.of(new Located(t, i));
                }
            }
        }
        return Optional.empty();
    }

    List<String> allItemIds() {
        return items.values().stream().flatMap(List::stream).map(TimelineItem::id).toList();
    }

    /** The other half of an AV clip, if linked and present. */
    Optional<Located> partner(TimelineItem item) {
        if (item instanceof ClipItem c && c.linkId() != null) {
            return find(c.linkId());
        }
        return Optional.empty();
    }

    String describe(TimelineItem i) {
        String name = switch (i) {
            case ClipItem c -> bin.stream().filter(m -> m.id().equals(c.mediaId())).findFirst()
                    .map(MediaClip::name).orElse(c.mediaId());
            case TitleItem t -> "title \"" + abbreviate(t.text()) + "\"";
        };
        return i.id() + " (" + name + ")";
    }

    String span(TimelineItem i) {
        return sec(i.start()) + "–" + sec(i.end());
    }

    // ---------------------------------------------------------------- mutation

    void put(String trackId, TimelineItem item) {
        items.get(trackId).add(item);
    }

    void remove(String itemId) {
        for (List<TimelineItem> list : items.values()) {
            list.removeIf(i -> i.id().equals(itemId));
        }
    }

    void replace(TimelineItem item) {
        for (List<TimelineItem> list : items.values()) {
            for (int k = 0; k < list.size(); k++) {
                if (list.get(k).id().equals(item.id())) {
                    list.set(k, item);
                    return;
                }
            }
        }
        throw new IllegalStateException("item not found: " + item.id());
    }

    /** Moves an item to another track (same id). */
    void moveToTrack(TimelineItem item, String trackId) {
        remove(item.id());
        put(trackId, item);
    }

    // ---------------------------------------------------------------- overlap checks

    /** First item on the track overlapping [start, end), ignoring the given ids. */
    Optional<TimelineItem> occupant(String trackId, long start, long end, Set<String> ignore) {
        return items.get(trackId).stream()
                .filter(i -> !ignore.contains(i.id()))
                .filter(i -> i.start() < end && start < i.end())
                .min(Comparator.comparingLong(TimelineItem::start));
    }

    /** Earliest position &gt;= from where [p, p+length) is free on the track. */
    long nextFree(String trackId, long from, long length, Set<String> ignore) {
        long p = Math.max(0, from);
        List<TimelineItem> sorted = items.get(trackId).stream()
                .filter(i -> !ignore.contains(i.id()))
                .sorted(Comparator.comparingLong(TimelineItem::start)).toList();
        for (TimelineItem i : sorted) {
            if (i.end() <= p) {
                continue;
            }
            if (i.start() >= p + length) {
                break;
            }
            p = i.end();
        }
        return p;
    }

    /** Throws a helpful error if [start, start+length) is not free on the track. */
    void requireFree(String trackId, long start, long length, Set<String> ignore) {
        Optional<TimelineItem> hit = occupant(trackId, start, start + length, ignore);
        if (hit.isPresent()) {
            TimelineItem h = hit.get();
            long free = nextFree(trackId, start, length, ignore);
            throw new EditException(trackId + " is occupied by " + describe(h) + " from " + span(h)
                    + "; a " + sec(length) + "s item fits at " + sec(free) + " or later"
                    + gapBefore(trackId, start, length, ignore)
                    + " (no ripple editing in v1: move or trim the blocking item first)");
        }
    }

    private String gapBefore(String trackId, long start, long length, Set<String> ignore) {
        // latest free slot that ends at or before `start + length`, if any
        List<TimelineItem> sorted = items.get(trackId).stream()
                .filter(i -> !ignore.contains(i.id()))
                .sorted(Comparator.comparingLong(TimelineItem::start)).toList();
        long best = -1;
        long cursor = 0;
        for (TimelineItem i : sorted) {
            if (i.start() - cursor >= length && cursor <= start) {
                best = Math.min(start, i.start() - length);
            }
            cursor = Math.max(cursor, i.end());
        }
        return best >= 0 && best != start ? ", or at " + sec(best) : "";
    }

    // ---------------------------------------------------------------- build

    Project build() {
        List<Track> built = tracks.stream().map(t -> t.withItems(items.get(t.id()))).toList();
        return new Project(base.file(), base.profile(), bin, built, transitions, nextId, base.meta(), masterEffects);
    }

    static String abbreviate(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 24 ? one : one.substring(0, 23) + "…";
    }
}
