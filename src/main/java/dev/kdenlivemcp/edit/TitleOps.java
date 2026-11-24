package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Extras;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TitleItem;
import dev.kdenlivemcp.model.Track;
import dev.kdenlivemcp.model.TrackKind;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Adding text titles. Styles come from title-presets.json. */
public final class TitleOps {

    public static final int MAX_TEXT = 500;

    private TitleOps() {
    }

    public static EditResult add(Project p, String text, long at, long length, String trackId, String styleId,
                                 Collection<String> styles) {
        Draft d = new Draft(p);
        if (text == null || text.isBlank()) {
            throw new EditException("text must not be empty");
        }
        if (text.length() > MAX_TEXT) {
            throw new EditException("text is " + text.length() + " characters; titles are limited to " + MAX_TEXT);
        }
        if (styleId == null || !styles.contains(styleId)) {
            throw new EditException("unknown title style '" + styleId + "'; styles: " + String.join(", ", styles));
        }
        Track track = d.track(trackId);
        if (track.kind() != TrackKind.VIDEO) {
            throw new EditException("titles go on video tracks; " + track.id() + " is an audio track. Video tracks: "
                    + TimelineOps.tracksOf(p, TrackKind.VIDEO));
        }
        d.requireFree(track.id(), at, length, Set.of());
        String id = d.newId("t");
        d.put(track.id(), new TitleItem(id, text, styleId, at, length, Extras.NONE));
        String summary = "added title " + id + " \"" + Draft.abbreviate(text) + "\" (" + styleId + ") on " + track.id()
                + " " + d.sec(at) + "–" + d.sec(at + length);
        if (higherVideoTrackCount(p, track) == 0 && track.number() == 1) {
            d.notes.add("the title is on V1, so it replaces the picture there; put it on V2 to overlay video");
        }
        return new EditResult(d.build(), summary, d.notes, List.of(id));
    }

    private static long higherVideoTrackCount(Project p, Track t) {
        return p.tracks().stream().filter(x -> x.kind() == TrackKind.VIDEO && x.number() > t.number()).count();
    }
}
