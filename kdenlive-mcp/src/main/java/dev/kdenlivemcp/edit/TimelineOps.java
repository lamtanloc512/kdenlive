package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.edit.Draft.Located;
import dev.kdenlivemcp.media.MediaKind;
import dev.kdenlivemcp.model.AudioSettings;
import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Extras;
import dev.kdenlivemcp.model.MediaClip;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TimelineItem;
import dev.kdenlivemcp.model.TitleItem;
import dev.kdenlivemcp.model.Track;
import dev.kdenlivemcp.model.TrackKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Insert, move, trim, split and remove. Every method is {@code Project -> EditResult} and never mutates
 * its input. Times are frames; the MCP layer converts seconds.
 * <p>
 * Rules (v1): items never overlap on a track (no ripple / overwrite), and an AV clip placed on a video
 * track gets its audio part on the paired audio track (V1 -> A1, V2 -> A2), linked so both move, trim,
 * split and get removed together.
 */
public final class TimelineOps {

    private TimelineOps() {
    }

    /**
     * @param in source start, or null for 0
     * @param out source end (exclusive), or null for the end of the media
     */
    public static EditResult insert(Project p, String mediaId, String trackId, long at, Long in, Long out,
                                    boolean withAudio) {
        Draft d = new Draft(p);
        MediaClip m = d.media(mediaId);
        Track track = d.track(trackId);
        MediaKind kind = m.kind();
        if (track.kind() == TrackKind.VIDEO && !kind.hasVideo()) {
            throw new EditException(m.label() + " has no video; insert it on an audio track: " + tracksOf(p, TrackKind.AUDIO));
        }
        if (track.kind() == TrackKind.AUDIO && !kind.hasAudio()) {
            throw new EditException(m.label() + " has no audio; insert it on a video track: " + tracksOf(p, TrackKind.VIDEO));
        }
        long mediaLen = m.lengthFrames(p.fps());
        long srcIn = in == null ? 0 : in;
        long srcOut;
        if (out != null) {
            srcOut = out;
        } else if (m.info().stillImage()) {
            srcOut = srcIn + p.fps().frames(5); // images default to 5 s, like Kdenlive
        } else {
            srcOut = mediaLen;
        }
        checkSourceRange(d, m, srcIn, srcOut);
        long length = srcOut - srcIn;

        d.requireFree(track.id(), at, length, Set.of());
        String id = d.newId("c");
        List<String> ids = new ArrayList<>(List.of(id));
        String partnerTrack = null;
        String partnerId = null;
        if (track.kind() == TrackKind.VIDEO && kind == MediaKind.AV && withAudio) {
            Track audio = pairedAudioTrack(p, track)
                    .orElseThrow(() -> new EditException("no audio track to hold the audio of " + m.label()
                            + "; pass withAudio=false to insert video only"));
            try {
                d.requireFree(audio.id(), at, length, Set.of());
            } catch (EditException e) {
                throw new EditException("the audio part of " + m.label() + " goes on " + audio.id() + ", but "
                        + e.getMessage() + ". Or pass withAudio=false to insert the video only");
            }
            partnerTrack = audio.id();
            partnerId = d.newId("c");
            ids.add(partnerId);
        }
        d.put(track.id(), new ClipItem(id, m.id(), at, srcIn, length, partnerId, AudioSettings.DEFAULT, Extras.NONE));
        String summary = "inserted " + id + " on " + track.id() + " " + d.sec(at) + "–" + d.sec(at + length)
                + " (" + m.name() + " in=" + d.sec(srcIn) + " out=" + d.sec(srcOut) + ")";
        if (partnerId != null) {
            d.put(partnerTrack, new ClipItem(partnerId, m.id(), at, srcIn, length, id, AudioSettings.DEFAULT, Extras.NONE));
            summary += "; audio part " + partnerId + " on " + partnerTrack;
        } else if (track.kind() == TrackKind.VIDEO && kind == MediaKind.AV) {
            summary += "; audio not inserted (withAudio=false)";
        }
        return new EditResult(d.build(), summary, d.notes, ids);
    }

    /** Moves an item (and its linked part) so it starts at {@code at}; optionally onto another track. */
    public static EditResult move(Project p, String itemId, String toTrack, long at) {
        Draft d = new Draft(p);
        Located loc = d.locate(itemId);
        TimelineItem item = loc.item();
        Track target = toTrack == null || toTrack.isBlank() ? loc.track() : d.track(toTrack);
        checkTrackAccepts(d, item, loc.track(), target);
        long delta = at - item.start();
        Optional<Located> partner = d.partner(item);
        if (partner.isPresent() && partner.get().item().start() + delta < 0) {
            throw new EditException("cannot move " + item.id() + " to " + d.sec(at) + ": its linked part "
                    + partner.get().item().id() + " would start before 0");
        }
        Set<String> ignore = new HashSet<>(Set.of(item.id()));
        partner.ifPresent(pl -> ignore.add(pl.item().id()));
        d.requireFree(target.id(), at, item.length(), ignore);
        if (partner.isPresent()) {
            Located pl = partner.get();
            try {
                d.requireFree(pl.track().id(), pl.item().start() + delta, pl.item().length(), ignore);
            } catch (EditException e) {
                throw new EditException("linked part " + pl.item().id() + " on " + pl.track().id() + " cannot follow: "
                        + e.getMessage());
            }
        }
        d.remove(item.id());
        d.put(target.id(), item.withStart(at));
        partner.ifPresent(pl -> d.replace(pl.item().withStart(pl.item().start() + delta)));
        String summary = "moved " + d.describe(item) + " to " + target.id() + " " + d.sec(at) + "–"
                + d.sec(at + item.length())
                + partner.map(pl -> "; linked " + pl.item().id() + " on " + pl.track().id() + " moved too").orElse("");
        TransitionOps.prune(d);
        return new EditResult(d.build(), summary, d.notes, List.of(item.id()));
    }

    /**
     * Changes the source range of a clip (and its linked part). The item keeps its timeline start.
     * For titles, {@code out} is the new duration and {@code in} is not allowed.
     */
    public static EditResult trim(Project p, String itemId, Long in, Long out) {
        Draft d = new Draft(p);
        Located loc = d.locate(itemId);
        if (in == null && out == null) {
            throw new EditException("give in and/or out (seconds of source time)");
        }
        return switch (loc.item()) {
            case TitleItem t -> {
                if (in != null) {
                    throw new EditException(t.id() + " is a title: it has no source in point. Use out=<duration> to change its length");
                }
                long len = out;
                if (len <= 0) {
                    throw new EditException("title duration must be at least one frame");
                }
                d.requireFree(loc.track().id(), t.start(), len, Set.of(t.id()));
                d.replace(t.withLength(len));
                String summary = "trimmed " + d.describe(t) + " to " + d.sec(len) + "s (" + d.sec(t.start()) + "–"
                        + d.sec(t.start() + len) + ")";
                TransitionOps.prune(d);
                yield new EditResult(d.build(), summary, d.notes, List.of(t.id()));
            }
            case ClipItem c -> {
                MediaClip m = d.media(c.mediaId());
                // in/out are source times; a speed-changed clip counts frames of the retimed clip
                long srcIn = in == null ? c.sourceInFrames() : in;
                long srcOut = out == null ? c.sourceOutFrames() : out;
                checkSourceRange(d, m, srcIn, srcOut);
                long newIn = Math.round(srcIn / c.speed());
                long newOut = srcOut;
                long len = Math.max(1, Math.round(srcOut / c.speed()) - newIn);
                Optional<Located> partner = d.partner(c);
                Set<String> ignore = new HashSet<>(Set.of(c.id()));
                partner.ifPresent(pl -> ignore.add(pl.item().id()));
                d.requireFree(loc.track().id(), c.start(), len, ignore);
                partner.ifPresent(pl -> d.requireFree(pl.track().id(), pl.item().start(), len, ignore));
                d.replace(clampFades(d, c.withSource(newIn, len)));
                partner.ifPresent(pl -> d.replace(clampFades(d, ((ClipItem) pl.item()).withSource(newIn, len))));
                String summary = "trimmed " + d.describe(c) + " to in=" + d.sec(srcIn) + " out=" + d.sec(newOut)
                        + " (" + d.sec(c.start()) + "–" + d.sec(c.start() + len) + " on timeline)"
                        + partner.map(pl -> "; linked " + pl.item().id() + " trimmed too").orElse("");
                TransitionOps.prune(d);
                yield new EditResult(d.build(), summary, d.notes, List.of(c.id()));
            }
        };
    }

    /** Removes an item, its linked part, and transitions that depended on them. */
    public static EditResult remove(Project p, String itemId) {
        Draft d = new Draft(p);
        Located loc = d.locate(itemId);
        Optional<Located> partner = d.partner(loc.item());
        d.remove(loc.item().id());
        partner.ifPresent(pl -> d.remove(pl.item().id()));
        String summary = "removed " + d.describe(loc.item()) + " from " + loc.track().id()
                + partner.map(pl -> " and its linked part " + pl.item().id() + " from " + pl.track().id()).orElse("");
        TransitionOps.prune(d);
        return new EditResult(d.build(), summary, d.notes, List.of(loc.item().id()));
    }

    /**
     * Splits an item at timeline position {@code at}. The left part keeps the id; the right part gets a
     * new one. Linked parts are split at the same position.
     */
    public static EditResult split(Project p, String itemId, long at) {
        Draft d = new Draft(p);
        Located loc = d.locate(itemId);
        TimelineItem item = loc.item();
        if (at <= item.start() || at >= item.end()) {
            throw new EditException("split position " + d.sec(at) + " must be inside " + d.describe(item) + " ("
                    + d.span(item) + ", exclusive of both ends)");
        }
        String rightId = d.newId(item instanceof TitleItem ? "t" : "c");
        List<String> ids = new ArrayList<>(List.of(item.id(), rightId));
        java.util.Map<String, String> rightPartOf = new java.util.HashMap<>(java.util.Map.of(item.id(), rightId));
        String summary;
        switch (item) {
            case TitleItem t -> {
                d.replace(new TitleItem(t.id(), t.text(), t.styleId(), t.start(), at - t.start(), t.fadeIn(), 0, t.extras(),
                        t.effects()));
                d.put(loc.track().id(), new TitleItem(rightId, t.text(), t.styleId(), at, t.end() - at, 0, t.fadeOut(),
                        t.extras(), d.copyEffects(t.effects())));
                summary = "split " + d.describe(t) + " at " + d.sec(at) + " into " + t.id() + " and " + rightId;
            }
            case ClipItem c -> {
                Optional<Located> partner = d.partner(c);
                String rightPartnerId = partner.isPresent() ? d.newId("c") : null;
                splitClip(d, loc.track().id(), c, at, rightId, rightPartnerId);
                if (partner.isPresent()) {
                    ClipItem pc = (ClipItem) partner.get().item();
                    splitClip(d, partner.get().track().id(), pc, at, rightPartnerId, rightId);
                    ids.add(rightPartnerId);
                    rightPartOf.put(pc.id(), rightPartnerId);
                }
                summary = "split " + d.describe(c) + " at " + d.sec(at) + " into " + c.id() + " ("
                        + d.sec(c.start()) + "–" + d.sec(at) + ") and " + rightId + " (" + d.sec(at) + "–"
                        + d.sec(c.end()) + ")"
                        + partner.map(pl -> "; linked part split into " + pl.item().id() + " and " + rightPartnerId)
                        .orElse("");
            }
        }
        // transitions at the item's end now belong to the right part
        for (int k = 0; k < d.transitions.size(); k++) {
            d.transitions.set(k, TransitionOps.reassignAfterSplit(d.transitions.get(k), rightPartOf));
        }
        TransitionOps.prune(d);
        return new EditResult(d.build(), summary, d.notes, ids);
    }

    private static void splitClip(Draft d, String trackId, ClipItem c, long at, String rightId, String rightLink) {
        long leftLen = at - c.start();
        AudioSettings a = c.audio();
        ClipItem left = new ClipItem(c.id(), c.mediaId(), c.start(), c.in(), leftLen, c.linkId(),
                new AudioSettings(a.volumeDb(), Math.min(a.fadeIn(), leftLen), 0), c.extras(), c.effects(), c.speed(),
                c.keepPitch());
        long rightLen = c.length() - leftLen;
        // keyframes are in source frames, so both halves keep the same animation
        ClipItem right = new ClipItem(rightId, c.mediaId(), at, c.in() + leftLen, rightLen, rightLink,
                new AudioSettings(a.volumeDb(), 0, Math.min(a.fadeOut(), rightLen)), c.extras(),
                d.copyEffects(c.effects()), c.speed(), c.keepPitch());
        d.replace(left);
        d.put(trackId, right);
    }

    // ---------------------------------------------------------------- helpers

    static void checkSourceRange(Draft d, MediaClip m, long in, long out) {
        long len = m.lengthFrames(d.fps());
        boolean image = m.info().stillImage();
        String limits = image ? "" : m.label() + " is " + d.sec(len) + "s long";
        if (in < 0) {
            throw new EditException("in=" + d.sec(in) + " is invalid: must be >= 0");
        }
        if (!image && in >= len) {
            throw new EditException(limits + ", in=" + d.sec(in) + " is invalid (must be < " + d.sec(len) + ")");
        }
        if (!image && out > len) {
            throw new EditException(limits + ", out=" + d.sec(out) + " is invalid (max " + d.sec(len) + ")");
        }
        if (out <= in) {
            throw new EditException("out=" + d.sec(out) + " must be after in=" + d.sec(in));
        }
    }

    private static void checkTrackAccepts(Draft d, TimelineItem item, Track from, Track to) {
        if (from.kind() != to.kind()) {
            throw new EditException("cannot move " + d.describe(item) + " from " + from.kind().name().toLowerCase()
                    + " track " + from.id() + " to " + to.kind().name().toLowerCase() + " track " + to.id()
                    + "; use a " + from.kind().name().toLowerCase() + " track: " + tracksOf(d.base, from.kind()));
        }
    }

    private static ClipItem clampFades(Draft d, ClipItem c) {
        AudioSettings a = c.audio();
        long fi = Math.min(a.fadeIn(), c.length());
        long fo = Math.min(a.fadeOut(), c.length() - fi);
        if (fi != a.fadeIn() || fo != a.fadeOut()) {
            d.notes.add("audio fades on " + c.id() + " shortened to fit (in " + d.sec(fi) + "s, out " + d.sec(fo) + "s)");
            return c.withAudio(new AudioSettings(a.volumeDb(), fi, fo));
        }
        return c;
    }

    /** V1 -> A1, V2 -> A2; falls back to the first audio track. */
    static Optional<Track> pairedAudioTrack(Project p, Track video) {
        Optional<Track> same = p.track("A" + video.number());
        if (same.isPresent() && same.get().kind() == TrackKind.AUDIO) {
            return same;
        }
        return p.tracks().stream().filter(t -> t.kind() == TrackKind.AUDIO).findFirst();
    }

    static String tracksOf(Project p, TrackKind kind) {
        List<String> ids = p.tracks().stream().filter(t -> t.kind() == kind).map(Track::id).toList();
        return ids.isEmpty() ? "(none)" : String.join(", ", ids);
    }
}
