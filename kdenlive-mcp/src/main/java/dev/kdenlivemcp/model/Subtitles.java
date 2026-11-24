package dev.kdenlivemcp.model;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The timeline's subtitle track, as Kdenlive keeps it: an ASS file next to the project
 * ({@code <project>.kdenlive.ass}) rendered by an internal {@code avfilter.subtitles} filter on the sequence.
 *
 * @param header the ASS text before {@code [Events]} (script info and styles), kept verbatim; null = Kdenlive's
 *               default header with {@link #style}
 * @param style the Default style used when the header is generated
 * @param filterProps extra properties of the subtitle filter written by Kdenlive (av.force_style, disable, ...)
 */
public record Subtitles(String header, Style style, List<Line> lines, Map<String, String> filterProps) {

    public static final Subtitles EMPTY = new Subtitles(null, Style.DEFAULT, List.of(), Map.of());

    public Subtitles {
        lines = lines.stream().sorted(Comparator.comparingLong(Line::start).thenComparingInt(Line::layer)).toList();
        filterProps = Collections.unmodifiableMap(new LinkedHashMap<>(filterProps));
        if (style == null) {
            style = Style.DEFAULT;
        }
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public Subtitles withLines(List<Line> newLines) {
        return new Subtitles(header, style, newLines, filterProps);
    }

    public Subtitles withStyle(Style newStyle) {
        return new Subtitles(null, newStyle, lines, filterProps); // regenerate the header with the new style
    }

    /**
     * One dialogue line. Times are frames, end exclusive.
     *
     * @param extra the ASS fields between Style and Text (Name, MarginL, MarginR, MarginV, Effect), kept verbatim
     */
    public record Line(long start, long end, String text, int layer, String styleName, String extra) {

        public Line {
            if (end <= start) {
                throw new IllegalArgumentException("subtitle end must be after start");
            }
            if (styleName == null || styleName.isBlank()) {
                styleName = "Default";
            }
            if (extra == null) {
                extra = ",0,0,0,";
            }
        }

        public Line(long start, long end, String text) {
            this(start, end, text, 0, "Default", ",0,0,0,");
        }
    }

    /**
     * The Default style. Sizes are pixels of the project frame; colors #RRGGBB[AA].
     *
     * @param position bottom | middle | top (ASS alignment 2, 5, 8)
     */
    public record Style(String font, double size, String color, String outlineColor, double outline, boolean bold,
                        String position, int marginV, String backColor, boolean box) {

        public static final Style DEFAULT = new Style("Arial", 0, "#ffffff", "#000000", 3, false, "bottom", 0,
                "#00000080", false);
    }
}
