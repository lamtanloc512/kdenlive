package dev.kdenlivemcp.title;

/**
 * One entry of title-presets.json.
 *
 * @param size font pixel size for a 1080-pixel-high frame; scaled to the project height
 * @param color / outlineColor "r,g,b,a" (0-255)
 * @param position one of top, center, lower_third, bottom
 * @param fadeIn / fadeOut seconds of alpha fade at the start / end of the title
 */
public record TitleStyle(String id, String description, String font, int weight, int size, String color,
                         double outline, String outlineColor, String position, double fadeIn, double fadeOut) {
}
