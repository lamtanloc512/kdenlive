package dev.kdenlivemcp.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FpsTest {

    @Test
    void parsesRationalsAndDecimals() {
        assertThat(Fps.parse("30/1")).isEqualTo(Fps.of(30));
        assertThat(Fps.parse("60000/2000")).isEqualTo(Fps.of(30));
        assertThat(Fps.parse("30000/1001")).isEqualTo(new Fps(30000, 1001));
        assertThat(Fps.parse("29.97")).isEqualTo(new Fps(30000, 1001));
        assertThat(Fps.parse("23.976")).isEqualTo(new Fps(24000, 1001));
        assertThat(Fps.parse("25")).isEqualTo(Fps.of(25));
        assertThat(Fps.parse("0/0")).isNull();
        assertThat(Fps.parse("abc")).isNull();
    }

    @Test
    void convertsSecondsAndFrames() {
        Fps f30 = Fps.of(30);
        assertThat(f30.frames(1.0)).isEqualTo(30);
        assertThat(f30.frames(1.016)).isEqualTo(30); // snaps to nearest
        assertThat(f30.frames(1.017)).isEqualTo(31);
        assertThat(f30.sec(45)).isEqualTo("1.500");
        Fps ntsc = new Fps(30000, 1001);
        assertThat(ntsc.frames(10.0)).isEqualTo(300); // 299.7 -> 300
        assertThat(ntsc.framesFloor(10.0)).isEqualTo(299);
        assertThat(ntsc.sec(30)).isEqualTo("1.001");
        assertThat(Fps.of(25).frames(5.0)).isEqualTo(125);
    }

    @Test
    void labels() {
        assertThat(Fps.of(30).label()).isEqualTo("30");
        assertThat(new Fps(30000, 1001).label()).isEqualTo("29.97");
        assertThat(new Fps(24000, 1001).label()).isEqualTo("23.976");
    }
}
