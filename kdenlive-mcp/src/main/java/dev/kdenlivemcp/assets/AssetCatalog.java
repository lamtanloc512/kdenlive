package dev.kdenlivemcp.assets;

import dev.kdenlivemcp.backend.kdenlive.Xml;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Every effect and transition Kdenlive defines, loaded from the copies of its data/ XML files in
 * {@code kdenlive-assets/} (see scripts/sync-kdenlive-assets.sh). Pure data: whether the local melt can run an
 * asset is decided by the caller from {@code melt -query}.
 */
public final class AssetCatalog {

    static final String ROOT = "kdenlive-assets/";
    private static final Pattern DOCTYPE = Pattern.compile("<!DOCTYPE[^>]*>");

    private final Map<String, AssetDef> effects;
    private final Map<String, AssetDef> transitions;

    private AssetCatalog(Map<String, AssetDef> effects, Map<String, AssetDef> transitions) {
        this.effects = effects;
        this.transitions = transitions;
    }

    /** Loads the bundled definitions from the classpath. */
    public static AssetCatalog load() {
        ClassLoader cl = AssetCatalog.class.getClassLoader();
        String index = read(cl, ROOT + "index.txt");
        Map<String, String> categories = categories(read(cl, ROOT + "kdenliveeffectscategory.rc"));
        Set<String> hidden = Set.copyOf(lines(read(cl, ROOT + "hidden_effects.txt")));
        // Kdenlive never registers excluded MLT services, so their XML definitions are unusable there too
        Set<String> excludedEffects = Set.copyOf(lines(read(cl, ROOT + "excluded_effects.txt")));
        Set<String> excludedTransitions = Set.copyOf(lines(read(cl, ROOT + "excluded_transitions.txt")));
        Map<String, AssetDef> effects = new LinkedHashMap<>();
        Map<String, AssetDef> transitions = new LinkedHashMap<>();
        for (String file : index.split("\\R")) {
            if (file.isBlank()) {
                continue;
            }
            AssetDef.Domain domain = file.startsWith("transitions/") ? AssetDef.Domain.TRANSITION
                    : file.startsWith("generators/") ? AssetDef.Domain.GENERATOR : AssetDef.Domain.EFFECT;
            if (domain == AssetDef.Domain.GENERATOR) {
                continue;
            }
            for (AssetDef def : parse(read(cl, ROOT + file), file, domain)) {
                if (domain == AssetDef.Domain.EFFECT && (hidden.contains(def.id()) || excludedEffects.contains(def.service()))) {
                    continue;
                }
                if (domain == AssetDef.Domain.TRANSITION && excludedTransitions.contains(def.service())) {
                    continue;
                }
                Map<String, AssetDef> target = domain == AssetDef.Domain.EFFECT ? effects : transitions;
                AssetDef withCat = def.withCategory(categories.getOrDefault(def.id(),
                        def.audio() ? "Audio" : domain == AssetDef.Domain.EFFECT ? "Other" : "Transitions"));
                target.merge(def.id(), withCat, (a, b) -> b.version() > a.version() ? b : a);
            }
        }
        return new AssetCatalog(Map.copyOf(effects), Map.copyOf(transitions));
    }

    public Optional<AssetDef> effect(String id) {
        return Optional.ofNullable(id == null ? null : effects.get(id.trim()));
    }

    public Optional<AssetDef> transition(String id) {
        return Optional.ofNullable(id == null ? null : transitions.get(id.trim()));
    }

    public Collection<AssetDef> effects() {
        return effects.values().stream().sorted(Comparator.comparing(AssetDef::category).thenComparing(AssetDef::name))
                .toList();
    }

    public Collection<AssetDef> transitions() {
        return transitions.values().stream().sorted(Comparator.comparing(AssetDef::name)).toList();
    }

    /** Effects matching a free-text query (id, name, description, category); all when the query is blank. */
    public List<AssetDef> searchEffects(String query, Predicate<AssetDef> filter) {
        List<String> words = query == null ? List.of()
                : List.of(query.toLowerCase(Locale.ROOT).trim().split("\\s+")).stream().filter(w -> !w.isBlank()).toList();
        return effects().stream().filter(filter).filter(d -> {
            String hay = (d.id() + " " + d.name() + " " + d.description() + " " + d.category()).toLowerCase(Locale.ROOT);
            return words.stream().allMatch(hay::contains);
        }).toList();
    }

    /** Up to {@code max} effect ids close to a misspelled one. */
    public List<String> suggestEffects(String id, int max) {
        String q = id == null ? "" : id.toLowerCase(Locale.ROOT).trim();
        return effects.values().stream()
                .sorted(Comparator.comparingInt((AssetDef d) -> score(q, d)).thenComparing(AssetDef::id))
                .limit(max).map(AssetDef::id).toList();
    }

    /** Lower is closer: exact or contained matches on the id (without avfilter./frei0r. prefix) or name first. */
    private static int score(String q, AssetDef d) {
        String id = d.id().toLowerCase(Locale.ROOT);
        String bare = id.replaceFirst("^(avfilter|frei0r|ladspa|movit|opencv)[._]", "");
        String name = d.name().toLowerCase(Locale.ROOT);
        if (q.equals(bare) || q.equals(name)) {
            return 0;
        }
        if (bare.contains(q) || name.contains(q) || q.contains(bare)) {
            return 1 + Math.abs(bare.length() - q.length()) / 4;
        }
        return 3 + Math.min(Math.min(distance(q, id), distance(q, bare)), distance(q, name));
    }

    // ------------------------------------------------------------------ parsing

    static List<AssetDef> parse(String xml, String file, AssetDef.Domain domain) {
        Element root = Xml.parseString(DOCTYPE.matcher(xml).replaceFirst("")).getDocumentElement();
        String tag = domain == AssetDef.Domain.TRANSITION ? "transition" : "effect";
        List<Element> defs = new ArrayList<>();
        if (root.getTagName().equals(tag)) {
            defs.add(root);
        } else {
            defs.addAll(Xml.children(root, tag));
        }
        List<AssetDef> out = new ArrayList<>();
        for (Element e : defs) {
            String service = e.getAttribute("tag");
            String id = e.getAttribute("id").isBlank() ? service : e.getAttribute("id");
            List<AssetParam> params = new ArrayList<>();
            for (Element p : Xml.children(e, "parameter")) {
                params.add(param(p));
            }
            out.add(new AssetDef(domain, id, service, text(e, "name", id), text(e, "description", ""),
                    e.getAttribute("type"), number(e.getAttribute("version"), 0), "1".equals(e.getAttribute("unique")),
                    "", params, file));
        }
        return out;
    }

    private static AssetParam param(Element p) {
        List<String> values = List.of();
        List<String> labels = List.of();
        String list = p.getAttribute("paramlist");
        if (!list.isBlank()) {
            values = List.of(list.split(";", -1));
            String display = text(p, "paramlistdisplay", "");
            labels = display.isBlank() ? values : List.of(display.split(",", -1));
        }
        String factor = p.getAttribute("factor");
        String decimals = p.getAttribute("decimals");
        return new AssetParam(p.getAttribute("name"), p.getAttribute("type"), text(p, "name", p.getAttribute("name")),
                text(p, "comment", ""), p.hasAttribute("default") ? p.getAttribute("default") : p.getAttribute("value"),
                numberOrNull(p.getAttribute("min")), numberOrNull(p.getAttribute("max")),
                p.hasAttribute("min") ? p.getAttribute("min") : null, p.hasAttribute("max") ? p.getAttribute("max") : null,
                factor.isBlank() || factor.contains(" ") ? 1 : number(factor, 1),
                decimals.isBlank() ? null : (int) number(decimals, 0), p.getAttribute("suffix"), values, labels);
    }

    private static Map<String, String> categories(String rc) {
        Map<String, String> out = new HashMap<>();
        Element root = Xml.parseString(DOCTYPE.matcher(rc).replaceFirst("")).getDocumentElement();
        for (Element g : Xml.children(root, "group")) {
            String name = text(g, "text", "Other");
            for (String id : g.getAttribute("list").split(",")) {
                if (!id.isBlank()) {
                    out.putIfAbsent(id.trim(), name);
                }
            }
        }
        return out;
    }

    private static String text(Element parent, String tag, String fallback) {
        List<Element> c = Xml.children(parent, tag);
        if (c.isEmpty()) {
            return fallback;
        }
        String t = c.getFirst().getTextContent().strip();
        return t.isEmpty() ? fallback : t;
    }

    private static Double numberOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null; // "%maxWidth" etc.
        }
    }

    private static double number(String s, double fallback) {
        Double d = numberOrNull(s);
        return d == null ? fallback : d;
    }

    private static List<String> lines(String text) {
        return text.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    }

    private static String read(ClassLoader cl, String path) {
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path + " (run scripts/sync-kdenlive-assets.sh)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }

    private static int distance(String a, String b) {
        if (b.contains(a) || a.contains(b)) {
            return Math.abs(a.length() - b.length());
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()] + 2;
    }
}
