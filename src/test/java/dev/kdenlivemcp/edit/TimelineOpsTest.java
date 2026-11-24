package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TimelineItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.kdenlivemcp.edit.Fixtures.f;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimelineOpsTest {

    private final Project base = Fixtures.withMedia();

    private static List<TimelineItem> items(Project p, String track) {
        return p.track(track).orElseThrow().items();
    }

    private static ClipItem clip(Project p, String track, int index) {
        return (ClipItem) items(p, track).get(index);
    }

    @Test
    void insertAvClipPlacesLinkedAudioOnPairedTrack() {
        EditResult r = TimelineOps.insert(base, "m1", "V1", 0, null, null, true);
        Project p = r.project();
        ClipItem v = clip(p, "V1", 0);
        ClipItem a = clip(p, "A1", 0);
        assertThat(v.length()).isEqualTo(f(6));
        assertThat(v.linkId()).isEqualTo(a.id());
        assertThat(a.linkId()).isEqualTo(v.id());
        assertThat(a.start()).isEqualTo(0);
        assertThat(r.summary()).isEqualTo("inserted c4 on V1 0.000–6.000 (bars.mp4 in=0.000 out=6.000); audio part c5 on A1");
        assertThat(base.track("V1").orElseThrow().items()).isEmpty(); // input untouched
    }

    @Test
    void insertWithoutAudio() {
        Project p = TimelineOps.insert(base, "m1", "V1", 0, null, null, false).project();
        assertThat(items(p, "A1")).isEmpty();
        assertThat(clip(p, "V1", 0).linkId()).isNull();
    }

    @Test
    void insertWithSourceRange() {
        Project p = TimelineOps.insert(base, "m1", "V2", f(10), f(1), f(3), false).project();
        ClipItem c = clip(p, "V2", 0);
        assertThat(c.in()).isEqualTo(f(1));
        assertThat(c.length()).isEqualTo(f(2));
        assertThat(c.start()).isEqualTo(f(10));
    }

    @Test
    void insertRejectsOutPastEnd() {
        assertThatThrownBy(() -> TimelineOps.insert(base, "m1", "V1", 0, null, f(15), true))
                .isInstanceOf(EditException.class)
                .hasMessage("m1 (bars.mp4) is 6.000s long, out=15.000 is invalid (max 6.000)");
    }

    @Test
    void insertRejectsUnknownTrackAndClip() {
        assertThatThrownBy(() -> TimelineOps.insert(base, "m1", "V9", 0, null, null, true))
                .hasMessage("track 'V9' does not exist; tracks: V2 (video), V1 (video), A1 (audio), A2 (audio)");
        assertThatThrownBy(() -> TimelineOps.insert(base, "m9", "V1", 0, null, null, true))
                .hasMessageStartingWith("unknown clip id 'm9'; bin: m1 (bars.mp4), m2 (testsrc.mp4), m3 (music.m4a)");
    }

    @Test
    void insertRejectsWrongTrackKind() {
        assertThatThrownBy(() -> TimelineOps.insert(base, "m3", "V1", 0, null, null, true))
                .hasMessage("m3 (music.m4a) has no video; insert it on an audio track: A1, A2");
    }

    @Test
    void insertAudioOnlyClipOnAudioTrack() {
        Project p = TimelineOps.insert(base, "m3", "A2", 0, null, null, true).project();
        assertThat(clip(p, "A2", 0).linkId()).isNull();
        assertThat(clip(p, "A2", 0).length()).isEqualTo(f(12));
    }

    @Test
    void insertRejectsOverlapAndSuggestsFreeSpot() {
        Project p = TimelineOps.insert(base, "m1", "V1", 0, null, null, true).project();
        assertThatThrownBy(() -> TimelineOps.insert(p, "m2", "V1", f(3), null, null, false))
                .hasMessage("V1 is occupied by c4 (bars.mp4) from 0.000–6.000; a 6.000s item fits at 6.000 or later "
                        + "(no ripple editing in v1: move or trim the blocking item first)");
    }

    @Test
    void insertRejectsWhenPairedAudioTrackIsBusy() {
        Project p = TimelineOps.insert(base, "m3", "A1", 0, null, null, true).project();
        assertThatThrownBy(() -> TimelineOps.insert(p, "m1", "V1", 0, null, null, true))
                .hasMessageStartingWith("the audio part of m1 (bars.mp4) goes on A1, but A1 is occupied by")
                .hasMessageEndingWith("Or pass withAudio=false to insert the video only");
    }

    @Test
    void moveCarriesLinkedPart() {
        Project p = TimelineOps.insert(base, "m1", "V1", 0, null, null, true).project();
        EditResult r = TimelineOps.move(p, "c4", null, f(2));
        assertThat(clip(r.project(), "V1", 0).start()).isEqualTo(f(2));
        assertThat(clip(r.project(), "A1", 0).start()).isEqualTo(f(2));
        assertThat(r.summary()).isEqualTo("moved c4 (bars.mp4) to V1 2.000–8.000; linked c5 on A1 moved too");
    }

    @Test
    void moveToOtherTrack() {
        Project p = TimelineOps.insert(base, "m1", "V1", 0, null, null, true).project();
        Project moved = TimelineOps.move(p, "c4", "V2", f(1)).project();
        assertThat(items(moved, "V1")).isEmpty();
        assertThat(clip(moved, "V2", 0).start()).isEqualTo(f(1));
        assertThat(clip(moved, "A1", 0).start()).isEqualTo(f(1));
    }

    @Test
    void moveRejectsTrackKindChangeAndOverlap() {
        Project p = TimelineOps.insert(base, "m1", "V1", 0, null, null, true).project();
        Project p2 = TimelineOps.insert(p, "m2", "V1", f(6), null, null, true).project();
        assertThatThrownBy(() -> TimelineOps.move(p2, "c4", "A2", 0))
                .hasMessage("cannot move c4 (bars.mp4) from video track V1 to audio track A2; use a video track: V2, V1");
        assertThatThrownBy(() -> TimelineOps.move(p2, "c4", null, f(3)))
                .hasMessageStartingWith("V1 is occupied by c6 (testsrc.mp4) from 6.000–12.000");
    }

    @Test
    void moveOntoItselfOverlapIsAllowed() {
        Project p = TimelineOps.insert(base, "m1", "V1", 0, null, null, true).project();
        Project moved = TimelineOps.move(p, "c4", null, f(1)).project(); // overlaps its own old position
        assertThat(clip(moved, "V1", 0).start()).isEqualTo(f(1));
    }

    @Test
    void trimKeepsStartAndTrimsPartner() {
        Project p = TimelineOps.insert(base, "m1", "V1", f(1), null, null, true).project();
        EditResult r = TimelineOps.trim(p, "c4", f(1), f(5));
        ClipItem v = clip(r.project(), "V1", 0);
        ClipItem a = clip(r.project(), "A1", 0);
        assertThat(v.start()).isEqualTo(f(1));
        assertThat(v.in()).isEqualTo(f(1));
        assertThat(v.length()).isEqualTo(f(4));
        assertThat(a.in()).isEqualTo(v.in());
        assertThat(a.length()).isEqualTo(v.length());
        assertThat(r.summary()).isEqualTo(
                "trimmed c4 (bars.mp4) to in=1.000 out=5.000 (1.000–5.000 on timeline); linked c5 trimmed too");
    }

    @Test
    void trimValidatesAgainstMediaLength() {
        Project p = TimelineOps.insert(base, "m1", "V1", 0, null, null, true).project();
        assertThatThrownBy(() -> TimelineOps.trim(p, "c4", null, f(15)))
                .hasMessage("m1 (bars.mp4) is 6.000s long, out=15.000 is invalid (max 6.000)");
        assertThatThrownBy(() -> TimelineOps.trim(p, "c4", f(4), f(3)))
                .hasMessage("out=3.000 must be after in=4.000");
    }

    @Test
    void trimCannotGrowIntoNeighbour() {
        Project p = TimelineOps.insert(base, "m1", "V1", 0, null, f(3), true).project();
        Project p2 = TimelineOps.insert(p, "m2", "V1", f(3), null, null, true).project();
        assertThatThrownBy(() -> TimelineOps.trim(p2, "c4", null, f(5)))
                .hasMessageStartingWith("V1 is occupied by c6 (testsrc.mp4) from 3.000–9.000");
    }

    @Test
    void splitIsFrameExactAndSplitsPartner() {
        Project p = TimelineOps.insert(base, "m1", "V1", f(1), null, null, true).project();
        EditResult r = TimelineOps.split(p, "c4", f(3));
        Project s = r.project();
        assertThat(items(s, "V1")).hasSize(2);
        assertThat(items(s, "A1")).hasSize(2);
        ClipItem left = clip(s, "V1", 0);
        ClipItem right = clip(s, "V1", 1);
        assertThat(left.id()).isEqualTo("c4");
        assertThat(left.length()).isEqualTo(f(2));
        assertThat(right.start()).isEqualTo(f(3));
        assertThat(right.in()).isEqualTo(f(2));
        assertThat(right.length()).isEqualTo(f(4));
        assertThat(left.length() + right.length()).isEqualTo(f(6));
        // links: left video <-> left audio, right video <-> right audio
        ClipItem leftA = clip(s, "A1", 0);
        ClipItem rightA = clip(s, "A1", 1);
        assertThat(left.linkId()).isEqualTo(leftA.id());
        assertThat(right.linkId()).isEqualTo(rightA.id());
        assertThat(rightA.linkId()).isEqualTo(right.id());
        assertThat(r.ids()).containsExactly("c4", right.id(), rightA.id());
    }

    @Test
    void splitRejectsPositionOutsideItem() {
        Project p = TimelineOps.insert(base, "m1", "V1", f(1), null, null, true).project();
        assertThatThrownBy(() -> TimelineOps.split(p, "c4", f(1)))
                .hasMessage("split position 1.000 must be inside c4 (bars.mp4) (1.000–7.000, exclusive of both ends)");
    }

    @Test
    void removeDropsPartner() {
        Project p = TimelineOps.insert(base, "m1", "V1", 0, null, null, true).project();
        EditResult r = TimelineOps.remove(p, "c5"); // remove via the audio part
        assertThat(items(r.project(), "V1")).isEmpty();
        assertThat(items(r.project(), "A1")).isEmpty();
        assertThat(r.summary()).isEqualTo("removed c5 (bars.mp4) from A1 and its linked part c4 from V1");
    }

    @Test
    void unknownItemListsExistingIds() {
        Project p = TimelineOps.insert(base, "m1", "V1", 0, null, null, true).project();
        assertThatThrownBy(() -> TimelineOps.move(p, "c99", null, 0))
                .hasMessage("unknown item id 'c99'; items: c4, c5");
    }
}
