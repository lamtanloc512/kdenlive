package dev.kdenlivemcp.backend.kdenlive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kdenlivemcp.media.MediaInfo;
import dev.kdenlivemcp.model.AudioSettings;
import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.Subtitles;
import dev.kdenlivemcp.model.Composition;
import dev.kdenlivemcp.model.DocumentMeta;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Effect;
import dev.kdenlivemcp.model.Extras;
import dev.kdenlivemcp.model.FadeToBlack;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.MediaClip;
import dev.kdenlivemcp.model.Mix;
import dev.kdenlivemcp.model.MixType;
import dev.kdenlivemcp.model.Profile;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.TimelineItem;
import dev.kdenlivemcp.model.TitleItem;
import dev.kdenlivemcp.model.Track;
import dev.kdenlivemcp.model.TrackKind;
import dev.kdenlivemcp.model.Transition;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Kdenlive document (version 1.1, one sequence) into a {@link Project}. Pure: it does not touch media
 * files; clip info comes from the properties Kdenlive stores (the backend re-probes files that exist).
 */
public final class KdenliveReader {

    private static final Pattern ID_NUM = Pattern.compile("^[a-z](\\d+)$");
    private static final Set<String> MANAGED_TRACK_PROPS = Set.of("kdenlive:audio_track", "kdenlive:trackheight",
            "kdenlive:timeline_active", "kdenlive:collapsed", "kdenlive:thumbs_format", "kdenlive:audio_rec",
            "kdenlive:locked_track", "kdenlive:mcp.muted");
    private static final Set<String> MANAGED_SEQ_PROPS = Set.of("kdenlive:uuid", "kdenlive:duration",
            "kdenlive:maxduration", "kdenlive:producer_type", "kdenlive:id", "kdenlive:clip_type", "kdenlive:folderid",
            "kdenlive:sequenceproperties.groups", "kdenlive:sequenceproperties.hasAudio",
            "kdenlive:sequenceproperties.hasVideo", "kdenlive:sequenceproperties.activeTrack",
            "kdenlive:sequenceproperties.tracksCount", "kdenlive:sequenceproperties.documentuuid",
            "kdenlive:sequenceproperties.audioTarget", "kdenlive:sequenceproperties.videoTarget",
            "kdenlive:sequenceproperties.tracks", "kdenlive:sequenceproperties.position",
            "kdenlive:sequenceproperties.scrollPos", "kdenlive:sequenceproperties.zoom",
            "kdenlive:sequenceproperties.zonein", "kdenlive:sequenceproperties.zoneout",
            "kdenlive:sequenceproperties.verticalzoom", "kdenlive:sequenceproperties.disablepreview");
    private static final Set<String> MANAGED_BIN_PROPS = Set.of("kdenlive:folder.-1.2", "kdenlive:sequenceFolder",
            "kdenlive:docproperties.audioChannels", "kdenlive:docproperties.binsort",
            "kdenlive:docproperties.enableTimelineZone", "kdenlive:docproperties.enableexternalproxy",
            "kdenlive:docproperties.enableproxy", "kdenlive:docproperties.externalproxyparams",
            "kdenlive:docproperties.generateimageproxy", "kdenlive:docproperties.generateproxy",
            "kdenlive:docproperties.patchversion", "kdenlive:docproperties.previewextension",
            "kdenlive:docproperties.previewparameters", "kdenlive:docproperties.proxyextension",
            "kdenlive:docproperties.proxyimageminsize", "kdenlive:docproperties.proxyimagesize",
            "kdenlive:docproperties.proxyminsize", "kdenlive:docproperties.proxyparams",
            "kdenlive:docproperties.proxyresize", "kdenlive:docproperties.seekOffset", "kdenlive:expandedFolders",
            "kdenlive:binZoom", "kdenlive:extraBins", "kdenlive:documentnotesversion");
    private static final Set<String> MANAGED_MEDIA_PROPS = Set.of("length", "eof", "resource", "mlt_service",
            "seekable", "format", "vstream", "astream", "kdenlive:folderid", "kdenlive:id", "kdenlive:clip_type",
            "mute_on_pause", "kdenlive:clipname", "set.test_audio", "set.test_image", "kdenlive:monitorPosition",
            "xml", "ttl", "aspect_ratio");

    /** A timeline entry as written in the file, before mixes are undone. */
    private static final class Raw {
        final int track;
        final int layer;
        final long writtenStart;
        long start;
        long in;
        long length;
        final Element producer;
        final Element entry;
        final int binId;
        String id;
        String linkId;

        Raw(int track, int layer, long start, long in, long length, Element producer, Element entry, int binId) {
            this.track = track;
            this.layer = layer;
            this.writtenStart = start;
            this.start = start;
            this.in = in;
            this.length = length;
            this.producer = producer;
            this.entry = entry;
            this.binId = binId;
        }

        boolean isTitle() {
            return "kdenlivetitle".equals(Xml.prop(producer, "mlt_service"));
        }
    }

    private final Path file;
    private final Document doc;
    private final Element mlt;
    private final Map<String, Element> byId = new HashMap<>();
    private Fps fps;
    private Path root;
    private JsonNode idMap;
    private final Set<String> usedIds = new HashSet<>();
    private long nextId = 1;

    private KdenliveReader(Path file, Document doc) {
        this.file = file.toAbsolutePath().normalize();
        this.doc = doc;
        this.mlt = doc.getDocumentElement();
    }

    public static Project read(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new EditException("project file not found: " + file.toAbsolutePath());
        }
        return new KdenliveReader(file, Xml.parse(file)).read();
    }

    public static Project read(Path file, String xml) {
        return new KdenliveReader(file, Xml.parseString(xml)).read();
    }

    private Project read() {
        if (!"mlt".equals(mlt.getTagName())) {
            throw new EditException(file.getFileName() + " is not a Kdenlive/MLT project (root element <"
                    + mlt.getTagName() + ">)");
        }
        for (Element e : Xml.children(mlt, null)) {
            if (e.hasAttribute("id")) {
                byId.put(e.getAttribute("id"), e);
            }
        }
        Element bin = byId.get("main_bin");
        if (bin == null) {
            throw new EditException(file.getFileName() + " has no main_bin playlist: it is plain MLT XML, not a "
                    + "Kdenlive project. Open it in Kdenlive and save it once.");
        }
        Map<String, String> binProps = Xml.properties(bin);
        String version = binProps.getOrDefault("kdenlive:docproperties.version", "?");
        if (!version.startsWith("1.1")) {
            throw new EditException(file.getFileName() + " is Kdenlive document version " + version
                    + "; kdenlive-mcp reads version 1.1 (Kdenlive 23.04 and later). Open and save it in a recent Kdenlive.");
        }
        String rootAttr = mlt.getAttribute("root");
        root = !rootAttr.isBlank() && Files.isDirectory(Path.of(rootAttr)) ? Path.of(rootAttr) : file.getParent();
        Profile profile = readProfile(binProps.get("kdenlive:docproperties.profile"));
        fps = profile.fps();
        idMap = parseJson(binProps.get(KdenliveWriter.ID_MAP_PROPERTY));
        nextId = idMap.path("next").asLong(1);

        String seqUuid = binProps.getOrDefault("kdenlive:docproperties.activetimeline",
                binProps.get("kdenlive:docproperties.uuid"));
        List<Element> sequences = new ArrayList<>();
        for (Element e : Xml.children(mlt, "tractor")) {
            if ("17".equals(Xml.prop(e, "kdenlive:producer_type"))) {
                sequences.add(e);
            }
        }
        if (sequences.size() > 1) {
            throw new EditException(file.getFileName() + " has " + sequences.size() + " sequences; kdenlive-mcp v1 "
                    + "edits single-sequence projects only");
        }
        Element seq = seqUuid != null && byId.containsKey(seqUuid) ? byId.get(seqUuid)
                : sequences.isEmpty() ? null : sequences.getFirst();
        if (seq == null) {
            throw new EditException(file.getFileName() + ": no timeline sequence found");
        }

        List<MediaClip> media = readBin(bin, seq);
        Map<Integer, MediaClip> mediaByBin = new HashMap<>();
        media.forEach(m -> mediaByBin.put(m.binId(), m));

        // tracks, bottom-up as in the file
        List<Element> trackEls = new ArrayList<>();
        Element black = null;
        for (Element t : Xml.children(seq, "track")) {
            Element prod = byId.get(t.getAttribute("producer"));
            if (prod == null) {
                throw new EditException("broken project: track producer " + t.getAttribute("producer") + " missing");
            }
            if (black == null && trackEls.isEmpty() && !"tractor".equals(prod.getTagName())) {
                black = prod;
                continue;
            }
            if (!"tractor".equals(prod.getTagName())) {
                throw new EditException(file.getFileName() + " uses an old track layout (playlist tracks); "
                        + "open and save it in Kdenlive 23.04 or later");
            }
            trackEls.add(prod);
        }
        int audioCount = (int) trackEls.stream().filter(KdenliveReader::isAudioTrack).count();
        List<String> names = new ArrayList<>();
        int a = 0;
        int v = 0;
        for (Element t : trackEls) {
            names.add(isAudioTrack(t) ? "A" + (audioCount - a++) : "V" + (++v));
        }

        List<Raw> raws = new ArrayList<>();
        List<Transition> transitions = new ArrayList<>();
        List<Extras> trackExtras = new ArrayList<>();
        List<List<Raw>> perTrack = new ArrayList<>();
        List<Element[]> mixes = new ArrayList<>(); // {transition, trackTractor}
        List<List<Element>> trackEffectEls = new ArrayList<>();
        for (int i = 0; i < trackEls.size(); i++) {
            Element tr = trackEls.get(i);
            List<Raw> items = readTrackItems(tr, i);
            perTrack.add(items);
            raws.addAll(items);
            Map<String, String> extraProps = new LinkedHashMap<>();
            Xml.properties(tr).forEach((k, val) -> {
                if (!MANAGED_TRACK_PROPS.contains(k)) {
                    extraProps.put(k, val);
                }
            });
            List<String> extraXml = new ArrayList<>();
            List<Element> fxEls = new ArrayList<>();
            for (Element f : Xml.children(tr, "filter")) {
                if (Xml.prop(f, "internal_added") == null) {
                    if (isEffect(f)) {
                        fxEls.add(f);
                    } else {
                        extraXml.add(Xml.toString(f, false));
                    }
                }
            }
            trackEffectEls.add(fxEls);
            for (Element t : Xml.children(tr, "transition")) {
                if (Xml.prop(t, "kdenlive:mixcut") != null) {
                    mixes.add(new Element[] {t, tr});
                } else {
                    extraXml.add(Xml.toString(t, false));
                }
            }
            trackExtras.add(new Extras(extraProps, extraXml));
        }

        // links from AVSplit groups, matched on written positions
        Map<String, Raw> byTrackPos = new HashMap<>();
        raws.forEach(r -> byTrackPos.put(r.track + ":" + r.writtenStart, r));
        List<Raw[]> links = new ArrayList<>();
        JsonNode groups = parseJson(Xml.prop(seq, "kdenlive:sequenceproperties.groups"));
        for (JsonNode g : groups) {
            if (!"AVSplit".equals(g.path("type").asText())) {
                continue;
            }
            List<Raw> members = new ArrayList<>();
            for (JsonNode c : g.path("children")) {
                String[] parts = c.path("data").asText().split(":");
                if (parts.length >= 2) {
                    Raw r = byTrackPos.get(parts[0] + ":" + parts[1]);
                    if (r != null) {
                        members.add(r);
                    }
                }
            }
            if (members.size() == 2) {
                links.add(new Raw[] {members.get(0), members.get(1)});
            }
        }

        // undo mixes
        List<Object[]> pendingMixes = new ArrayList<>(); // {Raw a, Raw b, type, length, cut}
        for (Element[] m : mixes) {
            Element t = m[0];
            int trackIndex = trackEls.indexOf(m[1]);
            long tin = MltTime.parse(t.getAttribute("in"), fps);
            long tout = MltTime.parse(t.getAttribute("out"), fps);
            long cut = Long.parseLong(Xml.prop(t, "kdenlive:mixcut").trim());
            List<Raw> items = perTrack.get(trackIndex);
            Raw b = items.stream().filter(r -> r.writtenStart == tin).findFirst().orElse(null);
            Raw aRaw = b == null ? null : items.stream().filter(r -> r != b && r.layer != b.layer
                    && r.writtenStart < tin && r.writtenStart + r.length >= tin).findFirst().orElse(null);
            if (aRaw == null || b == null) {
                trackExtras.set(trackIndex, withXml(trackExtras.get(trackIndex), Xml.toString(t, false)));
                continue;
            }
            String service = Xml.prop(t, "mlt_service");
            String resource = Xml.prop(t, "resource");
            MixType type = "luma".equals(service) && resource != null && !resource.isBlank() ? MixType.WIPE
                    : MixType.DISSOLVE;
            long length = tout - tin;
            long cutPos = tin + cut;
            aRaw.length = cutPos - aRaw.start;
            b.start = cutPos;
            b.in += cut;
            b.length -= cut;
            pendingMixes.add(new Object[] {aRaw, b, type, length, cut, resource});
        }

        // ids
        for (int i = 0; i < trackEls.size(); i++) {
            for (Raw r : perTrack.get(i)) {
                r.id = idFor(names.get(i) + "@" + r.start, r.isTitle() ? "t" : "c");
            }
        }
        for (Raw[] l : links) {
            l[0].linkId = l[1].id;
            l[1].linkId = l[0].id;
        }
        for (Object[] pm : pendingMixes) {
            Raw ra = (Raw) pm[0];
            Raw rb = (Raw) pm[1];
            long length = (Long) pm[3];
            long cut = (Long) pm[4];
            transitions.add(new Mix(transitionId("mix:" + ra.id), (MixType) pm[2], ra.id, rb.id, length,
                    Math.min(cut, length), (String) pm[5]));
        }

        // items with effects
        List<Track> tracks = new ArrayList<>();
        for (int i = trackEls.size() - 1; i >= 0; i--) { // model order is top to bottom
            Element tr = trackEls.get(i);
            TrackKind kind = isAudioTrack(tr) ? TrackKind.AUDIO : TrackKind.VIDEO;
            List<TimelineItem> items = new ArrayList<>();
            for (Raw r : perTrack.get(i)) {
                items.add(toItem(r, kind, mediaByBin, transitions));
            }
            String hide = Xml.children(tr, "track").isEmpty() ? "" : Xml.children(tr, "track").getFirst().getAttribute("hide");
            boolean both = "both".equals(hide);
            boolean locked = "1".equals(Xml.prop(tr, "kdenlive:locked_track"));
            List<Effect> trackFx = new ArrayList<>();
            for (Element f : trackEffectEls.get(i)) {
                trackFx.add(effectFrom(f, names.get(i) + "#" + trackFx.size()));
            }
            tracks.add(new Track(names.get(i), kind, items, kind == TrackKind.AUDIO && both,
                    kind == TrackKind.VIDEO && both, locked, trackExtras.get(i), trackFx));
        }

        // document-level passthrough
        Map<String, String> metaProps = new LinkedHashMap<>();
        binProps.forEach((k, val) -> {
            if (!MANAGED_BIN_PROPS.contains(k) && !KdenliveWriter.MANAGED_BIN_PROPS.contains(k)
                    && !k.equals(KdenliveWriter.ID_MAP_PROPERTY)) {
                metaProps.put(k, val);
            }
        });
        Xml.properties(seq).forEach((k, val) -> {
            if (!MANAGED_SEQ_PROPS.contains(k)) {
                metaProps.put(KdenliveWriter.SEQ_PREFIX + k, val);
            }
        });
        List<String> seqXml = new ArrayList<>();
        for (Element t : Xml.children(seq, "transition")) {
            if (Xml.prop(t, "internal_added") == null) {
                Composition c = composition(t, names);
                if (c != null) {
                    transitions.add(c);
                } else {
                    seqXml.add(Xml.toString(t, false));
                }
            }
        }
        List<Effect> master = new ArrayList<>();
        Subtitles subtitles = Subtitles.EMPTY;
        for (Element f : Xml.children(seq, "filter")) {
            if ("avfilter.subtitles".equals(Xml.prop(f, Effect.SERVICE)) && Xml.prop(f, "internal_added") != null) {
                subtitles = readSubtitles(f);
            } else if (Xml.prop(f, "internal_added") == null) {
                if (isEffect(f)) {
                    master.add(effectFrom(f, Project.MASTER + "#" + master.size()));
                } else {
                    seqXml.add(Xml.toString(f, false));
                }
            }
        }
        String uuid = Xml.prop(seq, "kdenlive:uuid");
        if (uuid == null) {
            uuid = seq.getAttribute("id");
        }
        DocumentMeta meta = new DocumentMeta(uuid,
                binProps.getOrDefault("kdenlive:docproperties.documentid", Long.toString(System.currentTimeMillis())),
                new Extras(metaProps, seqXml));
        return new Project(file, profile, media, tracks, transitions, nextId, meta, master, subtitles);
    }

    // ------------------------------------------------------------------ pieces

    private static boolean isAudioTrack(Element tractor) {
        if ("1".equals(Xml.prop(tractor, "kdenlive:audio_track"))) {
            return true;
        }
        List<Element> tracks = Xml.children(tractor, "track");
        return !tracks.isEmpty() && "video".equals(tracks.getFirst().getAttribute("hide"));
    }

    private Profile readProfile(String mltName) {
        Element pe = Xml.children(mlt, "profile").stream().findFirst()
                .orElseThrow(() -> new EditException(file.getFileName() + " has no <profile>"));
        Fps f = new Fps(Integer.parseInt(pe.getAttribute("frame_rate_num")),
                Integer.parseInt(pe.getAttribute("frame_rate_den")));
        return new Profile(mltName == null ? "custom" : mltName, pe.getAttribute("description"),
                Integer.parseInt(pe.getAttribute("width")), Integer.parseInt(pe.getAttribute("height")), f,
                intAttr(pe, "sample_aspect_num", 1), intAttr(pe, "sample_aspect_den", 1),
                intAttr(pe, "display_aspect_num", 16), intAttr(pe, "display_aspect_den", 9),
                intAttr(pe, "colorspace", 709), !"0".equals(pe.getAttribute("progressive")));
    }

    private List<MediaClip> readBin(Element bin, Element seq) {
        List<MediaClip> out = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        for (Element entry : Xml.children(bin, "entry")) {
            Element prod = byId.get(entry.getAttribute("producer"));
            if (prod == null || prod == seq) {
                continue;
            }
            Map<String, String> props = Xml.properties(prod);
            String service = props.getOrDefault("mlt_service", "");
            if (service.equals("color")) {
                int binId = Integer.parseInt(props.getOrDefault("kdenlive:id", "0"));
                Map<String, String> extras = new LinkedHashMap<>();
                props.forEach((k, val) -> {
                    if (!MANAGED_MEDIA_PROPS.contains(k) && !k.equals("mlt_image_format") && !k.equals("kdenlive:duration")) {
                        extras.put(k, val);
                    }
                });
                out.add(new MediaClip(mediaId(binId), binId, Path.of("color:" + props.getOrDefault("resource", "0x000000ff")),
                        props.getOrDefault("kdenlive:clipname", "Color Clip"), MediaInfo.color(props.getOrDefault("resource",
                        "0x000000ff")), new Extras(extras, List.of())));
            } else if (service.startsWith("avformat") || service.equals("qimage") || service.equals("pixbuf")) {
                int binId = Integer.parseInt(props.getOrDefault("kdenlive:id", "0"));
                Path path = resolve(props.get("resource"));
                String id = mediaId(binId);
                Map<String, String> extras = new LinkedHashMap<>();
                props.forEach((k, val) -> {
                    if (!MANAGED_MEDIA_PROPS.contains(k)) {
                        extras.put(k, val);
                    }
                });
                String name = props.getOrDefault("kdenlive:clipname", path.getFileName().toString());
                out.add(new MediaClip(id, binId, path, name.isBlank() ? path.getFileName().toString() : name,
                        mediaInfo(path, props, service), new Extras(extras, List.of())));
            } else if (!service.equals("kdenlivetitle")) {
                unsupported.add(service + " (" + props.getOrDefault("kdenlive:clipname", prod.getAttribute("id")) + ")");
            }
        }
        if (!unsupported.isEmpty()) {
            throw new EditException(file.getFileName() + " contains bin clips kdenlive-mcp v1 cannot edit: "
                    + String.join(", ", unsupported) + ". Supported: video/audio files, images, color clips and titles.");
        }
        return out;
    }

    private MediaInfo mediaInfo(Path path, Map<String, String> props, String service) {
        boolean image = service.equals("qimage") || service.equals("pixbuf");
        long length = parseLong(props.get("length"), 0);
        int clipType = (int) parseLong(props.get("kdenlive:clip_type"), 0);
        int vi = (int) parseLong(props.get("video_index"), image ? 0 : -1);
        int ai = (int) parseLong(props.get("audio_index"), -1);
        boolean hasVideo = image || (vi >= 0 && clipType != 1);
        boolean hasAudio = !image && ai >= 0 && clipType != 2;
        Fps mediaFps = null;
        long num = parseLong(props.get("meta.media.frame_rate_num"), 0);
        long den = parseLong(props.get("meta.media.frame_rate_den"), 0);
        if (num > 0 && den > 0) {
            mediaFps = new Fps((int) num, (int) den);
        }
        return new MediaInfo(path, image ? 0 : fps.seconds(length), hasVideo, hasAudio, image,
                (int) parseLong(props.get("meta.media.width"), 0), (int) parseLong(props.get("meta.media.height"), 0),
                mediaFps, (int) parseLong(props.get("meta.media." + vi + ".codec.rotate"), 0), false,
                hasVideo ? props.getOrDefault("meta.media." + vi + ".codec.name", "unknown") : null,
                hasAudio ? props.getOrDefault("meta.media." + ai + ".codec.name", "unknown") : null,
                (int) parseLong(props.get("meta.media." + ai + ".codec.sample_rate"), 0),
                (int) parseLong(props.get("meta.media." + ai + ".codec.channels"), 0),
                "", parseLong(props.get("kdenlive:file_size"), 0));
    }

    private List<Raw> readTrackItems(Element tractor, int trackIndex) {
        List<Raw> out = new ArrayList<>();
        List<Element> tracks = Xml.children(tractor, "track");
        for (int layer = 0; layer < tracks.size(); layer++) {
            Element pl = byId.get(tracks.get(layer).getAttribute("producer"));
            if (pl == null) {
                continue;
            }
            long cursor = 0;
            for (Element e : Xml.children(pl, null)) {
                switch (e.getTagName()) {
                    case "blank" -> cursor += MltTime.parse(e.getAttribute("length"), fps);
                    case "entry" -> {
                        long in = MltTime.parse(e.getAttribute("in"), fps);
                        long outIncl = MltTime.parse(e.getAttribute("out"), fps);
                        long length = outIncl - in + 1;
                        Element prod = byId.get(e.getAttribute("producer"));
                        if (prod == null) {
                            throw new EditException("broken project: entry refers to missing producer "
                                    + e.getAttribute("producer"));
                        }
                        String binIdText = Xml.prop(e, "kdenlive:id");
                        if (binIdText == null) {
                            binIdText = Xml.prop(prod, "kdenlive:id");
                        }
                        int binId = (int) parseLong(binIdText, -1);
                        out.add(new Raw(trackIndex, layer, cursor, in, length, prod, e, binId));
                        cursor += length;
                    }
                    default -> {
                        // properties of the playlist
                    }
                }
            }
        }
        return out;
    }

    private TimelineItem toItem(Raw r, TrackKind kind, Map<Integer, MediaClip> mediaByBin, List<Transition> transitions) {
        double volume = 0;
        long fadeIn = 0;
        long fadeOut = 0;
        List<String> passthrough = new ArrayList<>();
        List<Effect> effects = new ArrayList<>();
        for (Element f : Xml.children(r.entry, "filter")) {
            String kid = Xml.prop(f, "kdenlive_id");
            long fin = f.hasAttribute("in") ? MltTime.parse(f.getAttribute("in"), fps) : 0;
            long fout = f.hasAttribute("out") ? MltTime.parse(f.getAttribute("out"), fps) : 0;
            String alpha = Xml.prop(f, "alpha");
            if ("volume".equals(kid) && kind == TrackKind.AUDIO && volume == 0) {
                volume = parseDb(Xml.prop(f, "level"));
            } else if ("fadein".equals(kid) && kind == TrackKind.AUDIO) {
                fadeIn = fout - fin;
            } else if ("fadeout".equals(kid) && kind == TrackKind.AUDIO) {
                fadeOut = fout - fin;
            } else if ("fade_to_black".equals(kid) && "1".equals(alpha)) {
                transitions.add(new FadeToBlack(transitionId("fade:" + r.id), r.id, fout - fin));
            } else if ("fade_to_black".equals(kid) && r.isTitle()) {
                fadeOut = fout - fin;
            } else if ("fade_from_black".equals(kid) && r.isTitle() && alpha != null && alpha.contains(";")) {
                fadeIn = fout - fin;
            } else if (isEffect(f)) {
                effects.add(effectFrom(f, r.id + "#" + effects.size()));
            } else {
                passthrough.add(Xml.toString(f, false));
            }
        }
        if (r.isTitle()) {
            Map<String, String> props = Xml.properties(r.producer);
            JsonNode meta = idMap.path("titles").path(r.id);
            String style = meta.path("style").asText("");
            if (!style.isEmpty() && !style.equals(TitleItem.EXTERNAL)) {
                return new TitleItem(r.id, meta.path("text").asText(""), style, r.start, r.length,
                        meta.path("fadeIn").asLong(fadeIn), meta.path("fadeOut").asLong(fadeOut),
                        new Extras(Map.of(), passthrough), effects);
            }
            String text = props.containsKey("xmldata") ? TitleXml.textOf(props.get("xmldata")) : titleTextFromFile(props);
            return new TitleItem(r.id, text, TitleItem.EXTERNAL, r.start, r.length, fadeIn, fadeOut,
                    new Extras(props, passthrough), effects);
        }
        MediaClip m = mediaByBin.get(r.binId);
        if (m == null) {
            throw new EditException("timeline clip at " + fps.sec(r.start) + "s refers to bin id " + r.binId
                    + " which is not a file clip kdenlive-mcp supports");
        }
        double speed = 1;
        boolean keepPitch = false;
        if ("timewarp".equals(Xml.prop(r.producer, Effect.SERVICE))) {
            Double ws = dev.kdenlivemcp.assets.ParamCodec.parseDouble(Xml.prop(r.producer, "warp_speed"));
            speed = ws == null || ws <= 0 ? 1 : ws; // reverse (negative) speed is not modelled: played forward
            keepPitch = "1".equals(Xml.prop(r.producer, "warp_pitch"));
        }
        return new ClipItem(r.id, m.id(), r.start, r.in, r.length, r.linkId,
                new AudioSettings(volume, fadeIn, fadeOut), new Extras(Map.of(), passthrough), effects, speed, keepPitch);
    }

    private String titleTextFromFile(Map<String, String> props) {
        String res = props.get("resource");
        if (res == null) {
            return "";
        }
        try {
            Path p = resolve(res);
            return Files.isRegularFile(p) ? TitleXml.textOf(Files.readString(p)) : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ subtitles

    /** Kdenlive's saved subtitles live next to the project; the filter's path may point to a session temp file. */
    private Subtitles readSubtitles(Element filter) {
        Map<String, String> props = Xml.properties(filter);
        String filename = props.get("av.filename");
        for (String k : List.of("av.filename", "av.alpha", Effect.SERVICE, "internal_added")) {
            props.remove(k);
        }
        List<Path> candidates = new ArrayList<>(List.of(KdenliveWriter.subtitleFile(file)));
        if (filename != null && !filename.isBlank()) {
            candidates.add(resolve(filename));
        }
        for (Path c : candidates) {
            try {
                if (Files.isRegularFile(c)) {
                    return AssFile.parse(Files.readString(c), fps, props);
                }
            } catch (Exception e) {
                // unreadable: try the next one
            }
        }
        return new Subtitles(null, Subtitles.Style.DEFAULT, List.of(), props);
    }

    // ------------------------------------------------------------------ compositions

    /** A user composition in the sequence, or null when its tracks cannot be resolved (kept as passthrough). */
    private Composition composition(Element t, List<String> names) {
        Map<String, String> props = Xml.properties(t);
        int b = (int) parseLong(props.get("b_track"), -1);
        int a = (int) parseLong(props.get("a_track"), 0);
        if (b < 1 || b > names.size() || a < 0 || a > names.size() || props.containsKey("kdenlive:mixcut")
                || !t.hasAttribute("out")) {
            return null;
        }
        long in = t.hasAttribute("in") ? MltTime.parse(t.getAttribute("in"), fps) : 0;
        long out = MltTime.parse(t.getAttribute("out"), fps);
        if (out < in) {
            return null;
        }
        String track = names.get(b - 1);
        boolean force = "1".equals(props.get("force_track"));
        props.remove("a_track");
        props.remove("b_track");
        props.remove("force_track");
        return new Composition(transitionId("comp:" + track + "@" + in), track, a == 0 ? null : names.get(a - 1), force,
                in, out - in + 1, props);
    }

    // ------------------------------------------------------------------ effects

    /** A user effect: Kdenlive tags every effect it creates with kdenlive_id. */
    private static boolean isEffect(Element filter) {
        String kid = Xml.prop(filter, Effect.KDENLIVE_ID);
        return kid != null && !kid.isBlank() && Xml.prop(filter, Effect.SERVICE) != null;
    }

    private Effect effectFrom(Element f, String key) {
        Long in = f.hasAttribute("in") ? MltTime.parse(f.getAttribute("in"), fps) : null;
        Long out = f.hasAttribute("out") ? MltTime.parse(f.getAttribute("out"), fps) : null;
        return new Effect(effectId(key), Xml.properties(f), in, out);
    }

    // ------------------------------------------------------------------ ids

    private String mediaId(int binId) {
        return storedId("media", Integer.toString(binId), "m");
    }

    private String idFor(String key, String prefix) {
        return storedId("items", key, prefix);
    }

    private String transitionId(String key) {
        return storedId("transitions", key, "x");
    }

    private String effectId(String key) {
        return storedId("effects", key, "e");
    }

    /** The id we saved for this key in the id map, if still free, else a new one. */
    private String storedId(String section, String key, String prefix) {
        String id = idMap.path(section).path(key).asText("");
        if (id.startsWith(prefix) && usedIds.add(id)) {
            bump(id);
            return id;
        }
        return newId(prefix);
    }

    private String newId(String prefix) {
        String id;
        do {
            id = prefix + nextId++;
        } while (!usedIds.add(id));
        return id;
    }

    private void bump(String id) {
        Matcher m = ID_NUM.matcher(id);
        if (m.matches()) {
            nextId = Math.max(nextId, Long.parseLong(m.group(1)) + 1);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Path resolve(String resource) {
        if (resource == null || resource.isBlank()) {
            return root.resolve("missing");
        }
        Path p = Path.of(resource);
        return (p.isAbsolute() ? p : root.resolve(p)).normalize();
    }

    private static Extras withXml(Extras e, String xml) {
        List<String> list = new ArrayList<>(e.xml());
        list.add(xml);
        return new Extras(e.properties(), list);
    }

    private static JsonNode parseJson(String text) {
        try {
            return text == null || text.isBlank() ? new ObjectMapper().createObjectNode() : new ObjectMapper().readTree(text);
        } catch (Exception e) {
            return new ObjectMapper().createObjectNode();
        }
    }

    /** "00:00:00.000=-6" or "-6" or "0=-6;100=-3" -> first value. */
    static double parseDb(String level) {
        if (level == null || level.isBlank()) {
            return 0;
        }
        String first = level.split(";")[0];
        String value = first.contains("=") ? first.substring(first.indexOf('=') + 1) : first;
        value = value.replaceAll("[^0-9.+-]", "");
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            try {
                return (long) Double.parseDouble(s.trim());
            } catch (NumberFormatException e2) {
                return fallback;
            }
        }
    }

    private static int intAttr(Element e, String name, int fallback) {
        return (int) parseLong(e.getAttribute(name), fallback);
    }
}
