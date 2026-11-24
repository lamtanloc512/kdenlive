package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.edit.Draft.Located;
import dev.kdenlivemcp.model.AudioSettings;
import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.Composition;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.FadeToBlack;
import dev.kdenlivemcp.model.MediaClip;
import dev.kdenlivemcp.model.Mix;
import dev.kdenlivemcp.model.MixType;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TimelineItem;
import dev.kdenlivemcp.model.TitleItem;
import dev.kdenlivemcp.model.TrackKind;
import dev.kdenlivemcp.model.Transition;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Dissolves / wipes (same-track mixes) and fade to black. */
public final class TransitionOps {

    /** Effectively unlimited handle for titles and images. */
    private static final long UNLIMITED = Long.MAX_VALUE / 8;

    private TransitionOps() {
    }

    public static EditResult addMix(Project p, MixType type, String itemA, String itemB, long length) {
        return addMix(p, type, itemA, itemB, length, null);
    }

    /** @param luma wipe shape for WIPE (built-in name or file path from LumaLibrary), null = Kdenlive's default */
    public static EditResult addMix(Project p, MixType type, String itemA, String itemB, long length, String luma) {
        Draft d = new Draft(p);
        Located a = d.locate(itemA);
        String name = type.name().toLowerCase();
        if (itemB == null || itemB.isBlank()) {
            Located given = a;
            Optional<TimelineItem> next = d.items.get(given.track().id()).stream()
                    .filter(i -> i.start() == given.item().end()).findFirst();
            throw new EditException(name + " needs itemB, the item that follows " + given.item().id() + " on "
                    + given.track().id() + next.map(n -> " (that is " + n.id() + ")").orElse(" (none touches its end right now)"));
        }
        Located b = d.locate(itemB);
        if (!a.track().id().equals(b.track().id())) {
            throw new EditException(name + " works between neighbours on the same track: " + a.item().id() + " is on "
                    + a.track().id() + ", " + b.item().id() + " is on " + b.track().id());
        }
        if (b.item().end() == a.item().start()) { // given in reverse order
            Located t = a;
            a = b;
            b = t;
            d.notes.add("items swapped: " + a.item().id() + " comes first on the timeline");
        }
        String track = a.track().id();
        if (a.item().end() != b.item().start()) {
            long gap = b.item().start() - a.item().end();
            throw new EditException(d.describe(a.item()) + " ends at " + d.sec(a.item().end()) + " and "
                    + d.describe(b.item()) + " starts at " + d.sec(b.item().start()) + " on " + track
                    + ": a " + name + " needs them to touch"
                    + (gap > 0 ? " (gap " + d.sec(gap) + "s; e.g. clip_move " + b.item().id() + " at="
                    + d.sec(a.item().end()) + ")" : ""));
        }
        if (type == MixType.WIPE && a.track().kind() == TrackKind.AUDIO) {
            throw new EditException("wipe is video-only; use dissolve for an audio crossfade on " + track);
        }
        Mix mix = planMix(d, type, a.item(), b.item(), length, d.newId("x"));
        if (type == MixType.WIPE && luma != null && !luma.isBlank()) {
            mix = new Mix(mix.id(), type, mix.itemA(), mix.itemB(), mix.length(), mix.cutOffset(), luma);
        }
        d.transitions.add(mix);
        List<String> ids = new ArrayList<>(List.of(mix.id()));
        String summary = "added " + name + " " + mix.id() + " between " + a.item().id() + " and " + b.item().id()
                + " on " + track + ", " + d.sec(length) + "s (" + d.sec(a.item().end() - mix.cutOffset()) + "–"
                + d.sec(a.item().end() - mix.cutOffset() + length) + ")";

        // audio parts of AV clips get a crossfade too
        Optional<Located> pa = d.partner(a.item());
        Optional<Located> pb = d.partner(b.item());
        if (pa.isPresent() && pb.isPresent()) {
            Located xa = pa.get();
            Located xb = pb.get();
            if (xa.track().id().equals(xb.track().id()) && xa.item().end() == xb.item().start()
                    && findMixAt(d, xa.item().id(), xb.item().id()).isEmpty()) {
                try {
                    Mix audio = planMix(d, MixType.DISSOLVE, xa.item(), xb.item(), length, d.newId("x"));
                    d.transitions.add(audio);
                    ids.add(audio.id());
                    summary += "; audio crossfade " + audio.id() + " on " + xa.track().id();
                } catch (EditException e) {
                    d.notes.add("no audio crossfade on " + xa.track().id() + ": " + e.getMessage());
                }
            }
        }
        return new EditResult(d.build(), summary, d.notes, ids);
    }

    private static Mix planMix(Draft d, MixType type, TimelineItem a, TimelineItem b, long length, String id) {
        String name = type.name().toLowerCase();
        findMixAt(d, a.id(), b.id()).ifPresent(m -> {
            throw new EditException(a.id() + " and " + b.id() + " already have mix " + m.id()
                    + "; remove it first (undo) to change it");
        });
        long maxLen = Math.min(a.length(), b.length());
        if (length > maxLen) {
            throw new EditException(name + " of " + d.sec(length) + "s is longer than the shorter item ("
                    + d.sec(maxLen) + "s); use duration <= " + d.sec(maxLen));
        }
        long hA = handleAfter(d, a);
        long hB = handleBefore(d, b);
        long lo = Math.max(0, length - hA);
        long hi = Math.min(length, hB);
        // keep clear of mixes already at A's start / B's end
        long aFree = a.length() - mixInsideStart(d, a);
        long bFree = b.length() - mixInsideEnd(d, b);
        hi = Math.min(hi, aFree);
        lo = Math.max(lo, length - bFree);
        if (lo > hi) {
            long possible = Math.max(0, Math.min(hA, bFree) + Math.min(hB, aFree));
            possible = Math.min(possible, maxLen);
            StringBuilder msg = new StringBuilder(name + " of " + d.sec(length) + "s between " + a.id() + " and "
                    + b.id() + " needs " + d.sec(length) + "s of spare media around the cut, but " + d.describe(a)
                    + " has " + handle(d, hA) + " after its out point and " + d.describe(b) + " has " + handle(d, hB)
                    + " before its in point. ");
            if (possible > 0) {
                msg.append("Use duration <= ").append(d.sec(possible)).append(", or free more media first: ");
            } else {
                msg.append("Free media first: ");
            }
            if (b instanceof ClipItem cb && hB < UNLIMITED) {
                long extra = length - Math.min(hA, length) - hB;
                msg.append("clip_trim(").append(b.id()).append(", in=").append(d.sec(cb.in() + extra))
                        .append(") keeps the clips touching.");
            } else {
                msg.append("trim ").append(a.id()).append("'s out earlier and move ").append(b.id())
                        .append(" to touch it.");
            }
            throw new EditException(msg.toString());
        }
        long cut = Math.max(lo, Math.min(hi, length / 2));
        return new Mix(id, type, a.id(), b.id(), length, cut);
    }

    public static EditResult addFadeToBlack(Project p, String itemId, long length) {
        Draft d = new Draft(p);
        Located loc = d.locate(itemId);
        TimelineItem item = loc.item();
        if (loc.track().kind() != TrackKind.VIDEO) {
            throw new EditException("fade_to_black needs a video item; " + item.id() + " is on audio track "
                    + loc.track().id() + ". For audio use audio_set(" + item.id() + ", fadeOut=...)");
        }
        if (length > item.length()) {
            throw new EditException("fade of " + d.sec(length) + "s is longer than " + d.describe(item) + " ("
                    + d.sec(item.length()) + "s)");
        }
        Iterator<Transition> it = d.transitions.iterator();
        while (it.hasNext()) {
            if (it.next() instanceof FadeToBlack f && f.itemId().equals(item.id())) {
                it.remove();
                d.notes.add("replaced previous fade " + f.id());
            }
        }
        FadeToBlack fade = new FadeToBlack(d.newId("x"), item.id(), length);
        d.transitions.add(fade);
        String summary = "added fade_to_black " + fade.id() + " on " + d.describe(item) + ", last " + d.sec(length)
                + "s (" + d.sec(item.end() - length) + "–" + d.sec(item.end()) + ")";
        Optional<Located> partner = d.partner(item);
        if (partner.isPresent() && partner.get().item() instanceof ClipItem pc) {
            AudioSettings a = pc.audio();
            long fadeIn = Math.min(a.fadeIn(), pc.length() - length);
            d.replace(pc.withAudio(new AudioSettings(a.volumeDb(), fadeIn, length)));
            summary += "; audio part " + pc.id() + " fades out over the same " + d.sec(length) + "s";
        }
        return new EditResult(d.build(), summary, d.notes, List.of(fade.id()));
    }

    // ---------------------------------------------------------------- consistency

    /**
     * Drops or shortens transitions that no longer fit after an edit, adding a note for each.
     * Called by every timeline operation.
     */
    static void prune(Draft d) {
        Iterator<Transition> it = d.transitions.iterator();
        List<Transition> replaced = new ArrayList<>();
        while (it.hasNext()) {
            Transition t = it.next();
            String problem = switch (t) {
                case Mix m -> mixProblem(d, m);
                case FadeToBlack f -> d.find(f.itemId()).isEmpty() ? "item " + f.itemId() + " no longer exists" : null;
                case Composition c -> d.tracks.stream().anyMatch(x -> x.id().equals(c.track())) ? null
                        : "track " + c.track() + " no longer exists";
            };
            if (problem != null) {
                it.remove();
                d.notes.add("removed " + label(t) + " " + t.id() + ": " + problem);
                continue;
            }
            if (t instanceof FadeToBlack f) {
                long len = d.locate(f.itemId()).item().length();
                if (f.length() > len) {
                    it.remove();
                    replaced.add(f.withLength(len));
                    d.notes.add("fade " + f.id() + " shortened to " + d.sec(len) + "s to fit " + f.itemId());
                }
            }
        }
        d.transitions.addAll(replaced);
    }

    private static String mixProblem(Draft d, Mix m) {
        Optional<Located> a = d.find(m.itemA());
        Optional<Located> b = d.find(m.itemB());
        if (a.isEmpty() || b.isEmpty()) {
            return "item " + (a.isEmpty() ? m.itemA() : m.itemB()) + " no longer exists";
        }
        if (!a.get().track().id().equals(b.get().track().id()) || a.get().item().end() != b.get().item().start()) {
            return m.itemA() + " and " + m.itemB() + " no longer touch";
        }
        if (m.length() - m.cutOffset() > handleAfter(d, a.get().item()) || m.cutOffset() > handleBefore(d, b.get().item())) {
            return "not enough spare media around the cut any more";
        }
        if (m.cutOffset() > a.get().item().length() || m.length() - m.cutOffset() > b.get().item().length()) {
            return "an item became shorter than the mix";
        }
        return null;
    }

    /** After splitting item X into X|R, a mix or fade at X's end belongs to R. */
    static Transition reassignAfterSplit(Transition t, Map<String, String> rightPartOf) {
        return switch (t) {
            case Mix m -> rightPartOf.containsKey(m.itemA()) ? m.withItems(rightPartOf.get(m.itemA()), m.itemB()) : m;
            case FadeToBlack f -> rightPartOf.containsKey(f.itemId()) ? f.withItem(rightPartOf.get(f.itemId())) : f;
            case Composition c -> c;
        };
    }

    // ---------------------------------------------------------------- helpers

    static Optional<Mix> findMixAt(Draft d, String a, String b) {
        return d.transitions.stream().filter(t -> t instanceof Mix).map(t -> (Mix) t)
                .filter(m -> m.itemA().equals(a) && m.itemB().equals(b)).findFirst();
    }

    /** Frames of a mix that end inside the item's start (mix where the item is B). */
    private static long mixInsideStart(Draft d, TimelineItem item) {
        return d.transitions.stream().filter(t -> t instanceof Mix).map(t -> (Mix) t)
                .filter(m -> m.itemB().equals(item.id())).mapToLong(m -> m.length() - m.cutOffset()).max().orElse(0);
    }

    /** Frames of a mix that start inside the item's end (mix where the item is A). */
    private static long mixInsideEnd(Draft d, TimelineItem item) {
        return d.transitions.stream().filter(t -> t instanceof Mix).map(t -> (Mix) t)
                .filter(m -> m.itemA().equals(item.id())).mapToLong(Mix::cutOffset).max().orElse(0);
    }

    static long handleAfter(Draft d, TimelineItem item) {
        return switch (item) {
            case TitleItem t -> UNLIMITED;
            case ClipItem c -> {
                MediaClip m = d.media(c.mediaId());
                yield m.info().stillImage() ? UNLIMITED
                        : Math.max(0, (long) Math.floor(m.lengthFrames(d.fps()) / c.speed()) - c.out());
            }
        };
    }

    static long handleBefore(Draft d, TimelineItem item) {
        return switch (item) {
            case TitleItem t -> UNLIMITED;
            case ClipItem c -> d.media(c.mediaId()).info().stillImage() ? UNLIMITED : c.in();
        };
    }

    private static String handle(Draft d, long frames) {
        return frames >= UNLIMITED ? "unlimited media" : d.sec(frames) + "s";
    }

    private static String label(Transition t) {
        return switch (t) {
            case Mix m -> m.type().name().toLowerCase();
            case FadeToBlack f -> "fade_to_black";
            case Composition c -> "composition " + c.assetId();
        };
    }
}
