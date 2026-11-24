package dev.kdenlivemcp.media;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs the real ffprobe on media generated with ffmpeg. */
@QuarkusTest
@Tag("media")
class FfprobeServiceTest {

    static Path dir = Path.of("target/test-media");

    @Inject
    FfprobeService ffprobe;

    @BeforeAll
    static void generate() throws Exception {
        assumeTrue(TestMedia.available("ffmpeg"), "ffmpeg not installed");
        Files.createDirectories(dir);
        TestMedia.bars(dir, 6);
        TestMedia.music(dir, 12);
    }

    @Test
    void probesAvClip() {
        MediaInfo m = ffprobe.probe(dir.resolve("bars.mp4"));
        assertThat(m.kind()).isEqualTo(MediaKind.AV);
        assertThat(m.width()).isEqualTo(1920);
        assertThat(m.fps().label()).isEqualTo("30");
        assertThat(m.durationSeconds()).isBetween(5.9, 6.1);
    }

    @Test
    void probesAudio() {
        MediaInfo m = ffprobe.probe(dir.resolve("music.m4a"));
        assertThat(m.kind()).isEqualTo(MediaKind.AUDIO);
        assertThat(m.durationSeconds()).isBetween(11.9, 12.1);
    }

    @Test
    void missingAndBrokenFiles() throws Exception {
        assertThatThrownBy(() -> ffprobe.probe(dir.resolve("nope.mp4"))).hasMessageStartingWith("file not found: ");
        Path junk = dir.resolve("junk.mp4");
        Files.writeString(junk, "not a video");
        assertThatThrownBy(() -> ffprobe.probe(junk)).hasMessageStartingWith("ffprobe cannot read junk.mp4: ");
    }
}
