package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.MediaClip;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TimelineItem;
import dev.kdenlivemcp.model.TitleItem;
import dev.kdenlivemcp.model.Track;
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

    // ---------------------------------------------------------------- lookups

    Track track(String id) {
        if (id == null || id.isBlank()) {
            throw new EditException("track is required; tracks: " + trackList());
        }
        return base.track(id.trim())
                .orElseThrow(() -> new EditException("track '" + id + "' does not exist; tracks: " + trackList()));
    }

    String trackList() {
        return base.tracks().stream()
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
        for (Track t : base.tracks()) {
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
        List<Track> tracks = base.tracks().stream().map(t -> t.withItems(items.get(t.id()))).toList();
        return new Project(base.file(), base.profile(), bin, tracks, transitions, nextId, base.meta());
    }

    static String abbreviate(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 24 ? one : one.substring(0, 23) + "…";
    }
}
