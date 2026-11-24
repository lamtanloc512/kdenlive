package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.assets.AssetCatalog;
import dev.kdenlivemcp.backend.kdenlive.AssFile;
import dev.kdenlivemcp.backend.kdenlive.KdenliveReader;
import dev.kdenlivemcp.backend.kdenlive.KdenliveWriter;
import dev.kdenlivemcp.media.MediaKind;
import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.MediaClip;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.Subtitles;
import dev.kdenlivemcp.model.TimelineItem;
import dev.kdenlivemcp.title.TitlePresets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.kdenlivemcp.edit.Fixtures.f;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guides/markers, subtitles, clip speed and color clips. */
class TimelineExtrasTest {

    static final TitlePresets PRESETS = TitlePresets.load(null);

    static ClipItem clip(Project p, String id) {
        return (ClipItem) p.tracks().stream().flatMap(t -> t.items().stream()).filter(i -> i.id().equals(id))
                .findFirst().orElseThrow();
    }

    /** Writes the project and its side files to dir, reads it back. */
    static Project roundTrip(Project p, Path dir) throws Exception {
        Project at = p.withFile(dir.resolve("t.kdenlive"));
        KdenliveWriter.Output out = KdenliveWriter.write(at, PRESETS);
        for (Map.Entry<Path, String> e : out.titleFiles().entrySet()) {
            Files.createDirectories(e.getKey().getParent());
            Files.writeString(e.getKey(), e.getValue());
        }
        Files.writeString(at.file(), out.xml());
        return KdenliveReader.read(at.file());
    }

    @Test
    void guidesAndClipMarkers(@TempDir Path dir) throws Exception {
        Project p = MarkerOps.add(Fixtures.withMedia(), null, f(2), "Intro", 1, 0).project();
        p = MarkerOps.add(p, null, f(10), "Chorus", 3, f(4)).project();
        p = MarkerOps.add(p, "m1", f(1), "good take", 8, 0).project();
        assertThat(MarkerOps.list(p, null)).extracting(MarkerOps.Marker::comment).containsExactly("Intro", "Chorus");
        Project back = roundTrip(p, dir);
        assertThat(MarkerOps.list(back, null)).isEqualTo(MarkerOps.list(p, null));
        assertThat(MarkerOps.list(back, "m1")).containsExactly(new MarkerOps.Marker(f(1), "good take", 8, 0));
        String xml = Files.readString(dir.resolve("t.kdenlive"));
        assertThat(xml).contains("\"comment\": \"Chorus\"", "\"duration\": 120", "\"pos\": 300", "\"type\": 3");
        p = MarkerOps.remove(back, null, f(2)).project();
        assertThat(MarkerOps.list(p, null)).hasSize(1);
        assertThatThrownBy(() -> MarkerOps.add(Fixtures.withMedia(), null, 0, "x", 9, 0)).hasMessageContaining("0..8");
    }

    @Test
    void subtitlesAreAnAssFileNextToTheProject(@TempDir Path dir) throws Exception {
        Project p = SubtitleOps.add(Fixtures.withMedia(), f(1), f(3), "Xin chào, thế giới").project();
        p = SubtitleOps.add(p, 0, f(1), "First\\nline two").project();
        assertThat(p.subtitles().lines()).extracting(Subtitles.Line::text).containsExactly("First\nline two",
                "Xin chào, thế giới");
        Project back = roundTrip(p, dir);
        assertThat(back.subtitles().lines()).isEqualTo(p.subtitles().lines());
        String ass = Files.readString(dir.resolve("t.kdenlive.ass"));
        assertThat(ass).contains("[Events]", "Dialogue: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,First\\Nline two",
                "Style: Default,Arial,60.00,&H00FFFFFF");
        assertThat(Files.readString(dir.resolve("t.kdenlive"))).contains("avfilter.subtitles",
                "<property name=\"internal_added\">237</property>", "kdenlive:sequenceproperties.subtitlesList");

        Project styled = SubtitleOps.style(back, new Subtitles.Style("Arial", 64, "#ffe000", "#000000", 2, true, "top", 30,
                "#00000080", true)).project();
        assertThat(AssFile.write(styled.subtitles(), styled.profile(), "x"))
                .contains("Style: Default,Arial,64.00,&H0000E0FF,&H000000FF,&H00000000,&H7F000000,-1,0,0,0,100.00,100.00,0.00,0.00,3,2.00,0.00,8,10,10,30,1");
        assertThat(SubtitleOps.remove(styled, 1).project().subtitles().lines()).hasSize(1);
        assertThatThrownBy(() -> SubtitleOps.set(styled, 5, null, null, "x")).hasMessageContaining("1..2");
    }

    @Test
    void srtImport() {
        String srt = "1\n00:00:01,000 --> 00:00:02,500\nHello <i>there</i>\n\n2\n00:00:03,000 --> 00:00:04,000\nBye\n";
        Subtitles s = AssFile.parseSrt(srt, Fixtures.withMedia().fps());
        Project p = SubtitleOps.importLines(Fixtures.withMedia(), s, f(2), false).project();
        assertThat(p.subtitles().lines()).extracting(Subtitles.Line::start, Subtitles.Line::text)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(f(3), "Hello there"),
                        org.assertj.core.groups.Tuple.tuple(f(5), "Bye"));
    }

    @Test
    void speedRetimesTheClipItsLinkAndItsKeyframes(@TempDir Path dir) throws Exception {
        // c4 = bars.mp4 1.0..3.0 s at 0 on V1, audio c5 on A1
        Project p = TimelineOps.insert(Fixtures.withMedia(), "m1", "V1", 0, f(1), f(3), true).project();
        p = EffectOps.add(p, new EffectOps.Env(AssetCatalog.load(), s -> true), "c4", "brightness", null, List.of(
                new EffectOps.KeySpec(0, null, Map.of("level", 0)), new EffectOps.KeySpec(f(1), null, Map.of("level", 100))),
                null).project();
        EditResult r = SpeedOps.set(p, "c4", 0.5, true);
        ClipItem c = clip(r.project(), "c4");
        assertThat(c.speed()).isEqualTo(0.5);
        assertThat(c.length()).isEqualTo(f(4));
        assertThat(c.in()).isEqualTo(f(2)); // frames of the half-speed clip
        assertThat(c.sourceInFrames()).isEqualTo(f(1));
        assertThat(c.sourceOutFrames()).isEqualTo(f(3));
        assertThat(clip(r.project(), "c5").speed()).isEqualTo(0.5);
        // keyframes moved with the pictures: source 1.0 s and 2.0 s are now at 2.0 s and 4.0 s
        assertThat(c.effects().getFirst().props().get("level")).isEqualTo("00:00:02.000=0;00:00:04.000=1");

        Project back = roundTrip(r.project(), dir);
        ClipItem b = clip(back, "c4");
        assertThat(b.speed()).isEqualTo(0.5);
        assertThat(b.keepPitch()).isTrue();
        assertThat(b.in()).isEqualTo(c.in());
        assertThat(b.length()).isEqualTo(c.length());
        String xml = Files.readString(dir.resolve("t.kdenlive"));
        assertThat(xml).contains("<property name=\"mlt_service\">timewarp</property>",
                "<property name=\"warp_speed\">0.5</property>", "<property name=\"warp_pitch\">1</property>");

        // trimming a retimed clip uses source times
        Project t = TimelineOps.trim(back, "c4", f(1.5), null).project();
        assertThat(clip(t, "c4").sourceInFrames()).isEqualTo(f(1.5));
        assertThatThrownBy(() -> SpeedOps.set(back, "c4", -1, false)).hasMessageContaining("reverse");
    }

    @Test
    void speedUpCannotOverlapTheNextClipWhenSlowingDown() {
        Project p = TimelineOps.insert(Fixtures.withMedia(), "m1", "V1", 0, 0L, f(2), false).project();
        Project q = TimelineOps.insert(p, "m2", "V1", f(2), null, null, false).project();
        assertThatThrownBy(() -> SpeedOps.set(q, "c4", 0.5, false)).isInstanceOf(EditException.class)
                .hasMessageContaining("occupied");
        assertThat(clip(SpeedOps.set(q, "c4", 2, false).project(), "c4").length()).isEqualTo(f(1));
    }

    @Test
    void colorClips(@TempDir Path dir) throws Exception {
        EditResult r = ProjectOps.addColor(Fixtures.withMedia(), "#ff8000", "Orange");
        String id = r.ids().getFirst();
        MediaClip m = r.project().media(id).orElseThrow();
        assertThat(m.kind()).isEqualTo(MediaKind.COLOR);
        assertThat(m.color()).isEqualTo("0xff8000ff");
        Project p = TimelineOps.insert(r.project(), id, "V1", 0, null, f(2), false).project();
        TimelineItem item = p.track("V1").orElseThrow().items().getFirst();
        assertThat(item.length()).isEqualTo(f(2));
        Project back = roundTrip(p, dir);
        assertThat(back.media(id).orElseThrow().color()).isEqualTo("0xff8000ff");
        assertThat(back.media(id).orElseThrow().name()).isEqualTo("Orange");
        assertThat(back.track("V1").orElseThrow().items()).hasSize(1);
        assertThat(Files.readString(dir.resolve("t.kdenlive"))).contains("<property name=\"resource\">0xff8000ff</property>");
    }
}
