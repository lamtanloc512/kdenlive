package dev.kdenlivemcp.model;

import java.util.List;

/** Video fade to black over the last {@code length} frames of an item. */
public record FadeToBlack(String id, String itemId, long length) implements Transition {

    @Override
    public List<String> itemIds() {
        return List.of(itemId);
    }

    public FadeToBlack withItem(String newItem) {
        return new FadeToBlack(id, newItem, length);
    }

    public FadeToBlack withLength(long newLength) {
        return new FadeToBlack(id, itemId, newLength);
    }
}
