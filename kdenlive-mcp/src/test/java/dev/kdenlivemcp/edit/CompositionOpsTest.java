package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.assets.AssetCatalog;
import dev.kdenlivemcp.assets.LumaLibrary;
import dev.kdenlivemcp.backend.kdenlive.KdenliveReader;
import dev.kdenlivemcp.backend.kdenlive.KdenliveWriter;
import dev.kdenlivemcp.model.Composition;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Mix;
import dev.kdenlivemcp.model.MixType;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.Track;
import dev.kdenlivemcp.title.TitlePresets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.kdenlivemcp.edit.Fixtures.f;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositionOpsTest {

    static final EffectOps.Env ENV = new EffectOps.Env(AssetCatalog.load(), s -> true, s -> true,
            new LumaLibrary(List.of()));

    Project p;

    @BeforeEach
    void setUp() {
        p = TimelineOps.insert(Fixtures.withMedia(), "m1", "V1", 0, null, null, false).project();
        p = TimelineOps.insert(p, "m2", "V2", f(1), null, null, false).project();
    }

    static Composition comp(Project p, String id) {
        return p.transitions().stream().filter(t -> t.id().equals(id)).map(t -> (Composition) t).findFirst().orElseThrow();
    }

    @Test
    void wipeWithAKdenliveShapeOverTheTrackBelow() {
        EditResult r = CompositionOps.add(p, ENV, "wipe", "V2", null, f(1), f(2),
                Map.of("luma", "Iris Circle", "softness", 10), null);
        Composition c = comp(r.project(), r.ids().getFirst());
        assertThat(c.track()).isEqualTo("V2");
        assertThat(c.withTrack()).isEqualTo("V1");
        assertThat(c.forceTrack()).isFalse();
        assertThat(c.props()).containsEntry("luma", "luma16.pgm").containsEntry("softness", "0.1")
                .containsEntry("mlt_service", "composite").containsEntry("kdenlive_id", "wipe")
                .containsEntry("aligned", "0").containsEntry("fill", "1");
    }

    @Test
    void slideDirectionsAndKeyframesFromTheCompositionStart() {
        EditResult r = CompositionOps.add(p, ENV, "slide", "V2", "background", f(1), f(2),
                Map.of("rect", Map.of("from", "right", "to", "center")), null);
        Composition c = comp(r.project(), r.ids().getFirst());
        assertThat(c.withTrack()).isNull();
        assertThat(c.forceTrack()).isTrue();
        assertThat(c.props().get("rect")).isEqualTo("0=100% 0% 100% 100% 100%;-1=0% 0% 100% 100% 100%");

        EditResult pip = CompositionOps.add(p, ENV, "qtblend", "V2", null, f(3), f(2), null, List.of(
                new EffectOps.KeySpec(0, null, Map.of("rect", "0 0 960 540 1")),
                new EffectOps.KeySpec(f(1), "smooth", Map.of("rect", "960 540 960 540 1"))));
        assertThat(comp(pip.project(), pip.ids().getFirst()).props().get("rect"))
                .isEqualTo("00:00:00.000=0 0 960 540 1;00:00:01.000$=960 540 960 540 1");
    }

    @Test
    void validation() {
        Project q = CompositionOps.add(p, ENV, "dissolve", "V2", null, f(1), f(2), null, null).project();
        assertThatThrownBy(() -> CompositionOps.add(q, ENV, "wipe", "V2", null, f(2), f(2), null, null))
                .isInstanceOf(EditException.class).hasMessageContaining("cannot overlap");
        assertThatThrownBy(() -> CompositionOps.add(p, ENV, "wipe", "A1", null, 0, f(1), null, null))
                .hasMessageContaining("video composition");
        assertThatThrownBy(() -> CompositionOps.add(p, ENV, "wipe", "V1", "V2", 0, f(1), null, null))
                .hasMessageContaining("above");
        assertThatThrownBy(() -> CompositionOps.add(p, ENV, "wipe", "V2", null, 0, f(1), Map.of("luma", "wobble"), null))
                .hasMessageContaining("Iris Circle");
        assertThatThrownBy(() -> CompositionOps.add(p, ENV, "wiper", "V2", null, 0, f(1), null, null))
                .hasMessageContaining("wipe");
    }

    @Test
    void roundTripSetAndRemove() {
        EditResult r = CompositionOps.add(p, ENV, "wipe", "V2", null, f(1), f(2), Map.of("luma", "luma05"), null);
        String id = r.ids().getFirst();
        String xml = KdenliveWriter.write(r.project(), TitlePresets.load(null)).xml();
        Project back = KdenliveReader.read(r.project().file(), xml);
        assertThat(comp(back, id)).isEqualTo(comp(r.project(), id));
        assertThat(xml).contains("<property name=\"a_track\">3</property>", "<property name=\"b_track\">4</property>");

        Project q = CompositionOps.set(r.project(), ENV, id, Map.of("softness", 50), null, f(2), null, null).project();
        assertThat(comp(q, id).start()).isEqualTo(f(2));
        assertThat(comp(q, id).props()).containsEntry("softness", "0.5");
        assertThat(CompositionOps.remove(q, id).project().transitions()).isEmpty();
    }

    @Test
    void tracksRenumberUnderCompositions() {
        Project q = TrackOps.add(p, "video", null).project(); // V3 on top
        q = CompositionOps.add(q, ENV, "wipe", "V3", "V1", 0, f(1), null, null).project();
        String id = q.transitions().getFirst().id();
        q = TrackOps.remove(q, "V2").project();
        assertThat(q.tracks()).extracting(Track::id).containsExactly("V2", "V1", "A1", "A2");
        assertThat(comp(q, id).track()).isEqualTo("V2");
        assertThat(comp(q, id).withTrack()).isEqualTo("V1");
    }

    @Test
    void wipeMixShape() {
        Project q = TimelineOps.insert(Fixtures.withMedia(), "m1", "V1", 0, null, f(3), false).project();
        q = TimelineOps.insert(q, "m2", "V1", f(3), f(1), null, false).project();
        Mix m = (Mix) TransitionOps.addMix(q, MixType.WIPE, "c4", "c5", f(1), "luma16.pgm").project().transitions()
                .getFirst();
        assertThat(m.luma()).isEqualTo("luma16.pgm");
        Mix dflt = (Mix) TransitionOps.addMix(q, MixType.WIPE, "c4", "c5", f(1)).project().transitions().getFirst();
        assertThat(dflt.luma()).isEqualTo("%luma01.pgm");
    }
}
