package dev.kdenlivemcp.model;

/** Per-item audio adjustments. Fades are in frames. */
public record AudioSettings(double volumeDb, long fadeIn, long fadeOut) {

    public static final AudioSettings DEFAULT = new AudioSettings(0, 0, 0);

    public boolean isDefault() {
        return volumeDb == 0 && fadeIn == 0 && fadeOut == 0;
    }
}
