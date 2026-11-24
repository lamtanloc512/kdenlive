package dev.kdenlivemcp.model;

/**
 * Project video profile, mirroring the MLT {@code <profile>} element.
 *
 * @param mltName MLT/Kdenlive profile id, e.g. {@code atsc_1080p_30}
 */
public record Profile(
        String mltName,
        String description,
        int width,
        int height,
        Fps fps,
        int sampleAspectNum,
        int sampleAspectDen,
        int displayAspectNum,
        int displayAspectDen,
        int colorspace,
        boolean progressive) {

    public boolean vertical() {
        return height > width;
    }
}
