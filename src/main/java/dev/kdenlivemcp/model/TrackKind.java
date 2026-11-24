package dev.kdenlivemcp.model;

public enum TrackKind {
    VIDEO, AUDIO;

    public String prefix() {
        return this == VIDEO ? "V" : "A";
    }
}
