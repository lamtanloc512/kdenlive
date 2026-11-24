package dev.kdenlivemcp.model;

/** Transitions supported in v1. */
public sealed interface Transition permits Mix, FadeToBlack {

    String id();

    /** Item ids this transition depends on. */
    java.util.List<String> itemIds();
}
