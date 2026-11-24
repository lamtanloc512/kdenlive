package dev.kdenlivemcp.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/** Settings from application.properties / env (MELT_PATH, FFPROBE_PATH, FFMPEG_PATH, WORK_DIR). */
@ConfigMapping(prefix = "kdenlive")
public interface KdenliveConfig {

    Optional<String> meltPath();

    Optional<String> ffprobePath();

    Optional<String> ffmpegPath();

    String workDir();

    String logDir();

    @WithDefault("30")
    int probeTimeout();

    @WithDefault("30")
    int frameTimeout();

    @WithDefault("600")
    int renderMinTimeout();

    @WithDefault("5")
    int renderTimeoutFactor();

    @WithDefault("200")
    int undoDepth();

    Optional<String> titlePresets();

    @WithDefault("4")
    int previewParallelism();
}
