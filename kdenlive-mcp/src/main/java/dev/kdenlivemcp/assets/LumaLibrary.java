package dev.kdenlivemcp.assets;

import dev.kdenlivemcp.model.EditException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Wipe shapes ("luma" files) as Kdenlive offers them (MltConnection::refreshLumas): MLT's 22 built-in lumas, stored
 * by bare name ({@code luma16.pgm}, generated on the fly), plus Kdenlive's own luma images, stored by absolute path.
 * Names are Kdenlive's (Core::nameForLumaFile), so "Iris Circle", "iris_circle", "luma16" and "luma16.pgm" all work.
 */
public final class LumaLibrary {

    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        String[] builtIn = {"Bar Horizontal", "Bar Vertical", "Barn Door Horizontal", "Barn Door Vertical",
                "Barn Door Diagonal SW-NE", "Barn Door Diagonal NW-SE", "Diagonal Top Left", "Diagonal Top Right",
                "Matrix Waterfall Horizontal", "Matrix Waterfall Vertical", "Matrix Snake Horizontal",
                "Matrix Snake Parallel Horizontal", "Matrix Snake Vertical", "Matrix Snake Parallel Vertical", "Barn V Up",
                "Iris Circle", "Double Iris", "Iris Box", "Box Bottom Right", "Box Bottom Left", "Box Right Center",
                "Clock Top"};
        for (int i = 0; i < builtIn.length; i++) {
            NAMES.put(String.format(Locale.ROOT, "luma%02d.pgm", i + 1), builtIn[i]);
        }
        String[][] files = {{"square2-bars.pgm", "Square 2 Bars"}, {"checkerboard_small.pgm", "Checkerboard Small"},
                {"horizontal_blinds.pgm", "Horizontal Blinds"}, {"radial.pgm", "Radial"}, {"linear_x.pgm", "Linear X"},
                {"bi-linear_x.pgm", "Bi-Linear X"}, {"linear_y.pgm", "Linear Y"}, {"bi-linear_y.pgm", "Bi-Linear Y"},
                {"square.pgm", "Square"}, {"square2.pgm", "Square 2"}, {"cloud.pgm", "Cloud"},
                {"symmetric_clock.pgm", "Symmetric Clock"}, {"radial-bars.pgm", "Radial Bars"}, {"spiral.pgm", "Spiral"},
                {"spiral2.pgm", "Spiral 2"}, {"curtain.pgm", "Curtain"}, {"burst.pgm", "Burst"}, {"clock.pgm", "Clock"}};
        for (String[] f : files) {
            NAMES.put(f[0], f[1]);
        }
    }

    /** file name -> value to store (bare name for built-ins, absolute path for files). */
    private final Map<String, String> values = new LinkedHashMap<>();

    /**
     * @param dirs folders holding Kdenlive's luma images (searched in order, first hit wins); the HD subfolder is
     *             used when present, as Kdenlive does for HD profiles
     */
    public LumaLibrary(List<Path> dirs) {
        for (String n : NAMES.keySet()) {
            if (n.startsWith("luma")) {
                values.put(n, n);
            }
        }
        for (Path dir : dirs) {
            Path hd = dir.resolve("HD");
            Path base = Files.isDirectory(hd) ? hd : dir;
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> s = Files.list(base)) {
                s.filter(f -> f.toString().endsWith(".pgm") || f.toString().endsWith(".png")).sorted()
                        .forEach(f -> values.putIfAbsent(f.getFileName().toString(), f.toAbsolutePath().toString()));
            } catch (IOException e) {
                // unreadable folder: skip
            }
        }
    }

    /** Kdenlive's folders and the bundled tools' copy, in that order (Kdenlive's own paths keep projects portable). */
    public static LumaLibrary discover(Path meltPath) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(Path.of("/Applications/kdenlive.app/Contents/Resources/lumas"));
        dirs.add(Path.of("/usr/share/kdenlive/lumas"));
        dirs.add(Path.of(System.getProperty("user.home"), ".local/share/kdenlive/lumas"));
        if (meltPath != null) {
            Path real = meltPath.toAbsolutePath();
            try {
                real = real.toRealPath();
            } catch (IOException e) {
                // keep as is
            }
            Path root = real.getParent() == null ? null : real.getParent().getParent();
            if (root != null) {
                dirs.add(root.resolve("Contents/Resources/lumas"));
                dirs.add(root.resolve("usr/share/kdenlive/lumas"));
            }
        }
        return new LumaLibrary(dirs);
    }

    /** The value to store for a luma given by name, Kdenlive label, file name or path; "" / "none" = dissolve. */
    public String resolve(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("none") || name.equalsIgnoreCase("dissolve")) {
            return "";
        }
        String n = name.trim();
        if (n.startsWith("/") && Files.isRegularFile(Path.of(n))) {
            return n;
        }
        String key = norm(n.replaceFirst("^%", "").replaceFirst("\\.(pgm|png)$", ""));
        for (Map.Entry<String, String> e : values.entrySet()) {
            String file = e.getKey().replaceFirst("\\.(pgm|png)$", "");
            if (norm(file).equals(key) || norm(label(e.getKey())).equals(key)) {
                return e.getValue();
            }
        }
        throw new EditException("unknown wipe shape '" + name + "'; use one of: " + String.join(", ", labels()));
    }

    /** Human label for a stored value. */
    public static String label(String value) {
        if (value == null || value.isBlank()) {
            return "none (dissolve)";
        }
        String file = Path.of(value.replaceFirst("^%", "")).getFileName().toString();
        return NAMES.getOrDefault(file, file);
    }

    public List<String> labels() {
        return values.keySet().stream().map(LumaLibrary::label).toList();
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
