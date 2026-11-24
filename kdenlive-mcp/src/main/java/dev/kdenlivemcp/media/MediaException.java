package dev.kdenlivemcp.media;

/** A media file cannot be used; the message is meant for the AI client. */
public class MediaException extends RuntimeException {
    public MediaException(String message) {
        super(message);
    }
}
