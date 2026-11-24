package dev.kdenlivemcp.backend.kdenlive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.kdenlivemcp.media.MediaInfo;
import dev.kdenlivemcp.media.MediaKind;
import dev.kdenlivemcp.model.ClipItem;
import dev.kdenlivemcp.model.Effect;
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
import dev.kdenlivemcp.title.TitlePresets;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Writes a {@link Project} as a Kdenlive 26.08 document (version 1.1), following the layout of the
 * fixtures written by Kdenlive itself (see docs/kdenlive-format.md).
 */
public final class KdenliveWriter {

    public static final String KDENLIVE_VERSION = "26.08.3";
    public static final String MLT_VERSION = "7.43.0";
    /** Bin id of the "Sequences" folder and of the sequence clip, as Kdenlive assigns them. */
    static final int SEQUENCE_FOLDER_ID = 2;
    static final int SEQUENCE_BIN_ID = 3;
    static final String ID_MAP_PROPERTY = "kdenlive:docproperties.mcpidmap";
    /** DocumentMeta.extras property-name prefix for sequence-tractor properties we pass through. */
    static final String SEQ_PREFIX = "seq:";

    /** What to write: the main document plus generated title files (path -> content). */
    public record Output(String xml, Map<Path, String> titleFiles) {
    }

    /** One item as it is laid out in the file (mix-extended). */
    record Placed(TimelineItem item, int layer, long start, long in, long length) {
        long outInclusive() {
            return in + length - 1;
        }
    }

    private final Project p;
    private final Fps fps;
    private final TitlePresets presets;
    private final Path root;
    private final Document doc;
    private final Element mlt;
    private int producerN;
    private int chainN;
    private int playlistN;
    private int tractorN;
    private int filterN;
    private int transitionN;

    private final Map<String, Integer> titleBinIds = new LinkedHashMap<>();
    private final Map<String, String> titleProducerIds = new HashMap<>();
    private final Map<Path, String> titleFiles = new LinkedHashMap<>();
    private final Map<String, Placed> placedById = new HashMap<>();
    private final Map<String, Integer> trackIndexOfItem = new HashMap<>();

    /** Optional [from, to] frame range for the project tractor (frame grabs, partial renders). */
    private long[] playRange;
    /** Optional {width, height} written into the profile, to render a smaller copy natively. */
    private int[] outputSize;

    private KdenliveWriter(Project p, TitlePresets presets) {
        this.p = p;
        this.fps = p.fps();
        this.presets = presets;
        this.root = p.file().toAbsolutePath().getParent();
        this.doc = Xml.newDocument();
        this.mlt = doc.createElement("mlt");
        doc.appendChild(mlt);
    }

    public static Output write(Project project, TitlePresets presets) {
        return write(project, presets, null);
    }

    /**
     * @param playRange {from, to} (inclusive frames) to make melt play only part of the timeline, or null.
     *                  Seeking with melt's in=/out= on a whole document picks wrong frames; this does not.
     */
    public static Output write(Project project, TitlePresets presets, long[] playRange) {
        return write(project, presets, playRange, null);
    }

    /**
     * @param outputSize {width, height} to put in the profile so melt renders at that size natively. Resizing in
     *                   the consumer (width=/height=/s=) makes melt 7.43 stall near the end of the render.
     */
    public static Output write(Project project, TitlePresets presets, long[] playRange, int[] outputSize) {
        KdenliveWriter w = new KdenliveWriter(project, presets);
        w.playRange = playRange;
        w.outputSize = outputSize;
        w.build();
        return new Output(Xml.toString(w.doc, true), w.titleFiles);
    }

    // ------------------------------------------------------------------ document

    private void build() {
        mlt.setAttribute("LC_NUMERIC", "C");
        mlt.setAttribute("producer", "main_bin");
        mlt.setAttribute("root", root.toString());
        mlt.setAttribute("version", MLT_VERSION);
        writeProfile();
        assignTitleBinIds();

        long duration = p.duration();
        Element black = Xml.el(mlt, "producer", "id", "producer" + producerN++, "in", clock(0),
                "out", clock(duration + 300L * fps.num() / fps.den()));
        Xml.prop(black, "length", 2147483647);
        Xml.prop(black, "eof", "continue");
        Xml.prop(black, "resource", "black");
        Xml.prop(black, "aspect_ratio", 1);
        Xml.prop(black, "mlt_service", "color");
        Xml.prop(black, "kdenlive:playlistid", "black_track");
        Xml.prop(black, "mlt_image_format", "rgba");
        Xml.prop(black, "set.test_audio", 0);

        List<Track> bottomUp = bottomUp();
        List<String> trackTractors = new ArrayList<>();
        for (int i = 0; i < bottomUp.size(); i++) {
            trackTractors.add(writeTrack(bottomUp.get(i), i));
        }
        writeSequence(bottomUp, trackTractors, black.getAttribute("id"), duration);
        Map<String, String> binChains = new LinkedHashMap<>();
        for (MediaClip m : p.bin()) {
            binChains.put(m.id(), writeMediaProducer(m, null));
        }
        writeMainBin(binChains, duration);
        long from = playRange == null ? 0 : playRange[0];
        long to = playRange == null ? Math.max(0, duration - 1) : playRange[1];
        Element project = Xml.el(mlt, "tractor", "id", "tractor" + tractorN++, "in", clock(0),
                "out", clock(to - from));
        Xml.prop(project, "kdenlive:projectTractor", 1);
        Xml.el(project, "track", "in", clock(from), "out", clock(to), "producer", p.meta().sequenceUuid());
    }

    private void writeProfile() {
        Profile pr = p.profile();
        Xml.el(mlt, "profile",
                "colorspace", Integer.toString(pr.colorspace()),
                "description", pr.description(),
                "display_aspect_den", Integer.toString(pr.displayAspectDen()),
                "display_aspect_num", Integer.toString(pr.displayAspectNum()),
                "frame_rate_den", Integer.toString(fps.den()),
                "frame_rate_num", Integer.toString(fps.num()),
                "height", Integer.toString(outputSize == null ? pr.height() : outputSize[1]),
                "progressive", pr.progressive() ? "1" : "0",
                "sample_aspect_den", Integer.toString(pr.sampleAspectDen()),
                "sample_aspect_num", Integer.toString(pr.sampleAspectNum()),
                "width", Integer.toString(outputSize == null ? pr.width() : outputSize[0]));
    }

    private List<Track> bottomUp() {
        List<Track> list = new ArrayList<>(p.tracks());
        java.util.Collections.reverse(list);
        return list;
    }

    private void assignTitleBinIds() {
        int next = Math.max(SEQUENCE_BIN_ID, p.bin().stream().mapToInt(MediaClip::binId).max().orElse(SEQUENCE_BIN_ID)) + 1;
        for (Track t : bottomUp()) {
            for (TimelineItem i : t.items()) {
                if (i instanceof TitleItem) {
                    titleBinIds.put(i.id(), next++);
                }
            }
        }
    }

    // ------------------------------------------------------------------ tracks

    private String writeTrack(Track track, int index) {
        boolean audio = track.kind() == TrackKind.AUDIO;
        List<Placed> placed = place(track);
        placed.forEach(pl -> {
            placedById.put(pl.item().id(), pl);
            trackIndexOfItem.put(pl.item().id(), index);
        });

        // per-track producer copies, defined before use
        Map<String, String> chainsByMedia = new LinkedHashMap<>();
        for (Placed pl : placed.stream().sorted(Comparator.comparingLong(Placed::start)).toList()) {
            switch (pl.item()) {
                case ClipItem c -> chainsByMedia.computeIfAbsent(c.mediaId(),
                        id -> writeMediaProducer(p.media(id).orElseThrow(), track.kind()));
                case TitleItem t -> titleProducerIds.put(t.id(), writeTitleProducer(t, pl));
            }
        }

        String[] playlists = new String[2];
        long end = 0;
        for (int layer = 0; layer < 2; layer++) {
            Element pl = Xml.el(mlt, "playlist", "id", "playlist" + playlistN++);
            playlists[layer] = pl.getAttribute("id");
            if (audio) {
                Xml.prop(pl, "kdenlive:audio_track", 1);
            }
            long cursor = 0;
            final int l = layer;
            for (Placed item : placed.stream().filter(x -> x.layer() == l)
                    .sorted(Comparator.comparingLong(Placed::start)).toList()) {
                if (item.start() > cursor) {
                    Xml.el(pl, "blank", "length", clock(item.start() - cursor));
                }
                writeEntry(pl, item, track, chainsByMedia);
                cursor = item.start() + item.length();
                end = Math.max(end, cursor);
            }
        }

        Element tractor = Xml.el(mlt, "tractor", "id", "tractor" + tractorN++, "in", clock(0));
        if (end > 0) {
            tractor.setAttribute("out", clock(end - 1));
        }
        if (audio) {
            Xml.prop(tractor, "kdenlive:audio_track", 1);
        }
        Xml.prop(tractor, "kdenlive:trackheight", 75);
        Xml.prop(tractor, "kdenlive:timeline_active", 1);
        Xml.prop(tractor, "kdenlive:collapsed", 0);
        Xml.prop(tractor, "kdenlive:thumbs_format", null);
        Xml.prop(tractor, "kdenlive:audio_rec", null);
        if (track.locked()) {
            Xml.prop(tractor, "kdenlive:locked_track", 1);
        }
        if (track.muted()) {
            Xml.prop(tractor, "kdenlive:mcp.muted", 1);
        }
        track.extras().properties().forEach((k, v) -> Xml.prop(tractor, k, v));
        String hide = audio ? "video" : "audio";
        if (audio && track.muted()) {
            hide = "both";
        } else if (!audio && track.hidden()) {
            hide = "both";
        }
        Xml.el(tractor, "track", "hide", hide, "producer", playlists[0]);
        Xml.el(tractor, "track", "hide", hide, "producer", playlists[1]);
        for (Transition t : p.transitions()) {
            if (t instanceof Mix m && placedById.containsKey(m.itemA()) && placed.contains(placedById.get(m.itemA()))) {
                writeMix(tractor, m, audio);
            }
        }
        if (audio) {
            internalAudioFilters(tractor, true);
        }
        track.effects().forEach(fx -> writeEffect(tractor, fx));
        track.extras().xml().forEach(x -> Xml.appendFragment(tractor, renumber(x)));
        return tractor.getAttribute("id");
    }

    /** Lays out a track's items in Kdenlive's two-playlist form, extending mixed items. */
    private List<Placed> place(Track track) {
        Map<String, int[]> layer = new HashMap<>();
        Map<String, long[]> geo = new LinkedHashMap<>(); // id -> {start, in, length}
        for (TimelineItem i : track.items()) {
            long in = i instanceof ClipItem c ? c.in() : 0;
            geo.put(i.id(), new long[] {i.start(), in, i.length()});
            layer.put(i.id(), new int[] {0});
        }
        List<Mix> mixes = p.transitions().stream().filter(t -> t instanceof Mix).map(t -> (Mix) t)
                .filter(m -> geo.containsKey(m.itemA()) && geo.containsKey(m.itemB()))
                .sorted(Comparator.comparingLong(m -> geo.get(m.itemB())[0])).toList();
        for (Mix m : mixes) {
            layer.get(m.itemB())[0] = 1 - layer.get(m.itemA())[0];
            long[] a = geo.get(m.itemA());
            long[] b = geo.get(m.itemB());
            a[2] += m.length() - m.cutOffset();
            b[0] -= m.cutOffset();
            b[1] -= m.cutOffset();
            b[2] += m.cutOffset();
        }
        List<Placed> out = new ArrayList<>();
        for (TimelineItem i : track.items()) {
            long[] g = geo.get(i.id());
            long in = i instanceof TitleItem ? 0 : g[1];
            out.add(new Placed(i, layer.get(i.id())[0], g[0], in, g[2]));
        }
        return out;
    }

    private void writeEntry(Element playlist, Placed pl, Track track, Map<String, String> chains) {
        String producer = switch (pl.item()) {
            case ClipItem c -> chains.get(c.mediaId());
            case TitleItem t -> titleProducerIds.get(t.id());
        };
        Element e = Xml.el(playlist, "entry", "in", clock(pl.in()), "out", clock(pl.outInclusive()),
                "producer", producer);
        int binId = switch (pl.item()) {
            case ClipItem c -> p.media(c.mediaId()).orElseThrow().binId();
            case TitleItem t -> titleBinIds.get(t.id());
        };
        Xml.prop(e, "kdenlive:id", binId);
        List<Runnable> filters = new ArrayList<>();
        switch (pl.item()) {
            case ClipItem c -> {
                if (track.kind() == TrackKind.AUDIO) {
                    if (c.audio().volumeDb() != 0) {
                        filters.add(() -> volume(e, c.audio().volumeDb()));
                    }
                    if (c.audio().fadeIn() > 0) {
                        filters.add(() -> audioFade(e, "fadein", pl.in(), pl.in() + c.audio().fadeIn()));
                    }
                    if (c.audio().fadeOut() > 0) {
                        filters.add(() -> audioFade(e, "fadeout", pl.outInclusive() - c.audio().fadeOut(),
                                pl.outInclusive()));
                    }
                }
                c.effects().forEach(fx -> filters.add(() -> writeEffect(e, fx)));
                fadeToBlack(c.id()).ifPresent(f -> filters.add(() -> fadeToBlackFilter(e, pl, f.length(), true)));
                c.extras().xml().forEach(x -> filters.add(() -> Xml.appendFragment(e, renumber(x))));
            }
            case TitleItem t -> {
                if (t.fadeIn() > 0) {
                    filters.add(() -> {
                        Element f = filter(e, null, clock(t.fadeIn()));
                        Xml.prop(f, "start", 1);
                        Xml.prop(f, "level", 1);
                        Xml.prop(f, "mlt_service", "brightness");
                        Xml.prop(f, "kdenlive_id", "fade_from_black");
                        Xml.prop(f, "alpha", "0=0;-1=1");
                        Xml.prop(f, "kdenlive:collapsed", 0);
                    });
                }
                t.effects().forEach(fx -> filters.add(() -> writeEffect(e, fx)));
                if (t.fadeOut() > 0) {
                    filters.add(() -> fadeToBlackFilter(e, pl, t.fadeOut(), false));
                }
                fadeToBlack(t.id()).ifPresent(f -> filters.add(() -> fadeToBlackFilter(e, pl, f.length(), true)));
                t.extras().xml().forEach(x -> filters.add(() -> Xml.appendFragment(e, renumber(x))));
            }
        }
        if (!filters.isEmpty()) {
            Xml.prop(e, "kdenlive:activeeffect", 0);
            filters.forEach(Runnable::run);
        }
    }

    private java.util.Optional<FadeToBlack> fadeToBlack(String itemId) {
        return p.transitions().stream().filter(t -> t instanceof FadeToBlack f && f.itemId().equals(itemId))
                .map(t -> (FadeToBlack) t).findFirst();
    }

    private Element filter(Element parent, String in, String out) {
        Element f = Xml.el(parent, "filter", "id", "filter" + filterN++);
        if (in != null) {
            f.setAttribute("in", in);
        }
        if (out != null) {
            f.setAttribute("out", out);
        }
        return f;
    }

    /** A user effect, properties in their stored order (Kdenlive's own layout for effects it wrote). */
    private void writeEffect(Element parent, Effect fx) {
        Element f = filter(parent, fx.in() == null ? null : clock(fx.in()), fx.out() == null ? null : clock(fx.out()));
        fx.props().forEach((k, v) -> Xml.prop(f, k, v));
    }

    private void volume(Element entry, double db) {
        Element f = filter(entry, null, null);
        Xml.prop(f, "window", 75);
        Xml.prop(f, "max_gain", "20dB");
        Xml.prop(f, "level", clock(0) + "=" + num(db));
        Xml.prop(f, "channel_mask", -1);
        Xml.prop(f, "mlt_service", "volume");
        Xml.prop(f, "kdenlive_id", "volume");
        Xml.prop(f, "kdenlive:collapsed", 0);
    }

    private void audioFade(Element entry, String kind, long from, long to) {
        Element f = filter(entry, from <= 0 ? null : clock(from), clock(to)); // Kdenlive omits in="0"
        Xml.prop(f, "window", 75);
        Xml.prop(f, "max_gain", "20dB");
        Xml.prop(f, "channel_mask", -1);
        Xml.prop(f, "mlt_service", "volume");
        Xml.prop(f, "kdenlive_id", kind);
        Xml.prop(f, "gain", "fadein".equals(kind) ? 0 : 1);
        Xml.prop(f, "end", "fadein".equals(kind) ? 1 : 0);
        Xml.prop(f, "kdenlive:collapsed", 0);
    }

    /** toBlack=true: fade the picture to black; false: fade the alpha (titles). */
    private void fadeToBlackFilter(Element entry, Placed pl, long length, boolean toBlack) {
        Element f = filter(entry, clock(Math.max(pl.in(), pl.outInclusive() - length)), clock(pl.outInclusive()));
        Xml.prop(f, "start", 1);
        Xml.prop(f, "level", toBlack ? "0=1;-1=0" : "1");
        Xml.prop(f, "mlt_service", "brightness");
        Xml.prop(f, "kdenlive_id", "fade_to_black");
        Xml.prop(f, "alpha", toBlack ? "1" : "0=1;-1=0");
        Xml.prop(f, "kdenlive:collapsed", 0);
    }

    private void writeMix(Element tractor, Mix m, boolean audio) {
        Placed a = placedById.get(m.itemA());
        Placed b = placedById.get(m.itemB());
        long start = b.start();
        Element t = Xml.el(tractor, "transition", "id", "transition" + transitionN++, "in", clock(start),
                "out", clock(start + m.length()));
        Xml.prop(t, "a_track", 0);
        Xml.prop(t, "b_track", 1);
        String reverse = a.layer() == 1 ? "1" : "0";
        if (audio) {
            Xml.prop(t, "duck_threshold", 0);
            Xml.prop(t, "duck_attenuation", -12);
            Xml.prop(t, "duck_level", 0);
            Xml.prop(t, "duck_fade_in", 1500);
            Xml.prop(t, "duck_fade_out", 250);
            Xml.prop(t, "prefix", null);
            Xml.prop(t, "mlt_service", "mix");
            Xml.prop(t, "kdenlive_id", "mix");
            Xml.prop(t, "kdenlive:mixcut", m.cutOffset());
            Xml.prop(t, "start", -1);
            Xml.prop(t, "accepts_blanks", 1);
            Xml.prop(t, "reverse", reverse);
        } else {
            Xml.prop(t, "factory", "loader");
            Xml.prop(t, "resource", m.type() == MixType.WIPE ? "%luma01.pgm" : null);
            Xml.prop(t, "mlt_service", "luma");
            Xml.prop(t, "kdenlive_id", "luma");
            Xml.prop(t, "kdenlive:mixcut", m.cutOffset());
            Xml.prop(t, "softness", m.type() == MixType.WIPE ? "0.2" : "0");
            Xml.prop(t, "alpha_over", 0);
            Xml.prop(t, "fix_background_alpha", 1);
            Xml.prop(t, "invert", 0);
            Xml.prop(t, "reverse", reverse);
        }
    }

    private void internalAudioFilters(Element tractor, boolean withLevel) {
        Element v = filter(tractor, null, null);
        Xml.prop(v, "window", 75);
        Xml.prop(v, "max_gain", "20dB");
        Xml.prop(v, "channel_mask", -1);
        Xml.prop(v, "mlt_service", "volume");
        Xml.prop(v, "internal_added", 237);
        Xml.prop(v, "disable", 1);
        Element pan = filter(tractor, null, null);
        Xml.prop(pan, "channel", -1);
        Xml.prop(pan, "mlt_service", "panner");
        Xml.prop(pan, "internal_added", 237);
        Xml.prop(pan, "start", 0.5);
        Xml.prop(pan, "disable", 1);
        if (withLevel) {
            Element lvl = filter(tractor, null, null);
            Xml.prop(lvl, "iec_scale", 0);
            Xml.prop(lvl, "mlt_service", "audiolevel");
            Xml.prop(lvl, "internal_added", 237);
            Xml.prop(lvl, "dbpeak", 1);
            Xml.prop(lvl, "disable", 1);
        }
    }

    // ------------------------------------------------------------------ producers

    /** trackKind null = bin copy. */
    private String writeMediaProducer(MediaClip m, TrackKind trackKind) {
        MediaInfo info = m.info();
        boolean image = info.stillImage();
        long length = image ? fps.frames(5) : Math.max(1, m.lengthFrames(fps));
        String id = image ? "producer" + producerN++ : "chain" + chainN++;
        Element c = Xml.el(mlt, image ? "producer" : "chain", "id", id);
        if (!image) {
            c.setAttribute("out", clock(length - 1));
        } else {
            c.setAttribute("in", clock(0));
            c.setAttribute("out", clock(length - 1));
        }
        Map<String, String> extra = new LinkedHashMap<>(m.extras().properties());
        Xml.prop(c, "length", image ? 2147483647 : length);
        Xml.prop(c, "eof", "pause");
        Xml.prop(c, "resource", resource(m.path()));
        if (image) {
            Xml.prop(c, "ttl", 25);
            Xml.prop(c, "aspect_ratio", 1);
            Xml.prop(c, "mlt_service", extra.getOrDefault("mlt_service", "qimage"));
        } else {
            boolean audioOnly = m.kind() == MediaKind.AUDIO;
            Xml.prop(c, "mlt_service", trackKind == null && audioOnly ? "avformat" : "avformat-novalidate");
            Xml.prop(c, "seekable", 1);
            if (m.kind() == MediaKind.AV) {
                Xml.prop(c, "format", 3);
            }
            int audioIndex = intProp(extra, "audio_index", m.kind().hasAudio() ? (m.kind() == MediaKind.AV ? 1 : 0) : -1);
            int videoIndex = intProp(extra, "video_index", m.kind().hasVideo() ? 0 : -1);
            Xml.prop(c, "audio_index", audioIndex);
            Xml.prop(c, "video_index", videoIndex);
            if (videoIndex >= 0) {
                Xml.prop(c, "vstream", 0);
            }
            if (audioIndex >= 0) {
                Xml.prop(c, "astream", 0);
            }
        }
        Xml.prop(c, "kdenlive:folderid", -1);
        Xml.prop(c, "kdenlive:id", m.binId());
        Xml.prop(c, "kdenlive:control_uuid", extra.getOrDefault("kdenlive:control_uuid", controlUuid(m)));
        Xml.prop(c, "kdenlive:clip_type", clipType(m.kind()));
        if (!image) {
            Xml.prop(c, "mute_on_pause", 0);
        }
        String hash = extra.get("kdenlive:file_hash");
        if (hash == null) {
            hash = FileHash.of(m.path());
        }
        if (hash != null) {
            Xml.prop(c, "kdenlive:file_size", extra.getOrDefault("kdenlive:file_size", Long.toString(info.fileSize())));
            Xml.prop(c, "kdenlive:file_hash", hash);
        }
        Xml.prop(c, "kdenlive:clipname", m.name());
        for (String managed : List.of("length", "eof", "resource", "mlt_service", "seekable", "format", "audio_index",
                "video_index", "vstream", "astream", "kdenlive:folderid", "kdenlive:id", "kdenlive:control_uuid",
                "kdenlive:clip_type", "mute_on_pause", "kdenlive:file_size", "kdenlive:file_hash", "kdenlive:clipname",
                "set.test_audio", "set.test_image", "kdenlive:monitorPosition", "ttl", "aspect_ratio")) {
            extra.remove(managed);
        }
        extra.forEach((k, v) -> Xml.prop(c, k, v));
        Xml.prop(c, "xml", "was here");
        if (trackKind == TrackKind.VIDEO) {
            Xml.prop(c, "set.test_audio", 1);
            Xml.prop(c, "set.test_image", 0);
        } else if (trackKind == TrackKind.AUDIO) {
            Xml.prop(c, "set.test_audio", 0);
            Xml.prop(c, "set.test_image", 1);
        } else {
            Xml.prop(c, "kdenlive:monitorPosition", 0);
        }
        return id;
    }

    private String writeTitleProducer(TitleItem t, Placed pl) {
        String id = "producer" + producerN++;
        long len = pl.length();
        Element e = Xml.el(mlt, "producer", "id", id, "in", clock(0), "out", clock(len - 1));
        Xml.prop(e, "length", len);
        Xml.prop(e, "eof", "pause");
        if (TitleItem.EXTERNAL.equals(t.styleId())) {
            Map<String, String> props = new LinkedHashMap<>(t.extras().properties());
            props.remove("length");
            props.remove("eof");
            props.remove("kdenlive:id");
            props.remove("kdenlive:duration");
            props.forEach((k, v) -> Xml.prop(e, k, v));
        } else {
            Path file = titleFile(t);
            titleFiles.put(file, TitleXml.build(t.text(), presets.get(t.styleId()), p.profile(), len));
            Xml.prop(e, "resource", resource(file));
            Xml.prop(e, "aspect_ratio", 1);
            Xml.prop(e, "seekable", 1);
            Xml.prop(e, "mlt_service", "kdenlivetitle");
            Xml.prop(e, "xml", "was here");
            Xml.prop(e, "kdenlive:folderid", -1);
            Xml.prop(e, "kdenlive:clip_type", 2);
            Xml.prop(e, "kdenlive:clipname", "Title: " + Draftless.abbreviate(t.text()));
            Xml.prop(e, "force_reload", 0);
        }
        Xml.prop(e, "kdenlive:duration", MltTime.timecode(len, fps));
        Xml.prop(e, "kdenlive:id", titleBinIds.get(t.id()));
        return id;
    }

    /** Title documents live next to the project: {@code <name>.titles/<itemId>.kdenlivetitle}. */
    Path titleFile(TitleItem t) {
        String base = p.file().getFileName().toString().replaceFirst("\\.kdenlive$", "");
        return root.resolve(base + ".titles").resolve(t.id() + ".kdenlivetitle");
    }

    // ------------------------------------------------------------------ sequence and bin

    private void writeSequence(List<Track> bottomUp, List<String> tractors, String blackId, long duration) {
        String uuid = p.meta().sequenceUuid();
        Element seq = Xml.el(mlt, "tractor", "id", uuid, "in", clock(0),
                "out", duration > 0 ? clock(duration - 1) : clock(300L * fps.num() / fps.den()));
        int v1 = -1;
        int a1 = -1;
        for (int i = 0; i < bottomUp.size(); i++) {
            if (bottomUp.get(i).id().equals("V1")) {
                v1 = i;
            }
            if (bottomUp.get(i).id().equals("A1")) {
                a1 = i;
            }
        }
        Map<String, String> seqExtras = new LinkedHashMap<>();
        p.meta().extras().properties().forEach((k, v) -> {
            if (k.startsWith(SEQ_PREFIX)) {
                seqExtras.put(k.substring(SEQ_PREFIX.length()), v);
            }
        });
        Xml.prop(seq, "kdenlive:uuid", uuid);
        Xml.prop(seq, "kdenlive:clipname", seqExtras.getOrDefault("kdenlive:clipname", "Sequence 1"));
        Xml.prop(seq, "kdenlive:sequenceproperties.hasAudio",
                bottomUp.stream().anyMatch(t -> t.kind() == TrackKind.AUDIO) ? 1 : 0);
        Xml.prop(seq, "kdenlive:sequenceproperties.hasVideo",
                bottomUp.stream().anyMatch(t -> t.kind() == TrackKind.VIDEO) ? 1 : 0);
        Xml.prop(seq, "kdenlive:sequenceproperties.activeTrack", Math.max(0, v1));
        Xml.prop(seq, "kdenlive:sequenceproperties.tracksCount", bottomUp.size());
        Xml.prop(seq, "kdenlive:sequenceproperties.documentuuid", uuid);
        Xml.prop(seq, "kdenlive:duration", duration > 0 ? clock(duration) : "00:00:00:01");
        Xml.prop(seq, "kdenlive:maxduration", Math.max(1, duration));
        Xml.prop(seq, "kdenlive:producer_type", 17);
        Xml.prop(seq, "kdenlive:id", SEQUENCE_BIN_ID);
        Xml.prop(seq, "kdenlive:clip_type", 0);
        Xml.prop(seq, "kdenlive:folderid", SEQUENCE_FOLDER_ID);
        Xml.prop(seq, "kdenlive:sequenceproperties.audioTarget", Math.max(0, a1));
        Xml.prop(seq, "kdenlive:sequenceproperties.disablepreview", 0);
        Xml.prop(seq, "kdenlive:sequenceproperties.position", 0);
        Xml.prop(seq, "kdenlive:sequenceproperties.scrollPos", 0);
        Xml.prop(seq, "kdenlive:sequenceproperties.tracks", bottomUp.size());
        Xml.prop(seq, "kdenlive:sequenceproperties.verticalzoom", 1);
        Xml.prop(seq, "kdenlive:sequenceproperties.videoTarget", Math.max(0, v1));
        Xml.prop(seq, "kdenlive:sequenceproperties.zonein", 0);
        Xml.prop(seq, "kdenlive:sequenceproperties.zoneout", 75);
        Xml.prop(seq, "kdenlive:sequenceproperties.zoom", 8);
        Xml.prop(seq, "kdenlive:sequenceproperties.groups", groupsJson());
        Xml.prop(seq, "kdenlive:sequenceproperties.guides",
                seqExtras.getOrDefault("kdenlive:sequenceproperties.guides", "[\n]\n"));
        seqExtras.forEach((k, v) -> {
            if (!k.equals("kdenlive:clipname") && !k.equals("kdenlive:sequenceproperties.guides")) {
                Xml.prop(seq, k, v);
            }
        });
        Xml.el(seq, "track", "producer", blackId);
        tractors.forEach(t -> Xml.el(seq, "track", "producer", t));
        for (int i = 0; i < bottomUp.size(); i++) {
            Element t = Xml.el(seq, "transition", "id", "transition" + transitionN++);
            Xml.prop(t, "a_track", 0);
            Xml.prop(t, "b_track", i + 1);
            if (bottomUp.get(i).kind() == TrackKind.AUDIO) {
                Xml.prop(t, "duck_threshold", 0);
                Xml.prop(t, "duck_attenuation", -12);
                Xml.prop(t, "duck_level", 0);
                Xml.prop(t, "duck_fade_in", 1500);
                Xml.prop(t, "duck_fade_out", 250);
                Xml.prop(t, "prefix", null);
                Xml.prop(t, "mlt_service", "mix");
                Xml.prop(t, "kdenlive_id", "mix");
                Xml.prop(t, "internal_added", 237);
                Xml.prop(t, "always_active", 1);
                Xml.prop(t, "accepts_blanks", 1);
                Xml.prop(t, "sum", 1);
            } else {
                Xml.prop(t, "compositing", 0);
                Xml.prop(t, "distort", 0);
                Xml.prop(t, "rotate_center", 0);
                Xml.prop(t, "mlt_service", "qtblend");
                Xml.prop(t, "kdenlive_id", "qtblend");
                Xml.prop(t, "internal_added", 237);
                Xml.prop(t, "always_active", 1);
            }
        }
        p.meta().extras().xml().forEach(x -> Xml.appendFragment(seq, renumber(x)));
        internalAudioFilters(seq, false);
        p.masterEffects().forEach(fx -> writeEffect(seq, fx));
    }

    private String groupsJson() {
        ObjectMapper om = new ObjectMapper();
        ArrayNode groups = om.createArrayNode();
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Track t : bottomUp().reversed()) { // top to bottom: video first, as Kdenlive writes
            for (TimelineItem i : t.items()) {
                if (i instanceof ClipItem c && c.linkId() != null && placedById.containsKey(c.linkId())
                        && done.add(c.id()) && done.add(c.linkId())) {
                    ObjectNode g = groups.addObject();
                    ArrayNode children = g.putArray("children");
                    for (String id : List.of(c.id(), c.linkId())) {
                        ObjectNode leaf = children.addObject();
                        leaf.put("data", trackIndexOfItem.get(id) + ":" + placedById.get(id).start() + ":-1");
                        leaf.put("leaf", "clip");
                        leaf.put("type", "Leaf");
                    }
                    g.put("type", "AVSplit");
                }
            }
        }
        try {
            return om.writerWithDefaultPrettyPrinter().writeValueAsString(groups) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeMainBin(Map<String, String> binChains, long duration) {
        Element bin = Xml.el(mlt, "playlist", "id", "main_bin");
        String uuid = p.meta().sequenceUuid();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kdenlive:folder.-1." + SEQUENCE_FOLDER_ID, "Sequences");
        props.put("kdenlive:sequenceFolder", SEQUENCE_FOLDER_ID);
        props.put("kdenlive:docproperties.audioChannels", 2);
        props.put("kdenlive:docproperties.binsort", 0);
        props.put("kdenlive:docproperties.documentid", p.meta().documentId());
        props.put("kdenlive:docproperties.enableTimelineZone", 0);
        props.put("kdenlive:docproperties.enableexternalproxy", 0);
        props.put("kdenlive:docproperties.enableproxy", 0);
        props.put("kdenlive:docproperties.externalproxyparams", null);
        props.put("kdenlive:docproperties.generateimageproxy", 0);
        props.put("kdenlive:docproperties.generateproxy", 0);
        props.put("kdenlive:docproperties.kdenliveversion", KDENLIVE_VERSION);
        props.put("kdenlive:docproperties.patchversion", 1);
        props.put("kdenlive:docproperties.previewextension", null);
        props.put("kdenlive:docproperties.previewparameters", null);
        props.put("kdenlive:docproperties.profile", p.profile().mltName());
        props.put("kdenlive:docproperties.proxyextension", null);
        props.put("kdenlive:docproperties.proxyimageminsize", 2000);
        props.put("kdenlive:docproperties.proxyimagesize", 800);
        props.put("kdenlive:docproperties.proxyminsize", 1000);
        props.put("kdenlive:docproperties.proxyparams", null);
        props.put("kdenlive:docproperties.proxyresize", 640);
        props.put("kdenlive:docproperties.seekOffset", 9000);
        props.put("kdenlive:docproperties.sessionid", "{" + UUID.randomUUID() + "}");
        props.put("kdenlive:docproperties.uuid", uuid);
        props.put("kdenlive:docproperties.version", "1.1");
        props.put("kdenlive:expandedFolders", null);
        props.put("kdenlive:binZoom", 4);
        props.put("kdenlive:extraBins", "project_bin:-1:0");
        props.put("kdenlive:documentnotes", null);
        props.put("kdenlive:documentnotesversion", 2);
        props.put("kdenlive:docproperties.opensequences", uuid);
        props.put("kdenlive:docproperties.activetimeline", uuid);
        // pass through what Kdenlive stored (layout, guide categories, notes, ...), never our managed values
        p.meta().extras().properties().forEach((k, v) -> {
            if (!k.startsWith(SEQ_PREFIX) && !k.equals(ID_MAP_PROPERTY) && !MANAGED_BIN_PROPS.contains(k)) {
                props.put(k, v);
            }
        });
        props.put(ID_MAP_PROPERTY, idMapJson());
        props.forEach((k, v) -> Xml.prop(bin, k, v));
        Xml.prop(bin, "xml_retain", 1);
        Xml.el(bin, "entry", "in", clock(0), "out", clock(0), "producer", uuid);
        for (MediaClip m : p.bin()) {
            long len = m.info().stillImage() ? fps.frames(5) : Math.max(1, m.lengthFrames(fps));
            Xml.el(bin, "entry", "in", clock(0), "out", clock(len - 1), "producer", binChains.get(m.id()));
        }
        for (Map.Entry<String, Integer> t : titleBinIds.entrySet()) {
            Placed pl = placedById.get(t.getKey());
            Xml.el(bin, "entry", "in", clock(0), "out", clock(pl.length() - 1), "producer",
                    titleProducerIds.get(t.getKey()));
        }
    }

    static final java.util.Set<String> MANAGED_BIN_PROPS = java.util.Set.of(
            "kdenlive:docproperties.documentid", "kdenlive:docproperties.kdenliveversion",
            "kdenlive:docproperties.profile", "kdenlive:docproperties.sessionid", "kdenlive:docproperties.uuid",
            "kdenlive:docproperties.version", "kdenlive:docproperties.opensequences",
            "kdenlive:docproperties.activetimeline", "kdenlive:sequenceFolder", "xml_retain");

    /** Our short ids keyed by stable file facts, so they survive a save/open cycle. */
    private String idMapJson() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode root = om.createObjectNode();
        root.put("next", p.nextId());
        ObjectNode media = root.putObject("media");
        p.bin().forEach(m -> media.put(Integer.toString(m.binId()), m.id()));
        ObjectNode items = root.putObject("items");
        ObjectNode titles = root.putObject("titles");
        for (Track t : p.tracks()) {
            for (TimelineItem i : t.items()) {
                items.put(t.id() + "@" + i.start(), i.id());
                if (i instanceof TitleItem ti) {
                    ObjectNode o = titles.putObject(ti.id());
                    o.put("style", ti.styleId());
                    o.put("text", ti.text());
                    o.put("fadeIn", ti.fadeIn());
                    o.put("fadeOut", ti.fadeOut());
                }
            }
        }
        ObjectNode effects = root.putObject("effects");
        for (Track t : p.tracks()) {
            for (int k = 0; k < t.effects().size(); k++) {
                effects.put(t.id() + "#" + k, t.effects().get(k).id());
            }
            for (TimelineItem i : t.items()) {
                for (int k = 0; k < i.effects().size(); k++) {
                    effects.put(i.id() + "#" + k, i.effects().get(k).id());
                }
            }
        }
        for (int k = 0; k < p.masterEffects().size(); k++) {
            effects.put(Project.MASTER + "#" + k, p.masterEffects().get(k).id());
        }
        ObjectNode transitions = root.putObject("transitions");
        for (Transition t : p.transitions()) {
            switch (t) {
                case Mix m -> transitions.put("mix:" + m.itemA(), m.id());
                case FadeToBlack f -> transitions.put("fade:" + f.itemId(), f.id());
            }
        }
        return root.toString();
    }

    // ------------------------------------------------------------------ helpers

    private String clock(long frames) {
        return MltTime.clock(frames, fps);
    }

    private String resource(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (abs.startsWith(root)) {
            return root.relativize(abs).toString().replace('\\', '/');
        }
        return abs.toString();
    }

    private String controlUuid(MediaClip m) {
        String seed = p.meta().sequenceUuid() + "/" + m.binId() + "/" + m.path();
        return "{" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)) + "}";
    }

    static int clipType(MediaKind kind) {
        return switch (kind) {
            case AV -> 0;
            case AUDIO -> 1;
            case VIDEO -> 2;
            case IMAGE -> 5;
        };
    }

    private static int intProp(Map<String, String> props, String name, int fallback) {
        try {
            return props.containsKey(name) ? Integer.parseInt(props.get(name)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Passthrough fragments keep their content but get fresh ids so ids stay unique. */
    private String renumber(String xml) {
        Element e = Xml.parseString(xml).getDocumentElement();
        if (e.hasAttribute("id")) {
            String tag = e.getTagName();
            e.setAttribute("id", tag.equals("transition") ? "transition" + transitionN++ : "filter" + filterN++);
        }
        return Xml.toString(e, false);
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    /** Minimal text helper (edit.Draft's is package-private). */
    static final class Draftless {
        private Draftless() {
        }

        static String abbreviate(String s) {
            String one = s.replaceAll("\\s+", " ").strip();
            return one.length() <= 24 ? one : one.substring(0, 23) + "…";
        }
    }
}
