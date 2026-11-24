package dev.kdenlivemcp.model;

import java.util.List;

/**
 * A piece of a bin clip on the timeline.
 *
 * @param in first source frame used
 * @param length number of frames used (source out = in + length, exclusive)
 * @param linkId id of the audio (or video) part of the same AV clip on another track, or null
 * @param effects the item's effect stack, in order (volume/fades from {@link #audio} are written before it)
 * @param speed playback speed (1 = normal, 2 = twice as fast, 0.5 = slow motion). With speed != 1, {@code in} and
 *              {@code length} count frames of the speed-changed clip, as in Kdenlive's timewarp producers: source
 *              frame = frame * speed.
 * @param keepPitch keep the audio pitch when the speed changes (Kdenlive's "Pitch compensation")
 */
public record ClipItem(String id, String mediaId, long start, long in, long length, String linkId,
                       AudioSettings audio, Extras extras, List<Effect> effects, double speed, boolean keepPitch)
        implements TimelineItem {

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
        if (!(speed > 0)) {
            speed = 1;
        }
    }

    public ClipItem(String id, String mediaId, long start, long in, long length, String linkId, AudioSettings audio,
                    Extras extras, List<Effect> effects) {
        this(id, mediaId, start, in, length, linkId, audio, extras, effects, 1, false);
    }

    public ClipItem(String id, String mediaId, long start, long in, long length, String linkId, AudioSettings audio,
                    Extras extras) {
        this(id, mediaId, start, in, length, linkId, audio, extras, List.of(), 1, false);
    }

    public boolean speedChanged() {
        return speed != 1;
    }

    /** Source frames shown: in point and exclusive out point in the original media. */
    public long sourceInFrames() {
        return Math.round(in * speed);
    }

    public long sourceOutFrames() {
        return Math.round((in + length) * speed);
    }

    public ClipItem withSpeed(double newSpeed, boolean newKeepPitch, long newIn, long newLength, List<Effect> newEffects) {
        return new ClipItem(id, mediaId, start, newIn, newLength, linkId, audio, extras, newEffects, newSpeed, newKeepPitch);
    }

    /** Exclusive source out frame. */
    public long out() {
        return in + length;
    }

    @Override
    public ClipItem withStart(long newStart) {
        return new ClipItem(id, mediaId, newStart, in, length, linkId, audio, extras, effects, speed, keepPitch);
    }

    public ClipItem withSource(long newIn, long newLength) {
        return new ClipItem(id, mediaId, start, newIn, newLength, linkId, audio, extras, effects, speed, keepPitch);
    }

    public ClipItem withLink(String newLink) {
        return new ClipItem(id, mediaId, start, in, length, newLink, audio, extras, effects, speed, keepPitch);
    }

    public ClipItem withAudio(AudioSettings newAudio) {
        return new ClipItem(id, mediaId, start, in, length, linkId, newAudio, extras, effects, speed, keepPitch);
    }

    public ClipItem withId(String newId) {
        return new ClipItem(newId, mediaId, start, in, length, linkId, audio, extras, effects, speed, keepPitch);
    }

    @Override
    public ClipItem withEffects(List<Effect> newEffects) {
        return new ClipItem(id, mediaId, start, in, length, linkId, audio, extras, newEffects, speed, keepPitch);
    }

    @Override
    public long sourceIn() {
        return in;
    }
}
