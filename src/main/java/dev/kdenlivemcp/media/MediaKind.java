package dev.kdenlivemcp.media;

/** Matches Kdenlive's ClipType values we use: AV=3, Video=2, Audio=1, Image=5. */
public enum MediaKind {
    AV, VIDEO, AUDIO, IMAGE;

    public boolean hasVideo() {
        return this != AUDIO;
    }

    public boolean hasAudio() {
        return this == AV || this == AUDIO;
    }
}
