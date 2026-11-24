package dev.kdenlivemcp.mcp;

import dev.kdenlivemcp.media.FfprobeService;
import dev.kdenlivemcp.media.MediaInfo;
import dev.kdenlivemcp.media.TestMedia;
import io.quarkiverse.mcp.server.ImageContent;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole workflow through the MCP tool beans, with real ffmpeg/ffprobe/melt:
 * new -> import -> insert 2 clips -> title -> dissolve -> music -> contact_sheet -> render draft.
 * Needs ffmpeg and melt (set MELT_PATH to Kdenlive's melt for titles to render on a headless Linux box).
 */
@QuarkusTest
@Tag("media")
class EndToEndToolsTest {

    static final Path MEDIA = Path.of("target/it-media").toAbsolutePath();
    static final Path WORK = Path.of("target/it-work").toAbsolutePath();

    @Inject
    ProjectTools project;
    @Inject
    MediaTools media;
    @Inject
    TimelineTools timeline;
    @Inject
    PreviewTools preview;
    @Inject
    RenderTools render;
    @Inject
    FfprobeService ffprobe;

    @BeforeAll
    static void media() throws Exception {
        assumeTrue(TestMedia.available("ffmpeg"), "ffmpeg not installed");
        Files.createDirectories(MEDIA);
        Files.createDirectories(WORK);
        TestMedia.bars(MEDIA, 6);
        TestMedia.testsrc(MEDIA, 6);
        TestMedia.music(MEDIA, 12);
    }

    @Test
    void fullWorkflowRendersAPlayableFile() throws Exception {
        Path proj = WORK.resolve("e2e.kdenlive");
        assertThat(project.projectNew(proj.toString(), "1080p30", true)).startsWith("created ");

        String imported = media.mediaImport(List.of(MEDIA.resolve("bars.mp4").toString(),
                MEDIA.resolve("testsrc.mp4").toString(), MEDIA.resolve("music.m4a").toString()));
        assertThat(imported).contains("m1: bars.mp4 6.000s 1920x1080@30", "m3: music.m4a 12.000s audio:aac");

        BufferedImage scout = image(media.mediaContactSheet("m2", null, null, 8));
        assertThat(scout.getWidth()).isGreaterThan(4 * PreviewTools.TILE_WIDTH);
        assertThatThrownBy(() -> media.mediaContactSheet("m3", null, null, 8)).hasMessageContaining("audio only");

        assertThat(timeline.clipInsert("m1", "V1", 0, 0.0, 5.0, true)).contains("inserted c4", "audio part c5 on A1");
        assertThat(timeline.clipInsert("m2", "V1", 5, 1.0, 6.0, true)).contains("inserted c6");
        assertThat(timeline.titleAdd("Hello", 0.5, 3, "V2", "lower_third")).contains("added title t8");
        assertThat(timeline.transitionAdd("dissolve", "c4", "c6", 1.0)).contains("added dissolve", "audio crossfade");
        assertThat(timeline.clipInsert("m3", "A2", 0, null, 10.0, true)).contains("inserted c11 on A2 0.000–10.000");
        assertThat(timeline.audioSet("c11", -12.0, 0.5, 1.0)).contains("volume -12.0 dB");

        // helpful errors come back as tool errors
        assertThatThrownBy(() -> timeline.clipInsert("m1", "V1", 2, null, 15.0, true))
                .isInstanceOf(ToolCallException.class)
                .hasMessage("m1 (bars.mp4) is 6.000s long, out=15.000 is invalid (max 6.000)");

        // undo + redo the last edit
        assertThat(timeline.undo()).startsWith("undid audio_set c11");
        timeline.audioSet("c11", -12.0, 0.5, 1.0);

        String json = timeline.timelineGet();
        assertThat(json).contains("\"duration\":10.0", "\"type\":\"dissolve\"", "\"text\":\"Hello\"");

        ToolResponse frame = preview.previewFrame(1.0);
        BufferedImage img = image(frame);
        assertThat(img.getWidth()).isEqualTo(960);
        assertThat(img.getHeight()).isEqualTo(540);

        ToolResponse sheet = preview.contactSheet(null, null, 8);
        BufferedImage s = image(sheet);
        assertThat(s.getWidth()).isGreaterThan(4 * PreviewTools.TILE_WIDTH);
        assertThat(s.getHeight()).isGreaterThan(2 * 200);
        Files.write(WORK.resolve("e2e-contact-sheet.png"), Base64.getDecoder().decode(
                ((ImageContent) sheet.content().get(1)).data()));

        Path out = WORK.resolve("e2e.mp4");
        Files.deleteIfExists(out);
        String started = render.render(out.toString(), "draft_540p");
        assertThat(started).contains("jobId=r");
        String jobId = started.replaceAll("(?s).*jobId=(r\\d+).*", "$1");
        String status = "";
        for (int i = 0; i < 240 && !status.startsWith("done") && !status.startsWith("failed"); i++) {
            Thread.sleep(500);
            status = render.renderStatus(jobId);
        }
        assertThat(status).startsWith("done");
        assertThat(out).exists();
        MediaInfo info = ffprobe.probe(out);
        assertThat(info.durationSeconds()).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.1));
        assertThat(info.hasAudio()).isTrue();
        assertThat(info.height()).isEqualTo(540);

        // the saved project reads back with the same ids
        assertThat(project.projectOpen(proj.toString())).contains("[c4 bars.mp4 0.000–5.000]", "[t8 \"Hello\"");
    }

    private static BufferedImage image(ToolResponse r) throws Exception {
        ImageContent ic = (ImageContent) r.content().get(1);
        assertThat(ic.mimeType()).isEqualTo("image/png");
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(ic.data())));
    }
}
