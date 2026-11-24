package dev.kdenlivemcp.backend.kdenlive;

import dev.kdenlivemcp.model.Effect;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TimelineItem;
import dev.kdenlivemcp.title.TitlePresets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Projects with effects written by Kdenlive itself (Kdenlive's own test dataset, available when this repo sits in
 * the Kdenlive tree): effects become editable Effect objects and are written back property for property.
 */
class UpstreamDatasetRoundTripTest {

    static final Path DATASET = Path.of("../tests/dataset");

    @ParameterizedTest
    @ValueSource(strings = {"test-keyframes.kdenlive"})
    void effectsAreModelledAndWrittenBackUnchanged(String name) {
        Path file = DATASET.resolve(name);
        assumeTrue(Files.isRegularFile(file), "Kdenlive test dataset not present");
        Project p = KdenliveReader.read(file);
        List<Effect> all = p.tracks().stream().flatMap(t -> t.items().stream())
                .flatMap(i -> i.effects().stream()).toList();
        assertThat(all).isNotEmpty();
        assertThat(all).extracting(Effect::assetId).contains("brightness");

        String written = KdenliveWriter.write(p, TitlePresets.load(null)).xml();
        assertThat(SemanticSummary.of(written)).containsExactlyElementsOf(SemanticSummary.of(file));
        Project again = KdenliveReader.read(file, written);
        for (int k = 0; k < p.tracks().size(); k++) { // passthrough filter ids are renumbered, compare the rest
            assertThat(again.tracks().get(k).items()).isEqualTo(p.tracks().get(k).items());
            assertThat(again.tracks().get(k).effects()).isEqualTo(p.tracks().get(k).effects());
        }
        assertThat(p.tracks().stream().flatMap(t -> t.effects().stream()).map(Effect::assetId)).contains("brightness");
        assertThat(again.masterEffects()).isEqualTo(p.masterEffects());
        for (TimelineItem i : p.tracks().stream().flatMap(t -> t.items().stream()).toList()) {
            for (Effect fx : i.effects()) {
                assertThat(written).contains("<property name=\"" + fx.props().keySet().iterator().next() + "\">");
            }
        }
    }
}
