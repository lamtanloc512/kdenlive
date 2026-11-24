package dev.kdenlivemcp.assets;

import dev.kdenlivemcp.model.EditException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParamCodecTest {

    static final AssetCatalog CATALOG = AssetCatalog.load();
    static final ParamCodec.Context CTX = new ParamCodec.Context(1920, 1080, 30, 150, 1280, 720,
            f -> String.format("%02d:%02d:%02d.%03d", 0, 0, f / 30, (f % 30) * 1000 / 30));

    static AssetParam param(String effect, String name) {
        return CATALOG.effect(effect).orElseThrow().param(name).orElseThrow();
    }

    @Test
    void numbersUseKdenlivesUiUnits() {
        AssetParam level = param("brightness", "level");
        assertThat(ParamCodec.toRaw(level, 50, CTX)).isEqualTo("0.5");
        assertThat(ParamCodec.toUi(level, "0.5")).isEqualTo(50.0);
        assertThatThrownBy(() -> ParamCodec.toRaw(level, 500, CTX)).isInstanceOf(EditException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void listsAcceptValuesOrLabels() {
        AssetParam planes = param("avfilter.gblur", "av.planes");
        assertThat(ParamCodec.toRaw(planes, "YUV", CTX)).isEqualTo("7");
        assertThat(ParamCodec.toRaw(planes, 1, CTX)).isEqualTo("1");
        assertThatThrownBy(() -> ParamCodec.toRaw(planes, "nope", CTX)).hasMessageContaining("YUV");
    }

    @Test
    void colorsBecomeKdenlivesHexForm() {
        AssetParam color = param("dropshadow", "color");
        assertThat(ParamCodec.toRaw(color, "#ff8800", CTX)).isEqualTo("0xff8800ff");
        assertThat(ParamCodec.toRaw(color, "#ff880080", CTX)).isEqualTo("0xff880080");
        assertThat(ParamCodec.toRaw(color, "white", CTX)).isEqualTo("0xffffffff");
        assertThat(ParamCodec.toUi(color, "0xff8800ff")).isEqualTo("#ff8800ff");
    }

    @Test
    void rectsFromObjectsStringsOrLists() {
        AssetParam rect = param("qtblend", "rect");
        assertThat(ParamCodec.toRaw(rect, Map.of("x", 10, "y", 20, "w", 640, "h", 360), CTX)).isEqualTo("10 20 640 360 1");
        assertThat(ParamCodec.toRaw(rect, "0 0 1920 1080 0.5", CTX)).isEqualTo("0 0 1920 1080 0.5");
        assertThat(ParamCodec.toRaw(rect, List.of(1, 2, 3, 4), CTX)).isEqualTo("1 2 3 4 1");
    }

    @Test
    void defaultsResolvePlaceholdersAndAnchorAnimatedValues() {
        assertThat(ParamCodec.defaultRaw(param("qtblend", "rect"), CTX)).isEqualTo("00:00:01.000=0 0 1920 1080 1");
        assertThat(ParamCodec.defaultRaw(param("brightness", "level"), CTX)).isEqualTo("00:00:01.000=1");
    }

    @Test
    void keyframesRoundTripWithEases() {
        List<ParamCodec.Key> keys = List.of(new ParamCodec.Key(30, "linear", "0"),
                new ParamCodec.Key(60, "ease_in_out", "1"), new ParamCodec.Key(90, "discrete", "0.5"));
        String anim = ParamCodec.encode(keys, CTX.clock());
        assertThat(anim).isEqualTo("00:00:01.000=0;00:00:02.000i=1;00:00:03.000|=0.5");
        assertThat(ParamCodec.decode(anim, s -> Math.round(Double.parseDouble(s.substring(6)) * 30)))
                .extracting(ParamCodec.Key::frame, ParamCodec.Key::ease, ParamCodec.Key::raw)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(30L, "linear", "0"),
                        org.assertj.core.groups.Tuple.tuple(60L, "cubic_in_out", "1"),
                        org.assertj.core.groups.Tuple.tuple(90L, "discrete", "0.5"));
        assertThatThrownBy(() -> ParamCodec.easeChar("wobbly")).hasMessageContaining("bounce_in");
    }

    @Test
    void arithmeticInDefaults() {
        assertThat(ParamCodec.Expr.eval("1920 / 2")).isEqualTo(960.0);
        assertThat(ParamCodec.Expr.eval("-(2+3)*4")).isEqualTo(-20.0);
        assertThat(ParamCodec.Expr.eval("abc")).isNull();
    }
}
