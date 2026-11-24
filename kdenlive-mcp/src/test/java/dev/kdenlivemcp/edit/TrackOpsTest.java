package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.backend.kdenlive.KdenliveReader;
import dev.kdenlivemcp.backend.kdenlive.KdenliveWriter;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.Track;
import dev.kdenlivemcp.title.TitlePresets;
import org.junit.jupiter.api.Test;

import static dev.kdenlivemcp.edit.Fixtures.f;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackOpsTest {

    @Test
    void newVideoTracksGoOnTopAndAudioAtTheBottom() {
        Project p = TrackOps.add(Fixtures.withMedia(), "video", "Overlays").project();
        p = TrackOps.add(p, "audio", null).project();
        assertThat(p.tracks()).extracting(Track::id).containsExactly("V3", "V2", "V1", "A1", "A2", "A3");
        // and they survive save/open, names included
        String xml = KdenliveWriter.write(p, TitlePresets.load(null)).xml();
        Project back = KdenliveReader.read(p.file(), xml);
        assertThat(back.tracks()).extracting(Track::id).containsExactly("V3", "V2", "V1", "A1", "A2", "A3");
        assertThat(back.track("V3").orElseThrow().extras().properties()).containsEntry("kdenlive:track_name", "Overlays");
    }

    @Test
    void clipsCanBePlacedOnANewTrack() {
        Project p = TrackOps.add(Fixtures.withMedia(), "video", null).project();
        p = TimelineOps.insert(p, "m1", "V3", 0, null, null, false).project();
        assertThat(p.track("V3").orElseThrow().items()).hasSize(1);
    }

    @Test
    void flagsAndNames() {
        Project p = TrackOps.set(Fixtures.withMedia(), "A2", true, null, true, "Music").project();
        Track a2 = p.track("A2").orElseThrow();
        assertThat(a2.muted()).isTrue();
        assertThat(a2.locked()).isTrue();
        assertThat(a2.extras().properties()).containsEntry("kdenlive:track_name", "Music");
        assertThatThrownBy(() -> TrackOps.set(p, "A2", null, true, null, null)).hasMessageContaining("muted");
    }

    @Test
    void removingATrackRenumbersTheOthersAndDropsLinkedParts() {
        Project p = TimelineOps.insert(Fixtures.withMedia(), "m1", "V1", 0, null, null, true).project();
        p = TimelineOps.insert(p, "m2", "V2", f(1), null, null, false).project();
        EditResult r = TrackOps.remove(p, "V1");
        assertThat(r.project().tracks()).extracting(Track::id).containsExactly("V1", "A1", "A2");
        // the old V2 clip is now on V1; the removed clip's audio part on A1 is gone too
        assertThat(r.project().track("V1").orElseThrow().items()).hasSize(1);
        assertThat(r.project().track("A1").orElseThrow().items()).isEmpty();
        assertThat(r.summary()).contains("V2→V1");
        assertThatThrownBy(() -> TrackOps.remove(r.project(), "V1")).hasMessageContaining("only video track");
    }
}
