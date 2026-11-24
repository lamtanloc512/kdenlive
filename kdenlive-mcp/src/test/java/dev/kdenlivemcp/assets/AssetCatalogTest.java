package dev.kdenlivemcp.assets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetCatalogTest {

    static final AssetCatalog CATALOG = AssetCatalog.load();

    @Test
    void loadsKdenlivesWholeCatalog() {
        assertThat(CATALOG.effects()).hasSizeGreaterThan(400);
        assertThat(CATALOG.transitions()).hasSizeGreaterThan(30);
    }

    @Test
    void picksTheNewestVersionOfAnEffect() {
        AssetDef transform = CATALOG.effect("qtblend").orElseThrow();
        assertThat(transform.version()).isEqualTo(3);
        assertThat(transform.params()).extracting(AssetParam::name)
                .containsExactly("compositing", "distort", "rect", "rotate_anchor", "rotation");
        assertThat(transform.category()).isEqualTo("Transform, Distort, and Perspective");
    }

    @Test
    void readsUnitsRangesAndChoices() {
        AssetParam level = CATALOG.effect("brightness").orElseThrow().param("level").orElseThrow();
        assertThat(level.factor()).isEqualTo(100);
        assertThat(level.max()).isEqualTo(400);
        assertThat(level.animated()).isTrue();

        AssetParam planes = CATALOG.effect("avfilter.gblur").orElseThrow().param("av.planes").orElseThrow();
        assertThat(planes.kind()).isEqualTo(AssetParam.Kind.LIST);
        assertThat(planes.values()).contains("7");
        assertThat(planes.labels()).contains("YUV");
    }

    @Test
    void idDefaultsToTheMltServiceAndAudioIsTagged() {
        AssetDef eq = CATALOG.effect("avfilter.eq").orElseThrow();
        assertThat(eq.service()).isEqualTo("avfilter.eq");
        assertThat(CATALOG.effect("avfilter.aphaser").orElseThrow().audio()).isTrue();
    }

    @Test
    void skipsWhatKdenliveExcludes() {
        // Kdenlive never registers excluded MLT services (excluded_effects.txt), even when an XML exists
        assertThat(CATALOG.effect("avfilter.aecho")).isEmpty();
        assertThat(CATALOG.effect("avfilter.acompressor")).isEmpty();
    }

    @Test
    void searchAndSuggestions() {
        assertThat(CATALOG.searchEffects("gaussian blur", d -> true)).extracting(AssetDef::id)
                .contains("avfilter.gblur");
        assertThat(CATALOG.suggestEffects("gblur", 3)).contains("avfilter.gblur");
    }
}
