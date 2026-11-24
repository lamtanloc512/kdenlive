package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.assets.AssetCatalog;
import dev.kdenlivemcp.backend.kdenlive.KdenliveReader;
import dev.kdenlivemcp.backend.kdenlive.KdenliveWriter;
import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Effect;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TimelineItem;
import dev.kdenlivemcp.title.TitlePresets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.kdenlivemcp.edit.Fixtures.f;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectOpsTest {

    static final AssetCatalog CATALOG = AssetCatalog.load();
    static final EffectOps.Env ENV = new EffectOps.Env(CATALOG, s -> true);

    Project p;

    @BeforeEach
    void setUp() {
        // c4 = bars.mp4 from source 1.0 s on V1 at 2.0 s (audio part c5 on A1)
        p = TimelineOps.insert(Fixtures.withMedia(), "m1", "V1", f(2), f(1), f(5), true).project();
    }

    static TimelineItem item(Project p, String id) {
        return p.tracks().stream().flatMap(t -> t.items().stream()).filter(i -> i.id().equals(id)).findFirst()
                .orElseThrow();
    }

    @Test
    void addsWithDefaultsInKdenlivesPropertyOrder() {
        EditResult r = EffectOps.add(p, ENV, "c4", "avfilter.gblur", Map.of("av.sigma", 4), null, null);
        Effect fx = item(r.project(), "c4").effects().getFirst();
        assertThat(fx.id()).isEqualTo(r.ids().getFirst());
        assertThat(fx.props().keySet()).containsExactly("av.steps", "av.planes", "av.sigma", "av.sigmaV",
                "mlt_service", "kdenlive_id", "kdenlive:collapsed");
        // animated params are anchored at the clip's source in point (1.0 s)
        assertThat(fx.props().get("av.sigma")).isEqualTo("00:00:01.000=4");
        assertThat(fx.props().get("av.sigmaV")).isEqualTo("00:00:01.000=10");
        assertThat(r.summary()).contains("av.sigma=4");
    }

    @Test
    void keyframeTimesAreFromTheItemStartAndStoredInSourceFrames() {
        EditResult r = EffectOps.add(p, ENV, "c4", "brightness", null, List.of(
                new EffectOps.KeySpec(0, null, Map.of("level", 0)),
                new EffectOps.KeySpec(f(1), "ease_out", Map.of("level", 100)),
                new EffectOps.KeySpec(-1, null, Map.of("level", 50))), null);
        Effect fx = item(r.project(), "c4").effects().getFirst();
        // item: 4 s long, source in 1.0 s -> keys at source 1.0, 2.0 and the last frame (4.967)
        assertThat(fx.props().get("level")).isEqualTo("00:00:01.000=0;00:00:02.000h=1;00:00:04.967=0.5");
    }

    @Test
    void rejectsKeyframesOnFixedParametersAndOutsideTheItem() {
        assertThatThrownBy(() -> EffectOps.add(p, ENV, "c4", "avfilter.gblur", null,
                List.of(new EffectOps.KeySpec(0, null, Map.of("av.planes", 1))), null))
                .isInstanceOf(EditException.class).hasMessageContaining("cannot be keyframed");
        assertThatThrownBy(() -> EffectOps.add(p, ENV, "c4", "brightness", null,
                List.of(new EffectOps.KeySpec(f(9), null, Map.of("level", 1))), null))
                .hasMessageContaining("outside c4");
    }

    @Test
    void audioEffectsGoToTheAudioPart() {
        EditResult r = EffectOps.add(p, ENV, "c4", "avfilter.aphaser", null, null, null);
        assertThat(item(r.project(), "c5").effects()).hasSize(1);
        assertThat(item(r.project(), "c4").effects()).isEmpty();
        assertThat(r.notes()).anyMatch(n -> n.contains("audio part"));
    }

    @Test
    void unknownEffectSuggestsNames() {
        assertThatThrownBy(() -> EffectOps.add(p, ENV, "c4", "gblur", null, null, null))
                .hasMessageContaining("avfilter.gblur");
        assertThatThrownBy(() -> EffectOps.add(p, ENV, "c4", "avfilter.gblur", Map.of("radius", 3), null, null))
                .hasMessageContaining("av.sigma");
    }

    @Test
    void setRemoveMoveAndDisable() {
        Project q = EffectOps.add(p, ENV, "c4", "brightness", null, null, null).project();
        q = EffectOps.add(q, ENV, "c4", "sepia", null, null, null).project();
        List<Effect> stack = item(q, "c4").effects();
        String brightness = stack.get(0).id();
        String sepia = stack.get(1).id();

        q = EffectOps.set(q, ENV, brightness, Map.of("level", 150), null, false).project();
        Effect b = item(q, "c4").effects().getFirst();
        assertThat(b.props().get("level")).isEqualTo("00:00:01.000=1.5");
        assertThat(b.disabled()).isTrue();

        q = EffectOps.move(q, sepia, 0).project();
        assertThat(item(q, "c4").effects()).extracting(Effect::id).containsExactly(sepia, brightness);

        q = EffectOps.remove(q, brightness).project();
        assertThat(item(q, "c4").effects()).extracting(Effect::id).containsExactly(sepia);
        assertThatThrownBy(() -> EffectOps.remove(p, "e99")).hasMessageContaining("unknown effect");
    }

    @Test
    void uniqueEffectsOnlyOnce() {
        Project q = EffectOps.add(p, ENV, "c4", "fade_from_black", null, null, null).project();
        assertThatThrownBy(() -> EffectOps.add(q, ENV, "c4", "fade_from_black", null, null, null))
                .hasMessageContaining("once");
    }

    @Test
    void trackAndMasterEffects() {
        Project q = EffectOps.add(p, ENV, "V1", "sepia", null, null, null).project();
        q = EffectOps.add(q, ENV, "master", "avfilter.aphaser", null, null, null).project();
        assertThat(q.track("V1").orElseThrow().effects()).extracting(Effect::assetId).containsExactly("sepia");
        assertThat(q.masterEffects()).extracting(Effect::assetId).containsExactly("avfilter.aphaser");
        assertThatThrownBy(() -> EffectOps.add(p, ENV, "A1", "sepia", null, null, null))
                .hasMessageContaining("video effect");
    }

    @Test
    void splitCopiesTheStackWithNewIds() {
        Project q = EffectOps.add(p, ENV, "c4", "brightness", null, List.of(
                new EffectOps.KeySpec(0, null, Map.of("level", 0)),
                new EffectOps.KeySpec(f(4) - 1, null, Map.of("level", 100))), null).project();
        EditResult s = TimelineOps.split(q, "c4", f(4));
        ClipItem left = (ClipItem) item(s.project(), "c4");
        ClipItem right = (ClipItem) item(s.project(), s.ids().get(1));
        assertThat(right.effects()).hasSize(1);
        assertThat(right.effects().getFirst().id()).isNotEqualTo(left.effects().getFirst().id());
        // same source-frame animation on both halves
        assertThat(right.effects().getFirst().props()).isEqualTo(left.effects().getFirst().props());
    }

    @Test
    void effectsSurviveSaveAndOpenWithTheirIds() {
        Project q = EffectOps.add(p, ENV, "c4", "qtblend", null, List.of(
                new EffectOps.KeySpec(0, null, Map.of("rect", "0 0 1920 1080 1")),
                new EffectOps.KeySpec(f(2), "smooth", Map.of("rect", "-192 -108 2304 1296 1"))), null).project();
        q = EffectOps.add(q, ENV, "V1", "sepia", null, null, null).project();
        q = EffectOps.add(q, ENV, "master", "avfilter.aphaser", null, null, null).project();
        String xml = KdenliveWriter.write(q, TitlePresets.load(null)).xml();
        Project back = KdenliveReader.read(q.file(), xml);
        assertThat(item(back, "c4").effects()).isEqualTo(item(q, "c4").effects());
        assertThat(back.track("V1").orElseThrow().effects()).isEqualTo(q.track("V1").orElseThrow().effects());
        assertThat(back.masterEffects()).isEqualTo(q.masterEffects());
        assertThat(xml).contains("<property name=\"rect\">00:00:01.000=0 0 1920 1080 1;00:00:03.000$=-192 -108 2304 1296 1</property>");
    }

    @Test
    void cameraMoveBuildsTransformRects() {
        EditResult r = TransformOps.camera(p, ENV, "c4", List.of(
                new TransformOps.CameraKey(0, 1, null, null, null, null, null),
                new TransformOps.CameraKey(f(2), 2, 0.25, 0.25, null, null, "ease_in_out")), true);
        Effect fx = item(r.project(), "c4").effects().getFirst();
        assertThat(fx.assetId()).isEqualTo("qtblend");
        // zoom 2 centred on (0.25, 0.25): 3840x2160 at (0, 0) after clamping to cover the frame
        assertThat(fx.props().get("rect")).isEqualTo("00:00:01.000=0 0 1920 1080 1;00:00:03.000i=0 0 3840 2160 1");
        // calling again updates the same Transform
        EditResult again = TransformOps.camera(r.project(), ENV, "c4", List.of(
                new TransformOps.CameraKey(0, 1.5, null, null, 10.0, 0.5, null)), false);
        assertThat(item(again.project(), "c4").effects()).hasSize(1);
        assertThat(item(again.project(), "c4").effects().getFirst().props().get("rotation")).isEqualTo("00:00:01.000=10");
    }
}
