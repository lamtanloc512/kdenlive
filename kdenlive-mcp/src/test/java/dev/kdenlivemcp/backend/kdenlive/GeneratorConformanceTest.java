package dev.kdenlivemcp.backend.kdenlive;

import dev.kdenlivemcp.edit.AudioOps;
import dev.kdenlivemcp.edit.ProjectOps;
import dev.kdenlivemcp.edit.TimelineOps;
import dev.kdenlivemcp.edit.TitleOps;
import dev.kdenlivemcp.edit.TransitionOps;
import dev.kdenlivemcp.media.MediaInfo;
import dev.kdenlivemcp.model.MediaClip;
import dev.kdenlivemcp.model.MixType;
import dev.kdenlivemcp.model.ProfilePreset;
import dev.kdenlivemcp.model.Project;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rebuilds fixture 03 (made by hand in Kdenlive 26.08.3) using only our edit operations and checks that the
 * generated document means the same thing. The title differs on purpose (ours is a preset title file, the
 * fixture's is Kdenlive's simple.kdenlivetitle with its own fade), so title lines are compared separately.
 * The output is kept in fixtures/generated/ so it can be opened in Kdenlive.
 */
class GeneratorConformanceTest {

    @Test
    void opsReproduceFixture03() throws Exception {
        Path fixture = Path.of("fixtures/03-full.kdenlive");
        List<MediaInfo> infos = KdenliveReader.read(fixture).bin().stream().map(MediaClip::info).toList();
        Path out = Path.of("fixtures/generated/gen-03.kdenlive").toAbsolutePath();

        Project p = ProjectOps.create(out, ProfilePreset.P1080_30.profile);
        p = ProjectOps.importMedia(p, infos).project();                                   // m1 a, m2 b, m3 music
        p = TimelineOps.insert(p, "m1", "V1", 0, 0L, 165L, true).project();                // c4 / c5
        p = TimelineOps.insert(p, "m2", "V1", 165, 15L, 180L, true).project();             // c6 / c7
        p = TransitionOps.addMix(p, MixType.DISSOLVE, "c4", "c6", 30).project();
        p = TimelineOps.insert(p, "m3", "A2", 0, null, null, true).project();             // c10
        p = AudioOps.set(p, "c10", -6.0, null, 30L).project();
        p = TitleOps.add(p, "Hello", 32, 90, "V2", "big_center", FixtureRoundTripTest.PRESETS).project();

        KdenliveWriter.Output o = KdenliveWriter.write(p, FixtureRoundTripTest.PRESETS);
        Files.createDirectories(out.getParent());
        Files.writeString(out, o.xml());
        for (var f : o.titleFiles().entrySet()) {
            Files.createDirectories(f.getKey().getParent());
            Files.writeString(f.getKey(), f.getValue());
        }

        List<String> ours = SemanticSummary.of(o.xml());
        List<String> kdenlive = SemanticSummary.of(fixture);
        assertThat(withoutTitle(ours)).containsExactlyElementsOf(withoutTitle(kdenlive));
        assertThat(ours).anyMatch(l -> l.startsWith("  L0 32+90 in=0 bin=7 svc=kdenlivetitle fx[fade_from_black --15"));
        assertThat(ours).anyMatch(l -> l.startsWith("bin 7 kdenlivetitle t11.kdenlivetitle len=90"));
    }

    private static List<String> withoutTitle(List<String> lines) {
        return lines.stream().filter(l -> !l.contains("kdenlivetitle")).toList();
    }
}
