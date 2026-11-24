package dev.kdenlivemcp.backend.kdenlive;

import dev.kdenlivemcp.backend.EditorBackend;
import dev.kdenlivemcp.backend.RenderJob;
import dev.kdenlivemcp.backend.RenderPreset;
import dev.kdenlivemcp.backend.RenderSpec;
import dev.kdenlivemcp.config.KdenliveConfig;
import dev.kdenlivemcp.media.FfprobeService;
import dev.kdenlivemcp.media.MediaException;
import dev.kdenlivemcp.media.MediaInfo;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.MediaClip;
import dev.kdenlivemcp.model.Profile;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.title.TitlePresets;
import dev.kdenlivemcp.tools.Binary;
import dev.kdenlivemcp.tools.ExternalTools;
import dev.kdenlivemcp.tools.ProcessRunner;
import dev.kdenlivemcp.tools.ResolvedBinary;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** .kdenlive files in and out, rendering and previews with melt. */
@ApplicationScoped
public class KdenliveXmlBackend implements EditorBackend {

    private static final Logger LOG = Logger.getLogger(KdenliveXmlBackend.class);

    private final ExternalTools tools;
    private final FfprobeService ffprobe;
    private final KdenliveConfig config;
    private final TitlePresets presets;
    private final Map<String, MeltRenderJob> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger jobCounter = new AtomicInteger();
    private volatile Optional<String> titleProblem;

    public KdenliveXmlBackend(ExternalTools tools, FfprobeService ffprobe, KdenliveConfig config, TitlePresets presets) {
        this.tools = tools;
        this.ffprobe = ffprobe;
        this.config = config;
        this.presets = presets;
    }

    @Override
    public String name() {
        return "kdenlive-xml";
    }

    /** Never leave melt processes behind when the server stops. */
    @jakarta.annotation.PreDestroy
    void shutdown() {
        jobs.values().forEach(RenderJob::cancel);
    }

    // ------------------------------------------------------------------ files

    @Override
    public Project load(Path file) {
        Project p = KdenliveReader.read(file);
        List<MediaClip> refreshed = new ArrayList<>();
        for (MediaClip m : p.bin()) {
            MediaClip clip = m;
            if (Files.isRegularFile(m.path())) {
                try {
                    MediaInfo info = ffprobe.probe(m.path());
                    clip = new MediaClip(m.id(), m.binId(), m.path(), m.name(), info, m.extras());
                } catch (RuntimeException e) {
                    LOG.warnf("cannot probe %s, using stored clip info: %s", m.path(), e.getMessage());
                }
            }
            refreshed.add(clip);
        }
        return new Project(p.file(), p.profile(), refreshed, p.tracks(), p.transitions(), p.nextId(), p.meta());
    }

    @Override
    public void save(Project project, Path file) {
        Project target = project.withFile(file.toAbsolutePath().normalize());
        KdenliveWriter.Output out = KdenliveWriter.write(target, presets);
        out.titleFiles().forEach(Xml::writeAtomically);
        Xml.writeAtomically(target.file(), out.xml());
    }

    /** Media files referenced by the project that are missing on disk. */
    public List<Path> missingMedia(Project p) {
        return p.bin().stream().map(MediaClip::path).filter(f -> !Files.isRegularFile(f)).toList();
    }

    // ------------------------------------------------------------------ render

    @Override
    public RenderJob render(Project project, RenderSpec spec) {
        if (project.duration() == 0) {
            throw new EditException("the timeline is empty: nothing to render");
        }
        List<Path> missing = missingMedia(project);
        if (!missing.isEmpty()) {
            throw new EditException("cannot render, media missing: " + missing);
        }
        Optional<MeltRenderJob> running = jobs.values().stream()
                .filter(j -> !j.status().finished()).findFirst();
        if (running.isPresent()) {
            throw new EditException("render " + running.get().id() + " is still running ("
                    + running.get().status().message() + "); wait for it or call render_cancel");
        }
        Path output = spec.output().toAbsolutePath().normalize();
        String name = output.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".mp4") && !name.endsWith(".mov") && !name.endsWith(".mkv")) {
            throw new EditException("outputPath must end in .mp4, .mov or .mkv: " + output);
        }
        if (!Files.isDirectory(output.getParent())) {
            throw new EditException("output folder does not exist: " + output.getParent());
        }
        int[] size = outputSize(project.profile(), spec.preset());
        ResolvedBinary melt = tools.require(Binary.MELT);
        String id = "r" + jobCounter.incrementAndGet();
        Path dir = workDir("render-" + id);
        Path snapshot = writeSnapshot(project, dir, null, size);
        Path temp = output.resolveSibling("." + output.getFileName() + ".rendering" + extension(output));
        List<String> cmd = new ArrayList<>(List.of(melt.path().toString(), snapshot.toString(), "-progress",
                "-consumer", "avformat:" + temp, "real_time=-1"));
        cmd.addAll(spec.preset().consumerArgs);
        if (name.endsWith(".mp4") || name.endsWith(".mov")) {
            cmd.add("movflags=+faststart");
        }
        long seconds = Math.round(project.fps().seconds(project.duration()));
        Duration timeout = Duration.ofSeconds(Math.max(config.renderMinTimeout(), seconds * config.renderTimeoutFactor()));
        Path log = logDir().resolve("render-" + id + ".log");
        double expected = project.fps().seconds(project.duration());
        MeltRenderJob job = new MeltRenderJob(id, cmd, meltEnv(melt), temp, output, log, project.duration(), timeout,
                file -> describeOutput(file, expected));
        jobs.put(id, job);
        job.start();
        return job;
    }

    public Optional<RenderJob> job(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<String> jobIds() {
        return List.copyOf(jobs.keySet());
    }

    private String describeOutput(Path file, double expectedSeconds) {
        MediaInfo info;
        try {
            info = ffprobe.probe(file);
        } catch (MediaException e) {
            throw new IllegalStateException("render finished but the file is unreadable: " + e.getMessage());
        }
        String d = String.format(Locale.ROOT, "%.3fs (timeline %.3fs), %dx%d, video %s, audio %s",
                info.durationSeconds(), expectedSeconds, info.width(), info.height(), info.videoCodec(),
                info.hasAudio() ? info.audioCodec() : "none");
        return d;
    }

    static int[] outputSize(Profile profile, RenderPreset preset) {
        int w = profile.width();
        int h = profile.height();
        if (preset == RenderPreset.VERTICAL_1080P) {
            if (!profile.vertical()) {
                throw new EditException("vertical_1080p needs a vertical project (" + w + "x" + h + " is landscape); "
                        + "use final_1080p, or create the project with preset vertical_1080x1920_30");
            }
            return new int[] {1080, 1920};
        }
        int shortSide = preset == RenderPreset.DRAFT_540P ? 540 : 1080;
        if (Math.min(w, h) <= shortSide) {
            return new int[] {even(w), even(h)};
        }
        return profile.vertical() ? new int[] {shortSide, even((double) h * shortSide / w)}
                : new int[] {even((double) w * shortSide / h), shortSide};
    }

    private static int even(double v) {
        return (int) Math.round(v / 2) * 2;
    }

    // ------------------------------------------------------------------ previews

    /** One timeline frame as PNG, at most maxWidth wide, optionally with a text label burned in. */
    public byte[] grabFrame(Project project, long frame, int maxWidth, String label) {
        if (frame < 0 || frame >= Math.max(1, project.duration())) {
            throw new EditException("t=" + project.fps().sec(frame) + " is outside the timeline (0.000–"
                    + project.fps().sec(Math.max(0, project.duration() - 1)) + ")");
        }
        ResolvedBinary melt = tools.require(Binary.MELT);
        Path dir = workDir("frame-" + UUID.randomUUID());
        try {
            Path png = dir.resolve("frame.png");
            int w = even(Math.min(maxWidth, project.profile().width()));
            int h = even((double) project.profile().height() * w / project.profile().width());
            Path snapshot = writeSnapshot(project, dir, new long[] {frame, frame}, new int[] {w, h});
            List<String> cmd = new ArrayList<>(List.of(melt.path().toString(), "-quiet", snapshot.toString()));
            if (label != null && tools.mltCapabilities().has("filters", "dynamictext")) {
                int size = Math.max(14, h / 18);
                cmd.addAll(List.of("-filter", "dynamictext", "argument=" + label, "geometry=0%/86%:100%x14%",
                        "halign=right", "valign=bottom", "size=" + size, "fgcolour=0xffffffff",
                        "bgcolour=0x000000aa", "pad=" + Math.max(4, size / 4)));
            }
            // real_time=-1: never drop the (only) frame when the machine is busy
            cmd.addAll(List.of("-consumer", "avformat:" + png, "vcodec=png", "update=1", "real_time=-1"));
            ProcessRunner.Result r = null;
            // melt occasionally exits 0 without writing anything under parallel load (seen ~1/40 with 7.43): retry
            for (int attempt = 1; attempt <= 3; attempt++) {
                r = ProcessRunner.run(cmd, Duration.ofSeconds(config.frameTimeout()), meltEnv(melt), dir);
                if (r.ok() && Files.isRegularFile(png)) {
                    break;
                }
                LOG.debugf("frame %d attempt %d: exit %d, png %s", frame, attempt, r.exitCode(), Files.exists(png));
            }
            if (!r.ok() || !Files.isRegularFile(png)) {
                throw new EditException("melt could not render frame " + frame + (r.timedOut() ? " (timed out)" : "")
                        + " (exit " + r.exitCode() + "): " + (r.stderrTail(5) + "\n" + ProcessRunner.tail(r.stdout(), 5)).strip());
            }
            return Files.readAllBytes(png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteTree(dir);
        }
    }

    @Override
    public byte[] grabFrame(Project project, long frame, int maxWidth) {
        return grabFrame(project, frame, maxWidth, null);
    }

    /** {@code count} frames between start and end (inclusive) tiled into one PNG, 4 per row, times burned in. */
    public byte[] contactSheet(Project project, long start, long end, int count, int tileWidth) {
        ResolvedBinary ffmpeg = tools.require(Binary.FFMPEG);
        long[] frames = new long[count];
        for (int i = 0; i < count; i++) {
            frames[i] = count == 1 ? start : start + Math.round((double) (end - start) * i / (count - 1));
        }
        Path dir = workDir("sheet-" + UUID.randomUUID());
        try {
            byte[][] images = new byte[count][];
            java.util.concurrent.Semaphore limit = new java.util.concurrent.Semaphore(Math.max(1, config.previewParallelism()));
            List<Thread> threads = new ArrayList<>();
            List<RuntimeException> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
            for (int i = 0; i < count; i++) {
                final int idx = i;
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        limit.acquire();
                        try {
                            images[idx] = grabFrame(project, frames[idx], tileWidth,
                                    project.fps().sec(frames[idx]) + "s");
                        } finally {
                            limit.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        errors.add(e);
                    }
                }));
            }
            for (Thread t : threads) {
                t.join();
            }
            if (!errors.isEmpty()) {
                throw errors.getFirst();
            }
            return dev.kdenlivemcp.media.ImageTiler.tile(ffmpeg, dir, java.util.Arrays.asList(images));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EditException("interrupted");
        } finally {
            deleteTree(dir);
        }
    }

    // ------------------------------------------------------------------ melt environment

    /**
     * Problem rendering titles with this melt, if any. melt's qt module (kdenlivetitle) needs a Qt platform;
     * MLT ≥ 7.30 accepts QT_QPA_PLATFORM=offscreen, older builds insist on an X11 display.
     */
    public Optional<String> titleRenderingProblem() {
        Optional<String> cached = titleProblem;
        if (cached != null) {
            return cached;
        }
        Optional<String> result;
        Optional<ResolvedBinary> melt = tools.find(Binary.MELT);
        if (melt.isEmpty()) {
            result = Optional.of("melt not found");
        } else if (!tools.mltCapabilities().canRenderTitles()) {
            result = Optional.of("this melt has no kdenlivetitle producer (MLT qt module missing): titles will not "
                    + "render. Use the melt bundled with Kdenlive (set MELT_PATH)");
        } else {
            Path dir = workDir("titlecheck");
            try {
                Path title = dir.resolve("check.kdenlivetitle");
                // a 1-frame title rendered offscreen: old MLT builds print "requires a X11 environment"
                Files.writeString(title, TitleXml.build("check", presets.get(presets.ids().iterator().next()),
                        new Profile("check", "check", 320, 180, dev.kdenlivemcp.model.Fps.of(25), 1, 1, 16, 9, 709, true), 1));
                ProcessRunner.Result r = ProcessRunner.run(List.of(melt.get().path().toString(), "kdenlivetitle:" + title,
                        "out=0", "-consumer", "null"), Duration.ofSeconds(30), meltEnv(melt.get()), dir);
                String all = r.stdout() + r.stderr();
                result = all.contains("X11") || all.contains("INVALID")
                        ? Optional.of("this melt's Qt module needs an X11 display and none is available (titles would "
                        + "render as 'INVALID'). Use Kdenlive's bundled melt (MLT >= 7.30) via MELT_PATH, or run "
                        + "with a display (e.g. xvfb-run)")
                        : Optional.empty();
            } catch (IOException e) {
                result = Optional.of("cannot check title rendering: " + e.getMessage());
            } finally {
                deleteTree(dir);
            }
        }
        titleProblem = result;
        return result;
    }

    Map<String, String> meltEnv(ResolvedBinary melt) {
        Map<String, String> env = new LinkedHashMap<>(melt.env());
        boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
        if (linux && System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null
                && System.getenv("QT_QPA_PLATFORM") == null) {
            env.put("QT_QPA_PLATFORM", "offscreen");
        }
        env.putIfAbsent("LC_ALL", "C.UTF-8");
        return env;
    }

    // ------------------------------------------------------------------ helpers

    /** Writes the project (and its title files) into {@code dir} for melt; media stay where they are. */
    private Path writeSnapshot(Project project, Path dir, long[] range, int[] size) {
        Path snap = dir.resolve("snapshot.kdenlive");
        KdenliveWriter.Output out = KdenliveWriter.write(project.withFile(snap), presets, range, size);
        out.titleFiles().forEach(Xml::writeAtomically);
        Xml.writeAtomically(snap, out.xml());
        return snap;
    }

    private Path workDir(String name) {
        try {
            return Files.createDirectories(Path.of(expand(config.workDir())).toAbsolutePath().resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create work dir " + config.workDir() + ": " + e.getMessage(), e);
        }
    }

    private Path logDir() {
        try {
            return Files.createDirectories(Path.of(expand(config.logDir())).toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String expand(String p) {
        return p.startsWith("~/") ? System.getProperty("user.home") + p.substring(1) : p;
    }

    private static String extension(Path p) {
        String n = p.getFileName().toString();
        return n.substring(n.lastIndexOf('.'));
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // already gone
        }
    }
}
