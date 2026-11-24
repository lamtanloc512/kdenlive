package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TitleItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.kdenlivemcp.edit.Fixtures.f;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioAndTitleOpsTest {

    private final Project base = TimelineOps.insert(Fixtures.withMedia(), "m1", "V1", 0, null, null, true).project();
    private static final List<String> STYLES = List.of("lower_third", "big_center");

    @Test
    void audioSetOnVideoItemGoesToLinkedAudio() {
        EditResult r = AudioOps.set(base, "c4", -6.0, f(0.5), f(1));
        ClipItem audio = (ClipItem) r.project().track("A1").orElseThrow().items().getFirst();
        assertThat(audio.audio().volumeDb()).isEqualTo(-6.0);
        assertThat(audio.audio().fadeIn()).isEqualTo(f(0.5));
        assertThat(r.summary()).isEqualTo("audio of c5 (audio part of c4): volume -6.0 dB, fade in 0.500s, fade out 1.000s");
    }

    @Test
    void audioSetKeepsUnspecifiedValues() {
        Project p = AudioOps.set(base, "c5", -3.0, null, null).project();
        Project p2 = AudioOps.set(p, "c5", null, f(1), null).project();
        ClipItem audio = (ClipItem) p2.track("A1").orElseThrow().items().getFirst();
        assertThat(audio.audio().volumeDb()).isEqualTo(-3.0);
        assertThat(audio.audio().fadeIn()).isEqualTo(f(1));
    }

    @Test
    void audioSetValidates() {
        assertThatThrownBy(() -> AudioOps.set(base, "c5", -100.0, null, null))
                .hasMessage("volumeDb=-100.0 is out of range; use -60 to +24 dB (0 = unchanged, -6 = about half as loud)");
        assertThatThrownBy(() -> AudioOps.set(base, "c5", null, f(4), f(4)))
                .hasMessage("fadeIn 4.000s + fadeOut 4.000s exceed the length of c5 (6.000s)");
        Project noAudio = TimelineOps.insert(Fixtures.withMedia(), "m1", "V1", 0, null, null, false).project();
        assertThatThrownBy(() -> AudioOps.set(noAudio, "c4", 0.0, null, null))
                .hasMessage("c4 (bars.mp4) has no audio part on the timeline (it was inserted with withAudio=false)");
    }

    @Test
    void titleAdd() {
        EditResult r = TitleOps.add(base, "Hello world", f(1), f(3), "V2", "lower_third", STYLES);
        TitleItem t = (TitleItem) r.project().track("V2").orElseThrow().items().getFirst();
        assertThat(t.start()).isEqualTo(f(1));
        assertThat(t.length()).isEqualTo(f(3));
        assertThat(r.summary()).isEqualTo("added title t6 \"Hello world\" (lower_third) on V2 1.000–4.000");
    }

    @Test
    void titleValidation() {
        assertThatThrownBy(() -> TitleOps.add(base, "Hi", 0, f(3), "V2", "comic_sans", STYLES))
                .hasMessage("unknown title style 'comic_sans'; styles: lower_third, big_center");
        assertThatThrownBy(() -> TitleOps.add(base, "Hi", 0, f(3), "A1", "lower_third", STYLES))
                .hasMessage("titles go on video tracks; A1 is an audio track. Video tracks: V2, V1");
        assertThatThrownBy(() -> TitleOps.add(base, " ", 0, f(3), "V2", "lower_third", STYLES))
                .hasMessage("text must not be empty");
    }

    @Test
    void titleTrimAndSplit() {
        Project p = TitleOps.add(base, "Hi", f(1), f(3), "V2", "lower_third", STYLES).project();
        Project trimmed = TimelineOps.trim(p, "t6", null, f(2)).project();
        assertThat(trimmed.track("V2").orElseThrow().items().getFirst().length()).isEqualTo(f(2));
        assertThatThrownBy(() -> TimelineOps.trim(p, "t6", f(1), null)).hasMessageStartingWith("t6 is a title");
        Project split = TimelineOps.split(p, "t6", f(2)).project();
        assertThat(split.track("V2").orElseThrow().items()).hasSize(2);
    }

    @Test
    void dissolveBetweenTitlesNeedsNoHandles() {
        Project p = TitleOps.add(base, "A", 0, f(3), "V2", "lower_third", STYLES).project();
        Project p2 = TitleOps.add(p, "B", f(3), f(3), "V2", "lower_third", STYLES).project();
        assertThat(TransitionOps.addMix(p2, dev.kdenlivemcp.model.MixType.DISSOLVE, "t6", "t7", f(1))
                .project().transitions()).hasSize(1);
    }
}
