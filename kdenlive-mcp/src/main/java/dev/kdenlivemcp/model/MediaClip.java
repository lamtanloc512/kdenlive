package dev.kdenlivemcp.model;

import dev.kdenlivemcp.media.MediaInfo;
import dev.kdenlivemcp.media.MediaKind;

import java.nio.file.Path;

/**
 * A file in the project bin.
 *
 * @param id our short id ("m1")
 * @param binId Kdenlive's numeric bin id ({@code kdenlive:id})
 */
public record MediaClip(String id, int binId, Path path, String name, MediaInfo info, Extras extras) {

    public MediaClip {
        if (extras == null) {
            extras = Extras.NONE;
        }
    }

    public MediaKind kind() {
        return info.kind();
    }

    /** Length in project frames; images are unlimited. */
    public long lengthFrames(Fps fps) {
        return info.stillImage() ? Long.MAX_VALUE / 4 : fps.framesFloor(info.durationSeconds());
    }

    public String label() {
        return id + " (" + name + ")";
    }
}
