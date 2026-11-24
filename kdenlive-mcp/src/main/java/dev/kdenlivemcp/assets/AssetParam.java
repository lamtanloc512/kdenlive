package dev.kdenlivemcp.assets;

import java.util.List;
import java.util.Locale;

/**
 * One {@code <parameter>} of a Kdenlive effect/transition definition.
 * <p>
 * Units: MLT stores the raw value; Kdenlive shows {@code raw * factor}. {@code min}/{@code max} in the XML are in
 * the shown (UI) unit, {@code default} is raw. We follow the same convention: the API takes and reports UI values.
 *
 * @param name MLT property name ({@code av.radius}, {@code rect}, ...); several names separated by newlines for
 *             multiswitch
 * @param type Kdenlive parameter type as written in the XML ({@code animated}, {@code constant}, {@code list}, ...)
 * @param defaultValue raw default, may contain {@code %width} style placeholders
 * @param minText the raw min/max attributes (switches store their off/on values there)
 * @param values for lists: allowed raw values; {@code labels} are what Kdenlive shows for them
 */
public record AssetParam(String name, String type, String title, String comment, String defaultValue, Double min,
                         Double max, String minText, String maxText, double factor, Integer decimals, String suffix,
                         List<String> values, List<String> labels) {

    public AssetParam {
        values = values == null ? List.of() : List.copyOf(values);
        labels = labels == null ? List.of() : List.copyOf(labels);
        if (factor == 0) {
            factor = 1;
        }
    }

    public Kind kind() {
        return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "constant", "double", "float", "integer", "" -> Kind.NUMBER;
            case "animated", "keyframe", "simplekeyframe" -> Kind.NUMBER;
            case "bool", "switch" -> Kind.BOOL;
            case "list", "listdependency" -> Kind.LIST;
            case "color", "fixedcolor" -> Kind.COLOR;
            case "animatedrect", "rect", "geometry", "fakerect", "animatedfakerect" -> Kind.RECT;
            case "animatedpoint", "point", "fakepoint", "animatedfakepoint" -> Kind.POINT;
            case "fixed" -> Kind.FIXED;
            case "hidden" -> Kind.HIDDEN;
            case "position" -> Kind.POSITION;
            case "url", "urllist", "fontfamily", "keywords", "readonly" -> Kind.TEXT;
            default -> Kind.RAW; // curve, bezier_spline, roto-spline, multiswitch, av_curve, colorwheel, wipe, ...
        };
    }

    /** Can be keyframed (Kdenlive's {@code AssetParameterModel::isAnimated}). */
    public boolean animated() {
        return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "animated", "keyframe", "simplekeyframe", "animatedrect", "rect", "animatedfakerect",
                 "animatedpoint", "animatedfakepoint", "color", "colorwheel" -> true;
            default -> false;
        };
    }

    /** Shown to the client and settable through the API. */
    public boolean userSettable() {
        return name != null && !name.isBlank() && switch (kind()) {
            case FIXED, HIDDEN, POSITION -> false;
            default -> true;
        };
    }

    /** Several MLT properties behind one parameter (multiswitch). */
    public List<String> names() {
        return List.of(name.split("\n"));
    }

    public enum Kind { NUMBER, BOOL, LIST, COLOR, RECT, POINT, TEXT, RAW, FIXED, HIDDEN, POSITION }
}
