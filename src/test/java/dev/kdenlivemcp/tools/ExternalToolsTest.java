package dev.kdenlivemcp.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalToolsTest {

    @Test
    void parsesMeltQueryOutput() {
        String out = """
                ---
                producers:
                  - avformat
                  - kdenlivetitle
                  - color
                ...
                """;
        assertThat(ExternalTools.parseList(out)).containsExactly("avformat", "kdenlivetitle", "color");
    }

    @Test
    void bundleEnvironmentOnlyForAppBundlesAndExistingDirs(@TempDir Path tmp) throws Exception {
        Path contents = tmp.resolve("kdenlive.app/Contents");
        Files.createDirectories(contents.resolve("MacOS"));
        Files.createDirectories(contents.resolve("PlugIns/mlt"));
        Files.createDirectories(contents.resolve("Resources/mlt/profiles"));
        Map<String, String> env = ExternalTools.bundleEnvironment(contents.resolve("MacOS/melt"));
        if (System.getenv("MLT_REPOSITORY") == null) {
            assertThat(env).containsEntry("MLT_REPOSITORY", contents.resolve("PlugIns/mlt").toString());
        }
        if (System.getenv("MLT_DATA") == null) {
            assertThat(env).containsEntry("MLT_DATA", contents.resolve("Resources/mlt").toString());
        }
        assertThat(env).doesNotContainKey("FREI0R_PATH");
        assertThat(ExternalTools.bundleEnvironment(Path.of("/usr/bin/melt"))).isEmpty();
    }

    @Test
    void capabilitiesReportMissing() {
        MltCapabilities caps = new MltCapabilities(Map.of("producers", java.util.Set.of("avformat", "color")));
        assertThat(caps.canRenderTitles()).isFalse();
        assertThat(caps.missing(Map.of("producers", java.util.List.of("avformat", "kdenlivetitle"))))
                .containsExactly("producers/kdenlivetitle");
    }
}
