package dev.kdenlivemcp.tools;

/** Thrown when a required external program cannot be found or does not work. */
public class ToolMissingException extends RuntimeException {
    public ToolMissingException(String message) {
        super(message);
    }
}
