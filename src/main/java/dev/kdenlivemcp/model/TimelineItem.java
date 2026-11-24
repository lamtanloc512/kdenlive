package dev.kdenlivemcp.model;

/** Something placed on a track. Times are frames; {@code end()} is exclusive. */
public sealed interface TimelineItem permits ClipItem, TitleItem {

    String id();

    long start();

    long length();

    default long end() {
        return start() + length();
    }

    TimelineItem withStart(long start);
}
