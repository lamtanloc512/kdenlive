package dev.kdenlivemcp.backend.kdenlive;

import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.Profile;
import dev.kdenlivemcp.model.Subtitles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads and writes subtitle files: Kdenlive's ASS layout (SubtitleModel::saveSubtitleData), plus SRT/VTT import. */
public final class AssFile {

    private static final Pattern ASS_TIME = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})[.,](\\d{1,3})");
    private static final Pattern SRT_TIMES = Pattern.compile(
            "(\\d+:)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*(\\d+:)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})");
    static final String EVENTS_FORMAT = "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text";

    private AssFile() {
    }

    // ------------------------------------------------------------------ read

    public static Subtitles parse(String text, Fps fps, Map<String, String> filterProps) {
        String t = text.replace("\r\n", "\n").replace("\uFEFF", "");
        int events = t.indexOf("[Events]");
        if (events < 0) {
            Subtitles srt = parseSrt(t, fps);
            return new Subtitles(null, srt.style(), srt.lines(), filterProps);
        }
        List<Subtitles.Line> lines = new ArrayList<>();
        for (String line : t.substring(events).split("\n")) {
            if (!line.startsWith("Dialogue:")) {
                continue;
            }
            // Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text (text may contain commas)
            String[] f = line.substring("Dialogue:".length()).trim().split(",", 10);
            if (f.length < 10) {
                continue;
            }
            long start = assTime(f[1], fps);
            long end = Math.max(assTime(f[2], fps), start + 1);
            String extra = String.join(",", f[4], f[5], f[6], f[7], f[8]);
            lines.add(new Subtitles.Line(start, end, f[9].replace("\\N", "\n"), parseInt(f[0]), f[3].trim(), extra));
        }
        return new Subtitles(t.substring(0, events), Subtitles.Style.DEFAULT, lines, filterProps);
    }

    /** SRT or WebVTT text to subtitle lines (no styles). */
    public static Subtitles parseSrt(String text, Fps fps) {
        List<Subtitles.Line> lines = new ArrayList<>();
        String[] blocks = text.replace("\r\n", "\n").split("\n\\s*\n");
        for (String block : blocks) {
            String[] rows = block.strip().split("\n");
            for (int i = 0; i < rows.length; i++) {
                Matcher m = SRT_TIMES.matcher(rows[i]);
                if (m.find()) {
                    long start = fps.frames(seconds(m.group(1), m.group(2), m.group(3), m.group(4)));
                    long end = fps.frames(seconds(m.group(5), m.group(6), m.group(7), m.group(8)));
                    String body = String.join("\n", java.util.Arrays.copyOfRange(rows, i + 1, rows.length))
                            .replaceAll("</?[ibu]>", "").strip();
                    if (!body.isEmpty()) {
                        lines.add(new Subtitles.Line(start, Math.max(end, start + 1), body));
                    }
                    break;
                }
            }
        }
        if (lines.isEmpty()) {
            throw new EditException("no subtitles found (expected ASS, SRT or WebVTT)");
        }
        return new Subtitles(null, Subtitles.Style.DEFAULT, lines, Map.of());
    }

    // ------------------------------------------------------------------ write

    public static String write(Subtitles s, Profile profile, String kdenliveVersion) {
        StringBuilder sb = new StringBuilder();
        if (s.header() != null && !s.header().isBlank()) {
            sb.append(s.header());
            if (!s.header().endsWith("\n\n")) {
                sb.append(s.header().endsWith("\n") ? "\n" : "\n\n");
            }
        } else {
            sb.append(defaultHeader(s.style(), profile, kdenliveVersion));
        }
        sb.append("[Events]\n").append(EVENTS_FORMAT).append('\n');
        Fps fps = profile.fps();
        for (Subtitles.Line l : s.lines()) {
            sb.append("Dialogue: ").append(l.layer()).append(',').append(assTime(l.start(), fps)).append(',')
                    .append(assTime(l.end(), fps)).append(',').append(l.styleName()).append(',').append(l.extra())
                    .append(',').append(l.text().replace("\n", "\\N")).append('\n');
        }
        return sb.toString();
    }

    static String defaultHeader(Subtitles.Style st, Profile profile, String version) {
        int w = profile.width();
        int h = profile.height();
        double size = st.size() > 0 ? st.size() : h / 18.0;
        int marginV = st.marginV() > 0 ? st.marginV() : h / 27;
        int alignment = switch (st.position() == null ? "bottom" : st.position().toLowerCase(Locale.ROOT)) {
            case "top" -> 8;
            case "middle", "center" -> 5;
            default -> 2;
        };
        String style = String.format(Locale.ROOT,
                "Style: Default,%s,%.2f,%s,&H000000FF,%s,%s,%d,0,0,0,100.00,100.00,0.00,0.00,%d,%.2f,0.00,%d,10,10,%d,1",
                st.font(), size, assColor(st.color()), assColor(st.outlineColor()), assColor(st.backColor()),
                st.bold() ? -1 : 0, st.box() ? 3 : 1, st.outline(), alignment, marginV);
        return "[Script Info]\n; Script generated by Kdenlive " + version + "\nScriptType: v4.00+\nPlayResX: " + w
                + "\nPlayResY: " + h + "\nLayoutResX: " + w + "\nLayoutResY: " + h + "\nWrapStyle: 0\n"
                + "ScaledBorderAndShadow: yes\nYCbCr Matrix: None\n\n"
                + "[Kdenlive Extradata]\nMaxLayer: 0\nDefaultStyles: Default\n\n"
                + "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, "
                + "BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, "
                + "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n" + style + "\n\n";
    }

    /** #RRGGBB[AA] -> &HAABBGGRR (ASS alpha is inverted: 00 = opaque). */
    static String assColor(String css) {
        String hex = css == null ? "ffffff" : css.trim().replaceFirst("^#", "").replaceFirst("^0x", "");
        if (!hex.matches("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) {
            throw new EditException("'" + css + "' is not a color; use #RRGGBB or #RRGGBBAA");
        }
        int a = hex.length() == 8 ? Integer.parseInt(hex.substring(6), 16) : 255;
        return String.format(Locale.ROOT, "&H%02X%s%s%s", 255 - a, hex.substring(4, 6), hex.substring(2, 4),
                hex.substring(0, 2)).toUpperCase(Locale.ROOT);
    }

    static String assTime(long frames, Fps fps) {
        long cs = Math.round(fps.seconds(frames) * 100);
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", cs / 360000, (cs / 6000) % 60, (cs / 100) % 60, cs % 100);
    }

    static long assTime(String s, Fps fps) {
        Matcher m = ASS_TIME.matcher(s.trim());
        if (!m.find()) {
            return 0;
        }
        String frac = m.group(4);
        double fracSec = Integer.parseInt(frac) / Math.pow(10, frac.length());
        return fps.frames(Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60
                + Integer.parseInt(m.group(3)) + fracSec);
    }

    private static double seconds(String h, String m, String s, String frac) {
        double hours = h == null ? 0 : Integer.parseInt(h.substring(0, h.length() - 1));
        return hours * 3600 + Integer.parseInt(m) * 60 + Integer.parseInt(s) + Integer.parseInt(frac) / Math.pow(10, frac.length());
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
