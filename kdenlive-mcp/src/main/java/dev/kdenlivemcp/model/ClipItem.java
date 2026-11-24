package dev.kdenlivemcp.model;

import java.util.List;

/**
 * A piece of a bin clip on the timeline.
 *
 * @param in first source frame used
 * @param length number of frames used (source out = in + length, exclusive)
 * @param linkId id of the audio (or video) part of the same AV clip on another track, or null
 * @param effects the item's effect stack, in order (volume/fades from {@link #audio} are written before it)
 */
public record ClipItem(String id, String mediaId, long start, long in, long length, String linkId,
                       AudioSettings audio, Extras extras, List<Effect> effects) implements TimelineItem {

    public ClipItem {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be > 0");
        }
        if (audio == null) {
            audio = AudioSettings.DEFAULT;
        }
        if (extras == null) {
            extras = Extras.NONE;
        }
        effects = effects == null ? List.of() : List.copyOf(effects);
    }

    public ClipItem(String id, String mediaId, long start, long in, long length, String linkId, AudioSettings audio,
                    Extras extras) {
        this(id, mediaId, start, in, length, linkId, audio, extras, List.of());
    }

    /** Exclusive source out frame. */
    public long out() {
        return in + length;
    }

    @Override
    public ClipItem withStart(long newStart) {
        return new ClipItem(id, mediaId, newStart, in, length, linkId, audio, extras, effects);
    }

    public ClipItem withSource(long newIn, long newLength) {
        return new ClipItem(id, mediaId, start, newIn, newLength, linkId, audio, extras, effects);
    }

    public ClipItem withLink(String newLink) {
        return new ClipItem(id, mediaId, start, in, length, newLink, audio, extras, effects);
    }

    public ClipItem withAudio(AudioSettings newAudio) {
        return new ClipItem(id, mediaId, start, in, length, linkId, newAudio, extras, effects);
    }

    public ClipItem withId(String newId) {
        return new ClipItem(newId, mediaId, start, in, length, linkId, audio, extras, effects);
    }

    @Override
    public ClipItem withEffects(List<Effect> newEffects) {
        return new ClipItem(id, mediaId, start, in, length, linkId, audio, extras, newEffects);
    }

    @Override
    public long sourceIn() {
        return in;
    }
}
