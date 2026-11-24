package dev.kdenlivemcp.backend.kdenlive;

import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.FadeToBlack;
import dev.kdenlivemcp.model.Mix;
import dev.kdenlivemcp.model.MixType;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TitleItem;
import dev.kdenlivemcp.model.Transition;
import dev.kdenlivemcp.title.TitlePresets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** load fixture (written by Kdenlive 26.08.3) -> save -> semantic diff must be empty. */
class FixtureRoundTripTest {

    static final Path FIXTURES = Path.of("fixtures");
    static final TitlePresets PRESETS = TitlePresets.load(null);

    @ParameterizedTest
    @ValueSource(strings = {"01-empty.kdenlive", "02-one-clip.kdenlive", "03-full.kdenlive", "04-extras.kdenlive",
            "05-generated-resaved-by-kdenlive.kdenlive"})
    void roundTripKeepsTheTimeline(String name) {
        Path file = FIXTURES.resolve(name);
        Project p = KdenliveReader.read(file);
        String written = KdenliveWriter.write(p, PRESETS).xml();
        assertThat(SemanticSummary.of(written)).containsExactlyElementsOf(SemanticSummary.of(file));
        // and once more: our own output must read back to the same model
        Project again = KdenliveReader.read(file, written);
        assertThat(again.tracks()).isEqualTo(p.tracks());
        assertThat(again.transitions()).isEqualTo(p.transitions());
    }

    @Test
    void fullFixtureReadsAsTheModelWeExpect() {
        Project p = KdenliveReader.read(FIXTURES.resolve("03-full.kdenlive"));
        assertThat(p.fps().label()).isEqualTo("30");
        assertThat(p.tracks()).extracting(t -> t.id()).containsExactly("V2", "V1", "A1", "A2");
        assertThat(p.bin()).extracting(m -> m.path().getFileName().toString())
                .containsExactly("demo_a.mp4", "demo_b.mp4", "music.m4a");

        List<?> v1 = p.track("V1").orElseThrow().items();
        ClipItem a = (ClipItem) v1.get(0);
        ClipItem b = (ClipItem) v1.get(1);
        assertThat(a.start()).isZero();
        assertThat(a.length()).isEqualTo(165);          // 5.5 s, mix extension undone
        assertThat(b.start()).isEqualTo(165);
        assertThat(b.in()).isEqualTo(15);
        assertThat(a.linkId()).isNotNull();              // AVSplit group -> linked audio part
        assertThat(p.transitions()).hasSize(2).allSatisfy(t -> {
            Mix m = (Mix) t;
            assertThat(m.type()).isEqualTo(MixType.DISSOLVE);
            assertThat(m.length()).isEqualTo(30);
            assertThat(m.cutOffset()).isEqualTo(15);
        });

        ClipItem music = (ClipItem) p.track("A2").orElseThrow().items().getFirst();
        assertThat(music.audio().volumeDb()).isEqualTo(-6.0);
        assertThat(music.audio().fadeOut()).isEqualTo(30);

        TitleItem title = (TitleItem) p.track("V2").orElseThrow().items().getFirst();
        assertThat(title.text()).isEqualTo("Hello");
        assertThat(title.styleId()).isEqualTo(TitleItem.EXTERNAL);
        assertThat(title.start()).isEqualTo(32);
        assertThat(title.fadeIn()).isEqualTo(90);
    }

    @Test
    void ourIdsAndTitleStyleSurviveAKdenliveSave() {
        // 05 = our generated gen-03 opened in Kdenlive 26.08.3 and saved again (sequence becomes tractorN)
        Project p = KdenliveReader.read(FIXTURES.resolve("05-generated-resaved-by-kdenlive.kdenlive"));
        assertThat(p.track("V1").orElseThrow().items()).extracting(i -> i.id()).containsExactly("c4", "c6");
        assertThat(p.track("A2").orElseThrow().items()).extracting(i -> i.id()).containsExactly("c10");
        assertThat(p.transitions()).extracting(Transition::id).containsExactlyInAnyOrder("x8", "x9");
        TitleItem title = (TitleItem) p.track("V2").orElseThrow().items().getFirst();
        assertThat(title.id()).isEqualTo("t11");
        assertThat(title.styleId()).isEqualTo("big_center");
        assertThat(title.fadeIn()).isEqualTo(15);
        assertThat(p.nextId()).isEqualTo(12);
    }

    @Test
    void fadeToBlackIsRead() {
        Project p = KdenliveReader.read(FIXTURES.resolve("04-extras.kdenlive"));
        assertThat(p.transitions()).filteredOn(t -> t instanceof FadeToBlack).singleElement()
                .satisfies(t -> assertThat(((FadeToBlack) t).length()).isEqualTo(90));
    }
}
