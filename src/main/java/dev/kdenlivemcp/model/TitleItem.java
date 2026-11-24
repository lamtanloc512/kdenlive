package dev.kdenlivemcp.model;

/**
 * A text title on a video track.
 *
 * @param styleId id of an entry in title-presets.json
 */
public record TitleItem(String id, String text, String styleId, long start, long length, Extras extras)
        implements TimelineItem {

    public TitleItem {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be > 0");
        }
        if (extras == null) {
            extras = Extras.NONE;
        }
    }

    @Override
    public TitleItem withStart(long newStart) {
        return new TitleItem(id, text, styleId, newStart, length, extras);
    }

    public TitleItem withLength(long newLength) {
        return new TitleItem(id, text, styleId, start, newLength, extras);
    }

    public TitleItem withId(String newId) {
        return new TitleItem(newId, text, styleId, start, length, extras);
    }
}
