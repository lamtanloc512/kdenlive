package dev.kdenlivemcp.backend.kdenlive;

import dev.kdenlivemcp.model.Profile;
import dev.kdenlivemcp.title.TitleStyle;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.Locale;

/**
 * Builds a {@code .kdenlivetitle} document (same layout as Kdenlive's data/titles/simple.kdenlivetitle) for a
 * preset style: one text item spanning the frame width, centered with {@code alignment=4}.
 */
public final class TitleXml {

    private TitleXml() {
    }

    public static String build(String text, TitleStyle style, Profile profile, long lengthFrames) {
        int w = profile.width();
        int h = profile.height();
        // presets are sized for 1080p: scale by the short side, so vertical frames don't get 1.78x text
        double scale = Math.min(w, h) / 1080.0;
        int size = (int) Math.round(style.size() * scale);
        // shrink lines that would not fit in 92% of the width (bold sans: ~0.62 em per character)
        int longest = text.lines().mapToInt(l -> l.strip().codePointCount(0, l.strip().length())).max().orElse(1);
        double fit = w * 0.92 / Math.max(1, longest * 0.62);
        if (size > fit) {
            size = (int) Math.floor(fit);
        }
        double lineHeight = size * 1.25;
        long lines = Math.max(1, text.lines().count());
        double boxHeight = lines * lineHeight;
        double y = switch (style.position()) {
            case "top" -> h * 0.08;
            case "center" -> (h - boxHeight) / 2;
            case "bottom" -> h * 0.92 - boxHeight;
            default -> h * 0.72; // lower_third
        };
        Document doc = Xml.newDocument();
        Element root = doc.createElement("kdenlivetitle");
        doc.appendChild(root);
        root.setAttribute("width", Integer.toString(w));
        root.setAttribute("height", Integer.toString(h));
        root.setAttribute("out", Long.toString(lengthFrames));
        root.setAttribute("LC_NUMERIC", "C");
        if (style.box() != null && style.boxFit()) {
            // one rounded rectangle per line, hugging the text; neighbouring lines overlap so they merge into one
            // shape like TikTok / Reels captions
            double padX = size * 0.42;
            double padY = size * 0.14;
            double radius = style.boxRadius() * scale;
            java.util.List<String> textLines = text.lines().map(String::strip).toList();
            for (int i = 0; i < textLines.size(); i++) {
                double lw = textWidth(textLines.get(i), style.font(), style.weight(), size);
                double bw = lw + 2 * padX;
                double bx = (w - bw) / 2;
                double by = y + i * lineHeight - padY;
                Element band = Xml.el(root, "item", "type", "QGraphicsRectItem", "z-index", "0");
                Element bpos = Xml.el(band, "position", "x", num(bx), "y", num(by));
                Xml.el(bpos, "transform").setTextContent("1,0,0,0,1,0,0,0,1");
                Element c = Xml.el(band, "content", "rect", "0,0," + num(bw) + "," + num(lineHeight + 2 * padY),
                        "pencolor", "0,0,0,0", "penwidth", "0", "brushcolor", style.box());
                if (radius > 0) {
                    c.setAttribute("cornerRadius", num(radius));
                }
            }
        } else if (style.box() != null) {
            double pad = size * 0.35;
            Element band = Xml.el(root, "item", "type", "QGraphicsRectItem", "z-index", "0");
            Element bpos = Xml.el(band, "position", "x", "0", "y", num(y - pad));
            Xml.el(bpos, "transform").setTextContent("1,0,0,0,1,0,0,0,1");
            Xml.el(band, "content", "rect", "0,0," + w + "," + num(boxHeight + 2 * pad), "pencolor", "0,0,0,0",
                    "penwidth", "0", "brushcolor", style.box());
        }
        Element item = Xml.el(root, "item", "type", "QGraphicsTextItem", "z-index", "1");
        Element pos = Xml.el(item, "position", "x", "0", "y", num(y));
        Xml.el(pos, "transform").setTextContent("1,0,0,0,1,0,0,0,1");
        Element content = Xml.el(item, "content",
                "font", style.font(),
                "font-weight", Integer.toString(style.weight()),
                "font-pixel-size", Integer.toString(size),
                "font-italic", "0",
                "font-underline", "0",
                "letter-spacing", "0",
                "font-color", style.color(),
                "box-width", num(w),
                "box-height", num(boxHeight),
                "alignment", "4");
        if (style.outline() > 0) {
            content.setAttribute("font-outline", num(style.outline() * scale));
            content.setAttribute("font-outline-color", style.outlineColor());
        }
        content.setTextContent(text);
        Xml.el(root, "startviewport", "rect", "0,0," + w + "," + h);
        Xml.el(root, "endviewport", "rect", "0,0," + w + "," + h);
        Xml.el(root, "background", "color", "0,0,0,0");
        return Xml.toString(doc, false);
    }

    /** Width of one line in the title font, measured with the installed font when Java can load it. */
    static double textWidth(String line, String font, int weight, int size) {
        try {
            java.awt.Font f = new java.awt.Font(font, weight >= 650 ? java.awt.Font.BOLD : java.awt.Font.PLAIN, size);
            String named = weight >= 550 && weight < 650 ? font + " SemiBold" : weight >= 450 && weight < 550 ? font + " Medium" : null;
            if (named != null) {
                // AWT only knows plain/bold; semibold and medium instances are installed as their own family
                java.awt.Font n = new java.awt.Font(named, java.awt.Font.PLAIN, size);
                if (n.getFamily(Locale.ROOT).equalsIgnoreCase(named)) {
                    f = n;
                } else {
                    f = new java.awt.Font(font, java.awt.Font.BOLD, size);
                }
            }
            if (!f.getFamily(Locale.ROOT).equalsIgnoreCase(font) && !java.awt.Font.DIALOG.equals(font)) {
                // fall back to an estimate if the family is not installed (Java would substitute Dialog)
                if (java.awt.Font.DIALOG.equals(f.getFamily(Locale.ROOT))) {
                    return estimate(line, weight, size);
                }
            }
            java.awt.font.FontRenderContext frc = new java.awt.font.FontRenderContext(null, true, true);
            return f.getStringBounds(line, frc).getWidth();
        } catch (Throwable e) {
            return estimate(line, weight, size);
        }
    }

    private static double estimate(String line, int weight, int size) {
        return line.codePointCount(0, line.length()) * size * (weight >= 600 ? 0.6 : 0.52);
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    /** Plain text of the text items of a title document, for titles made in Kdenlive. */
    public static String textOf(String titleXml) {
        try {
            NodeList items = Xml.parseString(titleXml).getElementsByTagName("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.getLength(); i++) {
                String t = items.item(i).getTextContent();
                if (t != null && !t.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append(" / ");
                    }
                    sb.append(t.strip());
                }
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
