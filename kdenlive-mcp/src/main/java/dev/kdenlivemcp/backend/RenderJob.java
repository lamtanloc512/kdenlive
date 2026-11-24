package dev.kdenlivemcp.backend;

/** A running or finished render. */
public interface RenderJob {

    String id();

    RenderStatus status();

    /** Kills the render process; no-op if already finished. */
    void cancel();
}
