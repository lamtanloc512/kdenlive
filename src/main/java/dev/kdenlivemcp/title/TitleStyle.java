package dev.kdenlivemcp.title;

/**
 * One entry of title-presets.json.
 *
 * @param size font pixel size for a 1080-pixel-high frame; scaled to the project height
 * @param color / outlineColor "r,g,b,a" (0-255)
 * @param position one of top, center, lower_third, bottom
 * @param fadeIn / fadeOut seconds of alpha fade at the start / end of the title
 * @param box "r,g,b,a" of a band drawn behind the text across the frame (keeps text readable on light
 *            backgrounds), or null for none
 */
public record TitleStyle(String id, String description, String font, int weight, int size, String color,
                         double outline, String outlineColor, String position, double fadeIn, double fadeOut,
                         String box) {
}
