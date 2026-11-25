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
 * @param boxFit draw the box as rounded rectangles that hug each line of text (TikTok / Reels caption look)
 *               instead of a band across the frame
 * @param boxRadius corner radius of a fitted box, px at 1080p
 * @param align left, center or right
 * @param marginX left/right margin as a fraction of the frame width (for left / right aligned text)
 * @param y top of the text as a fraction of the frame height, overriding {@code position}; null to use position
 */
public record TitleStyle(String id, String description, String font, int weight, int size, String color,
                         double outline, String outlineColor, String position, double fadeIn, double fadeOut,
                         String box, boolean boxFit, double boxRadius, String align, double marginX, Double y) {

    public TitleStyle(String id, String description, String font, int weight, int size, String color,
                      double outline, String outlineColor, String position, double fadeIn, double fadeOut,
                      String box, boolean boxFit, double boxRadius) {
        this(id, description, font, weight, size, color, outline, outlineColor, position, fadeIn, fadeOut, box,
                boxFit, boxRadius, "center", 0, null);
    }

    public TitleStyle(String id, String description, String font, int weight, int size, String color,
                      double outline, String outlineColor, String position, double fadeIn, double fadeOut,
                      String box) {
        this(id, description, font, weight, size, color, outline, outlineColor, position, fadeIn, fadeOut, box,
                false, 0);
    }
}
