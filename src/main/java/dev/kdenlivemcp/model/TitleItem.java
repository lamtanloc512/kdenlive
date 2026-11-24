package dev.kdenlivemcp.model;

/**
 * A text title on a video track.
 *
 * @param styleId id of an entry in title-presets.json, or {@link #EXTERNAL} for a title made in Kdenlive
 *                (its title document is kept in {@code extras} and written back unchanged)
 * @param fadeIn alpha fade-in length in frames
 * @param fadeOut alpha fade-out length in frames
 */
public record TitleItem(String id, String text, String styleId, long start, long length, long fadeIn, long fadeOut,
                        Extras extras) implements TimelineItem {

    public static final String EXTERNAL = "external";

    public TitleItem {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be > 0");
        }
        if (extras == null) {
            extras = Extras.NONE;
        }
        fadeIn = Math.max(0, Math.min(fadeIn, length));
        fadeOut = Math.max(0, Math.min(fadeOut, length - fadeIn));
    }

    @Override
    public TitleItem withStart(long newStart) {
        return new TitleItem(id, text, styleId, newStart, length, fadeIn, fadeOut, extras);
    }

    public TitleItem withLength(long newLength) {
        return new TitleItem(id, text, styleId, start, newLength, fadeIn, fadeOut, extras);
    }

    public TitleItem withId(String newId) {
        return new TitleItem(newId, text, styleId, start, length, fadeIn, fadeOut, extras);
    }

    public TitleItem withFades(long in, long out) {
        return new TitleItem(id, text, styleId, start, length, in, out, extras);
    }
}
