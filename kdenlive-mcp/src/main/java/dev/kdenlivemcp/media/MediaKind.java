package dev.kdenlivemcp.media;

/** Matches Kdenlive's ClipType values we use: AV=3, Video=2, Audio=1, Image=5. */
public enum MediaKind {
    AV, VIDEO, AUDIO, IMAGE,
    /** A Kdenlive color clip (mlt_service color): no file, any length. */
    COLOR;

    public boolean hasVideo() {
        return this != AUDIO;
    }

    public boolean hasAudio() {
        return this == AV || this == AUDIO;
    }
}
