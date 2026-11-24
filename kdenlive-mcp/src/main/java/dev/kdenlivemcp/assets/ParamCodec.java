package dev.kdenlivemcp.assets;

import dev.kdenlivemcp.model.EditException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts parameter values between what the API takes/shows (Kdenlive's UI units) and what MLT stores, and
 * reads/writes MLT keyframe strings. Mirrors Kdenlive's AssetParameterModel where it matters for file output.
 */
public final class ParamCodec {

    /** MLT keyframe interpolation characters, as Kdenlive writes them (keyframemodel.cpp typeMap). */
    public static final Map<String, String> EASES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("linear", "");
        m.put("discrete", "|");
        m.put("smooth", "$");
        m.put("smooth_loose", "~");
        m.put("smooth_tight", "-");
        String[] families = {"sine", "quad", "cubic", "quart", "quint", "expo", "circ", "back", "elastic", "bounce"};
        String chars = "abcdefghijklmnopqrstuvwxyzABCD";
        for (int i = 0; i < families.length; i++) {
            m.put(families[i] + "_in", String.valueOf(chars.charAt(i * 3)));
            m.put(families[i] + "_out", String.valueOf(chars.charAt(i * 3 + 1)));
            m.put(families[i] + "_in_out", String.valueOf(chars.charAt(i * 3 + 2)));
        }
        m.put("ease_in", "g");
        m.put("ease_out", "h");
        m.put("ease_in_out", "i");
        EASES = java.util.Collections.unmodifiableMap(m);
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("%[A-Za-z]+");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");
    private static final Map<String, String> NAMED_COLORS = Map.ofEntries(
            Map.entry("black", "000000"), Map.entry("white", "ffffff"), Map.entry("red", "ff0000"),
            Map.entry("green", "00ff00"), Map.entry("blue", "0000ff"), Map.entry("yellow", "ffff00"),
            Map.entry("cyan", "00ffff"), Map.entry("magenta", "ff00ff"), Map.entry("gray", "808080"),
            Map.entry("grey", "808080"), Map.entry("orange", "ffa500"), Map.entry("transparent", "00000000"));

    /**
     * What placeholders in defaults resolve to.
     *
     * @param anchor first frame of the owner in the effect's time base (an item's source in point, or 0)
     * @param length owner length in frames
     * @param contentWidth size of the owner's own picture (media size for clips), for %contentWidth
     */
    public record Context(int width, int height, long anchor, long length, int contentWidth, int contentHeight,
                          LongFunction<String> clock) {
    }

    /** A decoded keyframe: frame in the effect's time base, ease name, raw MLT value. */
    public record Key(long frame, String ease, String raw) {
    }

    private ParamCodec() {
    }

    // ------------------------------------------------------------------ defaults

    /** The raw value Kdenlive would store for a fresh effect (placeholders resolved, animated params anchored). */
    public static String defaultRaw(AssetParam p, Context c) {
        String v = resolve(p.defaultValue() == null ? "" : p.defaultValue(), p, c);
        if (p.animated() && !v.isEmpty() && !v.contains("=") && p.kind() != AssetParam.Kind.RAW) {
            return c.clock().apply(c.anchor()) + "=" + v;
        }
        return v;
    }

    static String resolve(String value, AssetParam p, Context c) {
        String v = value;
        if (p.kind() == AssetParam.Kind.RECT && "adjustcenter".equals(v.trim())) {
            return fitRect(c);
        }
        if (!v.contains("%")) {
            return v;
        }
        double fit = Math.min((double) c.width() / Math.max(1, c.contentWidth()),
                (double) c.height() / Math.max(1, c.contentHeight()));
        Matcher m = PLACEHOLDER.matcher(v);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep = switch (m.group()) {
                case "%width", "%maxWidth" -> Integer.toString(c.width());
                case "%height", "%maxHeight" -> Integer.toString(c.height());
                case "%contentWidth" -> Integer.toString(c.contentWidth());
                case "%contentHeight" -> Integer.toString(c.contentHeight());
                case "%fittedContentWidth" -> num(c.contentWidth() * fit);
                case "%fittedContentHeight" -> num(c.contentHeight() * fit);
                case "%in", "%position" -> Long.toString(c.anchor());
                case "%out" -> Long.toString(c.anchor() + c.length() - 1);
                case "%fade" -> Long.toString(Math.min(c.length(), Math.round(c.length() / 10.0)));
                default -> m.group();
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        v = sb.toString();
        AssetParam.Kind kind = p.kind();
        if ((kind == AssetParam.Kind.RECT || kind == AssetParam.Kind.POINT) && v.contains("%")) {
            // "25% 0% 50% 100%": percentages of the frame, x/w on width, y/h on height
            String[] parts = v.trim().split("\\s+");
            List<String> out = new ArrayList<>();
            for (int i = 0; i < parts.length; i++) {
                String s = parts[i];
                if (s.endsWith("%")) {
                    double n = Double.parseDouble(s.substring(0, s.length() - 1)) / 100.0;
                    out.add(Long.toString(Math.round(n * (i % 2 == 0 ? c.width() : c.height()))));
                } else {
                    out.add(s);
                }
            }
            return String.join(" ", out);
        }
        if (kind == AssetParam.Kind.NUMBER || kind == AssetParam.Kind.HIDDEN || kind == AssetParam.Kind.FIXED) {
            Double d = Expr.eval(v);
            if (d != null) {
                return num(d);
            }
        }
        return v;
    }

    private static String fitRect(Context c) {
        double sourceDar = (double) c.contentWidth() / Math.max(1, c.contentHeight());
        double dar = (double) c.width() / c.height();
        long w = c.width();
        long h = c.height();
        if (sourceDar > dar) {
            h = Math.round(c.width() / sourceDar);
        } else {
            w = Math.round(c.height() * sourceDar);
        }
        return ((c.width() - w) / 2) + " " + ((c.height() - h) / 2) + " " + w + " " + h;
    }

    // ------------------------------------------------------------------ API value -> raw

    /** Converts a client value (UI units) to the raw MLT value for a single (non-keyframed) setting. */
    public static String toRaw(AssetParam p, Object value, Context c) {
        if (value == null) {
            throw new EditException(p.name() + ": value is missing");
        }
        return switch (p.kind()) {
            case NUMBER -> number(p, value);
            case BOOL -> bool(p, value);
            case LIST -> listValue(p, value);
            case COLOR -> color(p, value);
            case RECT -> rect(p, value, c);
            case POINT -> point(p, value);
            case TEXT, RAW -> value instanceof Map<?, ?> || value instanceof List<?> ? json(value) : value.toString();
            case FIXED, HIDDEN, POSITION -> throw new EditException(p.name() + " is set by Kdenlive and cannot be changed");
        };
    }

    private static String number(AssetParam p, Object value) {
        double ui;
        if (value instanceof Number n) {
            ui = n.doubleValue();
        } else if (value instanceof Boolean b) {
            ui = b ? 1 : 0;
        } else {
            String s = value.toString().trim().replace("%", "");
            try {
                ui = Double.parseDouble(s);
            } catch (NumberFormatException e) {
                throw new EditException(p.name() + ": '" + value + "' is not a number" + range(p));
            }
        }
        if (Double.isNaN(ui) || Double.isInfinite(ui)) {
            throw new EditException(p.name() + ": '" + value + "' is not a finite number");
        }
        if ((p.min() != null && ui < p.min() - 1e-9) || (p.max() != null && ui > p.max() + 1e-9)) {
            throw new EditException(p.name() + " = " + num(ui) + " is out of range" + range(p));
        }
        return num(ui / p.factor());
    }

    private static String bool(AssetParam p, Object value) {
        boolean on;
        if (value instanceof Boolean b) {
            on = b;
        } else if (value instanceof Number n) {
            on = n.doubleValue() != 0;
        } else {
            String s = value.toString().trim().toLowerCase(Locale.ROOT);
            on = switch (s) {
                case "1", "true", "on", "yes" -> true;
                case "0", "false", "off", "no" -> false;
                default -> throw new EditException(p.name() + ": use true or false, not '" + value + "'");
            };
        }
        if ("switch".equalsIgnoreCase(p.type())) {
            // a switch stores two arbitrary strings: min when off, max when on
            String off = p.minText() == null ? "0" : p.minText();
            String onV = p.maxText() == null ? "1" : p.maxText();
            return on ? onV : off;
        }
        return on ? "1" : "0";
    }

    private static String listValue(AssetParam p, Object value) {
        String s = value instanceof Number n ? num(n.doubleValue()) : value.toString().trim();
        for (int i = 0; i < p.values().size(); i++) {
            if (p.values().get(i).equals(s)) {
                return s;
            }
        }
        for (int i = 0; i < p.labels().size() && i < p.values().size(); i++) {
            if (p.labels().get(i).equalsIgnoreCase(s)) {
                return p.values().get(i);
            }
        }
        if (p.values().isEmpty()) {
            return s;
        }
        throw new EditException(p.name() + ": '" + value + "' is not one of " + choices(p));
    }

    /** "#RRGGBB", "#RRGGBBAA", "0xRRGGBBAA" or a basic color name -> Kdenlive's "0xRRGGBBAA". */
    static String color(AssetParam p, Object value) {
        String s = value.toString().trim();
        String hex;
        if (NAMED_COLORS.containsKey(s.toLowerCase(Locale.ROOT))) {
            hex = NAMED_COLORS.get(s.toLowerCase(Locale.ROOT));
        } else if (s.startsWith("#")) {
            hex = s.substring(1);
        } else if (s.toLowerCase(Locale.ROOT).startsWith("0x")) {
            hex = s.substring(2);
        } else {
            hex = s;
        }
        if (!HEX.matcher(hex).matches() || (hex.length() != 6 && hex.length() != 8)) {
            throw new EditException(p.name() + ": '" + value + "' is not a color; use #RRGGBB or #RRGGBBAA");
        }
        if (hex.length() == 6) {
            hex += "ff";
        }
        return "0x" + hex.toLowerCase(Locale.ROOT);
    }

    private static String rect(AssetParam p, Object value, Context c) {
        List<Double> n = numbers(p, value, List.of("x", "y", "w", "h", "opacity"));
        if (n.size() < 4) {
            throw new EditException(p.name() + ": a rectangle needs x, y, w, h (pixels of the " + c.width() + "x"
                    + c.height() + " frame) and optional opacity 0..1, e.g. {\"x\":0,\"y\":0,\"w\":" + c.width()
                    + ",\"h\":" + c.height() + ",\"opacity\":1}");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(i == 0 ? "" : " ").append(num(n.get(i)));
        }
        if (n.size() > 4 || p.type().contains("rect")) {
            double o = n.size() > 4 ? n.get(4) : 1;
            if (o < 0 || o > 1) {
                throw new EditException(p.name() + ": opacity must be 0..1, not " + num(o));
            }
            sb.append(' ').append(num(o));
        }
        return sb.toString();
    }

    private static String point(AssetParam p, Object value) {
        List<Double> n = numbers(p, value, List.of("x", "y"));
        if (n.size() < 2) {
            throw new EditException(p.name() + ": a point needs x and y, e.g. {\"x\":0.5,\"y\":0.5}");
        }
        return num(n.get(0)) + " " + num(n.get(1));
    }

    private static List<Double> numbers(AssetParam p, Object value, List<String> keys) {
        List<Double> out = new ArrayList<>();
        try {
            if (value instanceof Map<?, ?> m) {
                for (String k : keys) {
                    Object v = m.get(k);
                    if (v == null && k.equals("w")) {
                        v = m.get("width");
                    }
                    if (v == null && k.equals("h")) {
                        v = m.get("height");
                    }
                    if (v != null) {
                        out.add(Double.parseDouble(v.toString()));
                    } else if (out.size() < keys.size() - 1 || !k.equals("opacity")) {
                        break;
                    }
                }
            } else if (value instanceof List<?> l) {
                for (Object v : l) {
                    out.add(Double.parseDouble(v.toString()));
                }
            } else {
                for (String s : value.toString().trim().split("[\\s,]+")) {
                    if (!s.isEmpty()) {
                        out.add(Double.parseDouble(s));
                    }
                }
            }
        } catch (NumberFormatException e) {
            throw new EditException(p.name() + ": '" + value + "' has a non-numeric part");
        }
        return out;
    }

    // ------------------------------------------------------------------ raw -> API value

    /** Raw MLT value to what the client sees (UI units). */
    public static Object toUi(AssetParam p, String raw) {
        if (raw == null) {
            return null;
        }
        return switch (p.kind()) {
            case NUMBER -> {
                Double d = parseDouble(raw);
                yield d == null ? raw : round(d * p.factor());
            }
            case BOOL -> "switch".equalsIgnoreCase(p.type()) ? raw.equals(p.maxText()) : !raw.trim().equals("0")
                    && !raw.isBlank();
            case LIST -> {
                int i = p.values().indexOf(raw);
                yield i >= 0 && i < p.labels().size() && !p.labels().get(i).equals(raw)
                        ? raw + " (" + p.labels().get(i) + ")" : raw;
            }
            case COLOR -> raw.toLowerCase(Locale.ROOT).startsWith("0x") && raw.length() == 10
                    ? "#" + raw.substring(2).toLowerCase(Locale.ROOT) : raw;
            default -> raw;
        };
    }

    // ------------------------------------------------------------------ keyframes

    /** Builds an MLT animation string from keyframes (frames already in the effect's time base). */
    public static String encode(List<Key> keys, LongFunction<String> clock) {
        StringBuilder sb = new StringBuilder();
        for (Key k : keys.stream().sorted(java.util.Comparator.comparingLong(Key::frame)).toList()) {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(clock.apply(k.frame())).append(easeChar(k.ease())).append('=').append(k.raw());
        }
        return sb.toString();
    }

    /** Parses "00:00:01.000~=0.5;45|=1" style strings; plain values become one key at frame 0. */
    public static List<Key> decode(String anim, java.util.function.ToLongFunction<String> parseTime) {
        List<Key> out = new ArrayList<>();
        if (anim == null || anim.isBlank()) {
            return out;
        }
        if (!anim.contains("=")) {
            out.add(new Key(0, "linear", anim));
            return out;
        }
        for (String part : anim.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String pos = part.substring(0, eq).trim();
            String ease = "linear";
            char last = pos.charAt(pos.length() - 1);
            if (!Character.isDigit(last)) {
                ease = easeName(String.valueOf(last));
                pos = pos.substring(0, pos.length() - 1);
            }
            out.add(new Key(parseTime.applyAsLong(pos), ease, part.substring(eq + 1)));
        }
        return out;
    }

    public static String easeChar(String ease) {
        String key = ease == null || ease.isBlank() ? "linear" : ease.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        String c = EASES.get(key);
        if (c == null) {
            throw new EditException("unknown ease '" + ease + "'; use one of: " + String.join(", ", EASES.keySet()));
        }
        return c;
    }

    static String easeName(String c) {
        for (Map.Entry<String, String> e : EASES.entrySet()) {
            if (e.getValue().equals(c)) {
                return e.getKey();
            }
        }
        return c.equals("!") ? "discrete" : "linear";
    }

    // ------------------------------------------------------------------ text

    public static String range(AssetParam p) {
        if (p.min() == null && p.max() == null) {
            return "";
        }
        String suffix = p.suffix() == null ? "" : p.suffix();
        return " (" + (p.min() == null ? "" : num(p.min()) + suffix) + ".." + (p.max() == null ? "" : num(p.max()) + suffix)
                + ")";
    }

    public static String choices(AssetParam p) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < p.values().size(); i++) {
            String label = i < p.labels().size() ? p.labels().get(i) : p.values().get(i);
            out.add(label.equals(p.values().get(i)) ? label : p.values().get(i) + "=" + label);
        }
        return String.join(" | ", out);
    }

    /** Number without trailing zeros, at most 6 decimals, dot separator. */
    public static String num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        String s = String.format(Locale.ROOT, "%.6f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    public static Double parseDouble(String s) {
        try {
            return s == null ? null : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    /** Tiny arithmetic evaluator for defaults like "%width / 2" (after placeholder substitution). */
    static final class Expr {
        private final String s;
        private int i;

        private Expr(String s) {
            this.s = s;
        }

        static Double eval(String text) {
            try {
                Expr e = new Expr(text.replace(" ", ""));
                double v = e.sum();
                return e.i == e.s.length() ? v : null;
            } catch (RuntimeException ex) {
                return null;
            }
        }

        private double sum() {
            double v = product();
            while (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                char op = s.charAt(i++);
                double r = product();
                v = op == '+' ? v + r : v - r;
            }
            return v;
        }

        private double product() {
            double v = unary();
            while (i < s.length() && (s.charAt(i) == '*' || s.charAt(i) == '/')) {
                char op = s.charAt(i++);
                double r = unary();
                v = op == '*' ? v * r : v / r;
            }
            return v;
        }

        private double unary() {
            if (i < s.length() && s.charAt(i) == '-') {
                i++;
                return -unary();
            }
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                double v = sum();
                i++;
                return v;
            }
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == 'e')) {
                i++;
            }
            return Double.parseDouble(s.substring(start, i));
        }
    }
}
