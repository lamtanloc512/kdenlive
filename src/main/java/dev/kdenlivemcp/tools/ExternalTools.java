package dev.kdenlivemcp.tools;

import dev.kdenlivemcp.config.KdenliveConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds melt / ffprobe / ffmpeg and checks what the installed melt can do.
 * <p>
 * Lookup order per binary: explicit config (env var or property) &gt; kdenlive.app bundle (macOS) &gt;
 * Homebrew / /usr/local &gt; system dirs &gt; PATH. Results are cached for the life of the server.
 */
@ApplicationScoped
public class ExternalTools {

    private static final Logger LOG = Logger.getLogger(ExternalTools.class);
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*-\\s+(\\S+)\\s*$");

    /** MLT services we rely on, grouped by the -query kind. */
    public static final Map<String, List<String>> REQUIRED_MLT = Map.of(
            "producers", List.of("avformat", "color", "kdenlivetitle"),
            "transitions", List.of("luma", "mix", "qtblend"),
            "filters", List.of("volume", "brightness"));

    private final KdenliveConfig config;
    private final Map<Binary, Optional<ResolvedBinary>> cache = new EnumMap<>(Binary.class);
    private volatile MltCapabilities capabilities;

    public ExternalTools(KdenliveConfig config) {
        this.config = config;
    }

    public synchronized Optional<ResolvedBinary> find(Binary binary) {
        return cache.computeIfAbsent(binary, this::resolve);
    }

    public ResolvedBinary require(Binary binary) {
        return find(binary).orElseThrow(() -> new ToolMissingException(
                binary.exe + " not found. Install it (macOS: Kdenlive.app bundles melt; `brew install ffmpeg mlt`; "
                        + "Linux: `apt install melt ffmpeg`) or set " + binary.envVar + " to its full path."));
    }

    /** Services available in the resolved melt (queried once). */
    public MltCapabilities mltCapabilities() {
        MltCapabilities c = capabilities;
        if (c == null) {
            synchronized (this) {
                if (capabilities == null) {
                    capabilities = queryCapabilities();
                }
                c = capabilities;
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ resolution

    private Optional<ResolvedBinary> resolve(Binary binary) {
        Optional<String> configured = switch (binary) {
            case MELT -> config.meltPath();
            case FFPROBE -> config.ffprobePath();
            case FFMPEG -> config.ffmpegPath();
        };
        if (configured.isPresent() && !configured.get().isBlank()) {
            Path p = Path.of(expandHome(configured.get().trim()));
            if (!Files.isExecutable(p)) {
                LOG.errorf("%s=%s is not an executable file; falling back to auto-detection", binary.envVar, p);
            } else {
                return probe(binary, p, "config");
            }
        }
        for (Candidate c : candidates(binary)) {
            if (Files.isExecutable(c.path)) {
                Optional<ResolvedBinary> r = probe(binary, c.path, c.source);
                if (r.isPresent()) {
                    return r;
                }
            }
        }
        LOG.warnf("%s not found (set %s)", binary.exe, binary.envVar);
        return Optional.empty();
    }

    private record Candidate(Path path, String source) {
    }

    private static List<Candidate> candidates(Binary binary) {
        List<Candidate> list = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (isMac()) {
            for (String app : List.of("/Applications/kdenlive.app", "/Applications/Kdenlive.app",
                    home + "/Applications/kdenlive.app", home + "/Applications/Kdenlive.app")) {
                list.add(new Candidate(Path.of(app, "Contents", "MacOS", binary.exe), "kdenlive.app"));
            }
            list.add(new Candidate(Path.of("/opt/homebrew/bin", binary.exe), "homebrew"));
        }
        list.add(new Candidate(Path.of("/usr/local/bin", binary.exe), "/usr/local/bin"));
        list.add(new Candidate(Path.of("/usr/bin", binary.exe), "/usr/bin"));
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) {
                    list.add(new Candidate(Path.of(dir, binary.exe), "PATH"));
                }
            }
        }
        // de-duplicate while keeping order
        Set<Path> seen = new LinkedHashSet<>();
        return list.stream().filter(c -> seen.add(c.path.toAbsolutePath().normalize())).toList();
    }

    private Optional<ResolvedBinary> probe(Binary binary, Path path, String source) {
        Map<String, String> env = binary == Binary.MELT ? bundleEnvironment(path) : Map.of();
        List<String> cmd = List.of(path.toString(), "-version");
        try {
            ProcessRunner.Result r = ProcessRunner.run(cmd, Duration.ofSeconds(15), env, null);
            String text = (r.stdout() + "\n" + r.stderr()).strip();
            String first = text.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
            if (r.timedOut() || first.isEmpty()) {
                LOG.warnf("%s at %s did not answer -version (exit %d)", binary.exe, path, r.exitCode());
                return Optional.empty();
            }
            LOG.infof("using %s: %s (%s) - %s", binary.exe, path, source, first);
            return Optional.of(new ResolvedBinary(binary, path, source, first, env));
        } catch (RuntimeException e) {
            LOG.warnf("cannot run %s at %s: %s", binary.exe, path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The melt inside kdenlive.app needs to be told where its modules live. We only set variables the user
     * has not set and only for directories that exist; the exact bundle layout differs between releases.
     */
    static Map<String, String> bundleEnvironment(Path melt) {
        Map<String, String> env = new LinkedHashMap<>();
        String s = melt.toAbsolutePath().toString();
        int idx = s.indexOf(".app/Contents/MacOS/");
        if (idx < 0) {
            return env;
        }
        Path contents = Path.of(s.substring(0, idx + ".app/Contents".length()));
        putFirstExisting(env, "MLT_REPOSITORY", contents,
                "PlugIns/mlt", "Frameworks/mlt", "Frameworks/lib/mlt-7", "lib/mlt-7", "Resources/lib/mlt-7");
        putFirstExisting(env, "MLT_DATA", contents,
                "Resources/mlt", "Resources/share/mlt-7", "share/mlt-7", "Resources/mlt-7");
        if (env.containsKey("MLT_DATA") && System.getenv("MLT_PROFILES_PATH") == null) {
            Path profiles = Path.of(env.get("MLT_DATA"), "profiles");
            if (Files.isDirectory(profiles)) {
                env.put("MLT_PROFILES_PATH", profiles.toString());
            }
        }
        putFirstExisting(env, "FREI0R_PATH", contents,
                "PlugIns/frei0r-1", "Frameworks/frei0r-1", "lib/frei0r-1", "Resources/lib/frei0r-1");
        return env;
    }

    private static void putFirstExisting(Map<String, String> env, String var, Path base, String... rels) {
        if (System.getenv(var) != null) {
            return;
        }
        for (String rel : rels) {
            Path p = base.resolve(rel);
            if (Files.isDirectory(p)) {
                env.put(var, p.toString());
                return;
            }
        }
    }

    // ------------------------------------------------------------------ capabilities

    private MltCapabilities queryCapabilities() {
        Optional<ResolvedBinary> melt = find(Binary.MELT);
        if (melt.isEmpty()) {
            return MltCapabilities.none();
        }
        Map<String, Set<String>> services = new LinkedHashMap<>();
        for (String kind : List.of("producers", "transitions", "filters", "consumers")) {
            ProcessRunner.Result r = ProcessRunner.run(
                    List.of(melt.get().path().toString(), "-query", kind),
                    Duration.ofSeconds(30), melt.get().env(), null);
            services.put(kind, parseList(r.stdout() + "\n" + r.stderr()));
        }
        MltCapabilities caps = new MltCapabilities(services);
        List<String> missing = caps.missing(REQUIRED_MLT);
        if (missing.isEmpty()) {
            LOG.info("melt has every MLT service we need");
        } else {
            LOG.warnf("melt is missing MLT services: %s", missing);
        }
        return caps;
    }

    static Set<String> parseList(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            Matcher m = LIST_ITEM.matcher(line);
            if (m.matches()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String expandHome(String p) {
        return p.startsWith("~/") ? System.getProperty("user.home") + p.substring(1) : p;
    }
}
