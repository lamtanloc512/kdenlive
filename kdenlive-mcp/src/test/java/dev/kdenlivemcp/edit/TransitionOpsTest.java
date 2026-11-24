package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.FadeToBlack;
import dev.kdenlivemcp.model.Mix;
import dev.kdenlivemcp.model.MixType;
import dev.kdenlivemcp.model.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.kdenlivemcp.edit.Fixtures.f;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransitionOpsTest {

    /** c4/c5 = bars 0–5 (source 0–5, 1 s spare after); c6/c7 = testsrc 5–10 (source 1–6, 1 s spare before). */
    private Project p;

    @BeforeEach
    void setUp() {
        Project base = Fixtures.withMedia();
        Project a = TimelineOps.insert(base, "m1", "V1", 0, 0L, f(5), true).project();
        p = TimelineOps.insert(a, "m2", "V1", f(5), f(1), f(6), true).project();
    }

    @Test
    void dissolveIsCenteredAndAddsAudioCrossfade() {
        EditResult r = TransitionOps.addMix(p, MixType.DISSOLVE, "c4", "c6", f(1));
        assertThat(r.project().transitions()).hasSize(2);
        Mix video = (Mix) r.project().transitions().get(0);
        Mix audio = (Mix) r.project().transitions().get(1);
        assertThat(video.itemA()).isEqualTo("c4");
        assertThat(video.itemB()).isEqualTo("c6");
        assertThat(video.cutOffset()).isEqualTo(f(0.5));
        assertThat(audio.itemA()).isEqualTo("c5");
        assertThat(audio.itemB()).isEqualTo("c7");
        assertThat(r.summary()).isEqualTo(
                "added dissolve x8 between c4 and c6 on V1, 1.000s (4.500–5.500); audio crossfade x9 on A1");
    }

    @Test
    void itemsGivenInReverseOrderAreSwapped() {
        EditResult r = TransitionOps.addMix(p, MixType.DISSOLVE, "c6", "c4", f(1));
        Mix m = (Mix) r.project().transitions().getFirst();
        assertThat(m.itemA()).isEqualTo("c4");
        assertThat(r.notes()).contains("items swapped: c4 comes first on the timeline");
    }

    @Test
    void mixShiftsWhenOnlyOneSideHasHandles() {
        // B has 1 s spare before its in point, A has none: the whole mix sits before the cut
        Project base = Fixtures.withMedia();
        Project a = TimelineOps.insert(base, "m1", "V1", 0, null, null, false).project();
        Project b = TimelineOps.insert(a, "m2", "V1", f(6), f(1), f(6), false).project();
        Mix m = (Mix) TransitionOps.addMix(b, MixType.DISSOLVE, "c4", "c5", f(1)).project().transitions().getFirst();
        assertThat(m.cutOffset()).isEqualTo(f(1));
    }

    @Test
    void notEnoughHandlesExplainsHowToFix() {
        Project base = Fixtures.withMedia();
        Project a = TimelineOps.insert(base, "m1", "V1", 0, null, null, false).project();
        Project b = TimelineOps.insert(a, "m2", "V1", f(6), null, null, false).project();
        assertThatThrownBy(() -> TransitionOps.addMix(b, MixType.DISSOLVE, "c4", "c5", f(1)))
                .hasMessage("dissolve of 1.000s between c4 and c5 needs 1.000s of spare media around the cut, but "
                        + "c4 (bars.mp4) has 0.000s after its out point and c5 (testsrc.mp4) has 0.000s before its in "
                        + "point. Free media first: clip_trim(c5, in=1.000) keeps the clips touching.");
    }

    @Test
    void suggestedTrimMakesTheMixPossible() {
        Project base = Fixtures.withMedia();
        Project a = TimelineOps.insert(base, "m1", "V1", 0, null, null, false).project();
        Project b = TimelineOps.insert(a, "m2", "V1", f(6), null, null, false).project();
        Project trimmed = TimelineOps.trim(b, "c5", f(1), null).project();
        assertThat(TransitionOps.addMix(trimmed, MixType.DISSOLVE, "c4", "c5", f(1)).project().transitions()).hasSize(1);
    }

    @Test
    void itemsMustTouch() {
        Project moved = TimelineOps.move(p, "c6", null, f(6)).project();
        assertThatThrownBy(() -> TransitionOps.addMix(moved, MixType.DISSOLVE, "c4", "c6", f(1)))
                .hasMessage("c4 (bars.mp4) ends at 5.000 and c6 (testsrc.mp4) starts at 6.000 on V1: a dissolve needs "
                        + "them to touch (gap 1.000s; e.g. clip_move c6 at=5.000)");
    }

    @Test
    void missingItemBNamesTheNeighbour() {
        assertThatThrownBy(() -> TransitionOps.addMix(p, MixType.DISSOLVE, "c4", null, f(1)))
                .hasMessage("dissolve needs itemB, the item that follows c4 on V1 (that is c6)");
    }

    @Test
    void wipeIsVideoOnly() {
        assertThatThrownBy(() -> TransitionOps.addMix(p, MixType.WIPE, "c5", "c7", f(1)))
                .hasMessage("wipe is video-only; use dissolve for an audio crossfade on A1");
    }

    @Test
    void moveBreaksMixAndSaysSo() {
        Project mixed = TransitionOps.addMix(p, MixType.DISSOLVE, "c4", "c6", f(1)).project();
        EditResult r = TimelineOps.move(mixed, "c6", null, f(7));
        assertThat(r.project().transitions()).isEmpty();
        assertThat(r.notes()).containsExactly(
                "removed dissolve x8: c4 and c6 no longer touch",
                "removed dissolve x9: c5 and c7 no longer touch");
    }

    @Test
    void splitMovesEndMixToRightPart() {
        Project mixed = TransitionOps.addMix(p, MixType.DISSOLVE, "c4", "c6", f(1)).project();
        EditResult r = TimelineOps.split(mixed, "c4", f(2));
        String right = r.ids().get(1);
        String rightAudio = r.ids().get(2);
        assertThat(r.project().transitions()).extracting(t -> ((Mix) t).itemA()).containsExactly(right, rightAudio);
    }

    @Test
    void removeDropsMix() {
        Project mixed = TransitionOps.addMix(p, MixType.DISSOLVE, "c4", "c6", f(1)).project();
        assertThat(TimelineOps.remove(mixed, "c6").project().transitions()).isEmpty();
    }

    @Test
    void fadeToBlackAlsoFadesLinkedAudio() {
        EditResult r = TransitionOps.addFadeToBlack(p, "c6", f(1));
        assertThat(r.project().transitions().getFirst()).isInstanceOf(FadeToBlack.class);
        ClipItem audio = (ClipItem) r.project().track("A1").orElseThrow().items().get(1);
        assertThat(audio.audio().fadeOut()).isEqualTo(f(1));
        assertThat(r.summary()).isEqualTo("added fade_to_black x8 on c6 (testsrc.mp4), last 1.000s (9.000–10.000); "
                + "audio part c7 fades out over the same 1.000s");
    }

    @Test
    void fadeToBlackRejectsAudioItems() {
        assertThatThrownBy(() -> TransitionOps.addFadeToBlack(p, "c5", f(1)))
                .hasMessage("fade_to_black needs a video item; c5 is on audio track A1. For audio use audio_set(c5, fadeOut=...)");
    }

    @Test
    void fadeShrinksWhenItemIsTrimmed() {
        Project faded = TransitionOps.addFadeToBlack(p, "c6", f(4)).project();
        EditResult r = TimelineOps.trim(faded, "c6", f(1), f(3));
        FadeToBlack fade = (FadeToBlack) r.project().transitions().getFirst();
        assertThat(fade.length()).isEqualTo(f(2));
    }
}
