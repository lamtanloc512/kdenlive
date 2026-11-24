package dev.kdenlivemcp.tools;

/** External programs we drive. */
public enum Binary {
    MELT("melt", "MELT_PATH", "kdenlive.melt-path"),
    FFPROBE("ffprobe", "FFPROBE_PATH", "kdenlive.ffprobe-path"),
    FFMPEG("ffmpeg", "FFMPEG_PATH", "kdenlive.ffmpeg-path");

    public final String exe;
    public final String envVar;
    public final String property;

    Binary(String exe, String envVar, String property) {
        this.exe = exe;
        this.envVar = envVar;
        this.property = property;
    }
}
