package dev.kdenlivemcp.model;

import java.util.List;

/** Something placed on a track. Times are frames; {@code end()} is exclusive. */
public sealed interface TimelineItem permits ClipItem, TitleItem {

    String id();

    long start();

    long length();

    default long end() {
        return start() + length();
    }

    TimelineItem withStart(long start);

    List<Effect> effects();

    TimelineItem withEffects(List<Effect> effects);

    /** Source frame shown at {@link #start()}; effect keyframes on the item are positioned in source frames. */
    long sourceIn();
}
