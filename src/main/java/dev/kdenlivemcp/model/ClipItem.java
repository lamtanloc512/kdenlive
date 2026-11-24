package dev.kdenlivemcp.model;

/**
 * A piece of a bin clip on the timeline.
 *
 * @param in first source frame used
 * @param length number of frames used (source out = in + length, exclusive)
 * @param linkId id of the audio (or video) part of the same AV clip on another track, or null
 */
public record ClipItem(String id, String mediaId, long start, long in, long length, String linkId,
                       AudioSettings audio, Extras extras) implements TimelineItem {

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
    }

    /** Exclusive source out frame. */
    public long out() {
        return in + length;
    }

    @Override
    public ClipItem withStart(long newStart) {
        return new ClipItem(id, mediaId, newStart, in, length, linkId, audio, extras);
    }

    public ClipItem withSource(long newIn, long newLength) {
        return new ClipItem(id, mediaId, start, newIn, newLength, linkId, audio, extras);
    }

    public ClipItem withLink(String newLink) {
        return new ClipItem(id, mediaId, start, in, length, newLink, audio, extras);
    }

    public ClipItem withAudio(AudioSettings newAudio) {
        return new ClipItem(id, mediaId, start, in, length, linkId, newAudio, extras);
    }

    public ClipItem withId(String newId) {
        return new ClipItem(newId, mediaId, start, in, length, linkId, audio, extras);
    }
}
