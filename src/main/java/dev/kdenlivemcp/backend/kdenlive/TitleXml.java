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
        double scale = h / 1080.0;
        int size = (int) Math.round(style.size() * scale);
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
        Element item = Xml.el(root, "item", "type", "QGraphicsTextItem", "z-index", "0");
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
