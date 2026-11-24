package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.media.MediaInfo;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.ProfilePreset;
import dev.kdenlivemcp.model.Project;

import java.nio.file.Path;
import java.util.List;

/** Small in-memory projects for edit tests (30 fps, no disk access). */
public final class Fixtures {

    private Fixtures() {
    }

    public static MediaInfo av(String name, double seconds) {
        return new MediaInfo(Path.of("/media/" + name), seconds, true, true, false, 1920, 1080, Fps.of(30), 0, false,
                "h264", "aac", 48000, 2, "mov,mp4", 1000);
    }

    public static MediaInfo audio(String name, double seconds) {
        return new MediaInfo(Path.of("/media/" + name), seconds, false, true, false, 0, 0, null, 0, false,
                null, "aac", 48000, 2, "mov,mp4", 1000);
    }

    public static MediaInfo videoOnly(String name, double seconds) {
        return new MediaInfo(Path.of("/media/" + name), seconds, true, false, false, 1920, 1080, Fps.of(30), 0, false,
                "h264", null, 0, 0, "mov,mp4", 1000);
    }

    /** Project with m1 = bars.mp4 (6 s AV), m2 = testsrc.mp4 (6 s AV), m3 = music.m4a (12 s audio). */
    public static Project withMedia() {
        Project p = ProjectOps.create(Path.of("/tmp/test.kdenlive"), ProfilePreset.P1080_30.profile);
        return ProjectOps.importMedia(p, List.of(av("bars.mp4", 6), av("testsrc.mp4", 6), audio("music.m4a", 12)))
                .project();
    }

    /** Seconds to frames at 30 fps. */
    public static long f(double seconds) {
        return Math.round(seconds * 30);
    }
}
