package dev.kdenlivemcp.backend;

/**
 * Snapshot of a render job.
 *
 * @param percent 0-100
 * @param message human/AI-readable details (output summary when done, reason when failed)
 * @param logTail last lines of the render log
 */
public record RenderStatus(State state, int percent, String message, String logTail) {

    public enum State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    public boolean finished() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }
}
