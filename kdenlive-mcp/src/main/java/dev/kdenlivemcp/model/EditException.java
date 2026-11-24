package dev.kdenlivemcp.model;

/**
 * An edit was rejected. The message is shown to the AI client verbatim, so it must say what is wrong
 * and what would work instead (e.g. "clip is 12.400s long, out=15.000 is invalid").
 */
public class EditException extends RuntimeException {
    public EditException(String message) {
        super(message);
    }
}
