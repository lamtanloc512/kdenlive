package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.edit.Draft.Located;
import dev.kdenlivemcp.model.AudioSettings;
import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TitleItem;
import dev.kdenlivemcp.model.TrackKind;

import java.util.List;
import java.util.Locale;

/** Volume and fades on items that carry audio. */
public final class AudioOps {

    public static final double MIN_DB = -60;
    public static final double MAX_DB = 24;

    private AudioOps() {
    }

    /**
     * Sets volume and/or fades. Null leaves a value unchanged. On a video item of an AV clip the change
     * goes to its linked audio part.
     */
    public static EditResult set(Project p, String itemId, Double volumeDb, Long fadeIn, Long fadeOut) {
        Draft d = new Draft(p);
        Located loc = d.locate(itemId);
        if (loc.item() instanceof TitleItem t) {
            throw new EditException(t.id() + " is a title and has no audio");
        }
        ClipItem target = (ClipItem) loc.item();
        String via = "";
        if (loc.track().kind() == TrackKind.VIDEO) {
            Located partner = d.partner(target).orElseThrow(() -> new EditException(
                    d.describe(loc.item()) + " has no audio part on the timeline"
                            + (d.media(((ClipItem) loc.item()).mediaId()).kind().hasAudio()
                            ? " (it was inserted with withAudio=false)" : " (the media has no audio)")));
            target = (ClipItem) partner.item();
            via = " (audio part of " + itemId + ")";
        }
        if (volumeDb != null && (volumeDb.isNaN() || volumeDb < MIN_DB || volumeDb > MAX_DB)) {
            throw new EditException("volumeDb=" + volumeDb + " is out of range; use " + (int) MIN_DB + " to +"
                    + (int) MAX_DB + " dB (0 = unchanged, -6 = about half as loud)");
        }
        AudioSettings a = target.audio();
        double vol = volumeDb == null ? a.volumeDb() : volumeDb;
        long fi = fadeIn == null ? a.fadeIn() : fadeIn;
        long fo = fadeOut == null ? a.fadeOut() : fadeOut;
        if (fi < 0 || fo < 0) {
            throw new EditException("fades must be >= 0 seconds");
        }
        if (fi + fo > target.length()) {
            throw new EditException("fadeIn " + d.sec(fi) + "s + fadeOut " + d.sec(fo) + "s exceed the length of "
                    + target.id() + " (" + d.sec(target.length()) + "s)");
        }
        d.replace(target.withAudio(new AudioSettings(vol, fi, fo)));
        String summary = String.format(Locale.ROOT, "audio of %s%s: volume %+.1f dB, fade in %ss, fade out %ss",
                target.id(), via, vol, d.sec(fi), d.sec(fo));
        return new EditResult(d.build(), summary, d.notes, List.of(target.id()));
    }
}
