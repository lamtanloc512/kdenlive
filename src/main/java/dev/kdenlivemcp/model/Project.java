package dev.kdenlivemcp.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The whole editable state of one project. Immutable: every edit produces a new Project, which makes
 * undo a matter of keeping old instances.
 *
 * @param file where the project is saved (.kdenlive)
 * @param tracks in display order, top to bottom (e.g. V2, V1, A1, A2)
 * @param nextId counter for new short ids (m1, c2, t3, x4 share one sequence)
 * @param meta document-level data (uuid, ids, unmodelled settings)
 */
public record Project(Path file, Profile profile, List<MediaClip> bin, List<Track> tracks,
                      List<Transition> transitions, long nextId, DocumentMeta meta) {

    public Project {
        bin = List.copyOf(bin);
        tracks = List.copyOf(tracks);
        transitions = List.copyOf(transitions);
    }

    public Fps fps() {
        return profile.fps();
    }

    public Optional<Track> track(String id) {
        return tracks.stream().filter(t -> t.id().equalsIgnoreCase(id)).findFirst();
    }

    public Optional<MediaClip> media(String id) {
        return bin.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    /** Timeline duration in frames (end of the last item on any track). */
    public long duration() {
        return tracks.stream().mapToLong(Track::end).max().orElse(0);
    }

    public Project withFile(Path newFile) {
        return new Project(newFile, profile, bin, tracks, transitions, nextId, meta);
    }
}
