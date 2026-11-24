package dev.kdenlivemcp.model;

import java.util.Comparator;
import java.util.List;

/**
 * A timeline track. {@code id} is the Kdenlive-style name ("V1" is the lowest video track, "A1" the
 * first audio track). Items are kept sorted by start and never overlap.
 */
public record Track(String id, TrackKind kind, List<TimelineItem> items, boolean muted, boolean hidden, boolean locked,
                    Extras extras) {

    public Track {
        items = items.stream().sorted(Comparator.comparingLong(TimelineItem::start)).toList();
    }

    public static Track empty(String id, TrackKind kind) {
        return new Track(id, kind, List.of(), false, false, false, Extras.NONE);
    }

    /** 1 for V1/A1, 2 for V2/A2, ... */
    public int number() {
        return Integer.parseInt(id.substring(1));
    }

    public Track withItems(List<TimelineItem> newItems) {
        return new Track(id, kind, newItems, muted, hidden, locked, extras);
    }

    /** End of the last item, in frames. */
    public long end() {
        return items.stream().mapToLong(TimelineItem::end).max().orElse(0);
    }
}
