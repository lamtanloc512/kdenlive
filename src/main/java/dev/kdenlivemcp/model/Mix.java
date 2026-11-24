package dev.kdenlivemcp.model;

import java.util.List;

/**
 * A same-track mix between two adjacent items ({@code a.end == b.start}), as Kdenlive creates with
 * "Mix clips" (U).
 * <p>
 * In the model both items stay cut-to-cut. When written, A is extended past the cut by
 * {@code length - cutOffset} frames and B starts {@code cutOffset} frames earlier, so the mix covers
 * {@code [cut - cutOffset, cut + length - cutOffset)}. Kdenlive stores {@code cutOffset} as
 * {@code kdenlive:mixcut}.
 */
public record Mix(String id, MixType type, String itemA, String itemB, long length, long cutOffset)
        implements Transition {

    public Mix {
        if (length <= 0 || cutOffset < 0 || cutOffset > length) {
            throw new IllegalArgumentException("invalid mix length/cutOffset " + length + "/" + cutOffset);
        }
    }

    @Override
    public List<String> itemIds() {
        return List.of(itemA, itemB);
    }

    public Mix withItems(String a, String b) {
        return new Mix(id, type, a, b, length, cutOffset);
    }
}
