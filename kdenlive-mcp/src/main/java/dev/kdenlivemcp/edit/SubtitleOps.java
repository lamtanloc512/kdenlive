package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.Subtitles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The subtitle track. Lines are numbered 1..n in time order; numbers shift when lines are added or removed, so
 * list them again after an edit.
 */
public final class SubtitleOps {

    private SubtitleOps() {
    }

    public static EditResult add(Project p, long start, long end, String text) {
        Draft d = new Draft(p);
        Subtitles.Line line = line(d, start, end, text);
        List<Subtitles.Line> lines = new ArrayList<>(d.subtitles.lines());
        lines.add(line);
        d.subtitles = d.subtitles.withLines(lines);
        int n = d.subtitles.lines().indexOf(line) + 1;
        return new EditResult(d.build(), "subtitle " + n + " " + d.sec(start) + "–" + d.sec(end) + " \"" + text + "\""
                + overlapNote(d, line), d.notes, List.of("s" + n));
    }

    public static EditResult set(Project p, int number, Long start, Long end, String text) {
        Draft d = new Draft(p);
        Subtitles.Line old = at(d, number);
        Subtitles.Line line = line(d, start == null ? old.start() : start, end == null ? old.end() : end,
                text == null ? old.text() : text);
        line = new Subtitles.Line(line.start(), line.end(), line.text(), old.layer(), old.styleName(), old.extra());
        List<Subtitles.Line> lines = new ArrayList<>(d.subtitles.lines());
        lines.set(number - 1, line);
        d.subtitles = d.subtitles.withLines(lines);
        int n = d.subtitles.lines().indexOf(line) + 1;
        return new EditResult(d.build(), "subtitle " + n + " now " + d.sec(line.start()) + "–" + d.sec(line.end())
                + " \"" + line.text() + "\"" + overlapNote(d, line), d.notes, List.of("s" + n));
    }

    /** @param number line number, or null to remove all lines */
    public static EditResult remove(Project p, Integer number) {
        Draft d = new Draft(p);
        if (number == null) {
            int n = d.subtitles.lines().size();
            d.subtitles = d.subtitles.withLines(List.of());
            return new EditResult(d.build(), "removed all " + n + " subtitles", d.notes, List.of());
        }
        Subtitles.Line old = at(d, number);
        List<Subtitles.Line> lines = new ArrayList<>(d.subtitles.lines());
        lines.remove(number - 1);
        d.subtitles = d.subtitles.withLines(lines);
        return new EditResult(d.build(), "removed subtitle " + number + " \"" + old.text() + "\"", d.notes, List.of());
    }

    /** Adds lines from a parsed file, shifted by {@code offset} frames; replace=true drops the current lines. */
    public static EditResult importLines(Project p, Subtitles parsed, long offset, boolean replace) {
        Draft d = new Draft(p);
        List<Subtitles.Line> lines = new ArrayList<>(replace ? List.of() : d.subtitles.lines());
        for (Subtitles.Line l : parsed.lines()) {
            if (l.end() + offset <= 0) {
                continue;
            }
            lines.add(new Subtitles.Line(Math.max(0, l.start() + offset), l.end() + offset, l.text(), l.layer(),
                    l.styleName(), l.extra()));
        }
        Subtitles base = replace && parsed.header() != null
                ? new Subtitles(parsed.header(), parsed.style(), List.of(), d.subtitles.filterProps()) : d.subtitles;
        d.subtitles = base.withLines(lines);
        return new EditResult(d.build(), "imported " + parsed.lines().size() + " subtitles"
                + (offset == 0 ? "" : " shifted by " + d.sec(offset) + "s") + "; the track has "
                + d.subtitles.lines().size() + " lines", d.notes, List.of());
    }

    public static EditResult style(Project p, Subtitles.Style style) {
        Draft d = new Draft(p);
        if (d.subtitles.header() != null) {
            d.notes.add("the subtitle styles from Kdenlive were replaced by this Default style");
        }
        d.subtitles = d.subtitles.withStyle(style);
        return new EditResult(d.build(), String.format(Locale.ROOT,
                "subtitle style: %s %s px, color %s, outline %s %s px%s, %s%s", style.font(),
                style.size() > 0 ? dev.kdenlivemcp.assets.ParamCodec.num(style.size()) : "auto", style.color(),
                style.outlineColor(), dev.kdenlivemcp.assets.ParamCodec.num(style.outline()), style.bold() ? ", bold" : "",
                style.position(), style.box() ? ", on a box " + style.backColor() : ""), d.notes, List.of());
    }

    // ------------------------------------------------------------------ helpers

    private static Subtitles.Line line(Draft d, long start, long end, String text) {
        if (text == null || text.isBlank()) {
            throw new EditException("subtitle text is empty");
        }
        if (start < 0 || end <= start) {
            throw new EditException("subtitle needs 0 <= start < end (got " + d.sec(start) + "–" + d.sec(end) + ")");
        }
        return new Subtitles.Line(start, end, text.replace("\\n", "\n").strip());
    }

    private static Subtitles.Line at(Draft d, int number) {
        List<Subtitles.Line> lines = d.subtitles.lines();
        if (number < 1 || number > lines.size()) {
            throw new EditException(lines.isEmpty() ? "there are no subtitles yet"
                    : "subtitle number must be 1.." + lines.size());
        }
        return lines.get(number - 1);
    }

    private static String overlapNote(Draft d, Subtitles.Line line) {
        return d.subtitles.lines().stream().anyMatch(o -> o != line && o.start() < line.end() && line.start() < o.end())
                ? " (overlaps another line: both show at once)" : "";
    }
}
