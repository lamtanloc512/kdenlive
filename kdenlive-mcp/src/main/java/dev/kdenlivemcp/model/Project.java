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
 * @param nextId counter for new short ids (m1, c2, t3, x4, e5 share one sequence)
 * @param meta document-level data (uuid, ids, unmodelled settings)
 * @param masterEffects effects on the whole sequence (Kdenlive's "master" effects), applied after the tracks mix
 * @param subtitles the subtitle track (empty when none)
 */
public record Project(Path file, Profile profile, List<MediaClip> bin, List<Track> tracks,
                      List<Transition> transitions, long nextId, DocumentMeta meta, List<Effect> masterEffects,
                      Subtitles subtitles) {

    /** Owner key of master effects in effect locations and the id map. */
    public static final String MASTER = "master";

    public Project {
        bin = List.copyOf(bin);
        tracks = List.copyOf(tracks);
        transitions = List.copyOf(transitions);
        masterEffects = masterEffects == null ? List.of() : List.copyOf(masterEffects);
        if (subtitles == null) {
            subtitles = Subtitles.EMPTY;
        }
    }

    public Project(Path file, Profile profile, List<MediaClip> bin, List<Track> tracks, List<Transition> transitions,
                   long nextId, DocumentMeta meta, List<Effect> masterEffects) {
        this(file, profile, bin, tracks, transitions, nextId, meta, masterEffects, Subtitles.EMPTY);
    }

    public Project(Path file, Profile profile, List<MediaClip> bin, List<Track> tracks, List<Transition> transitions,
                   long nextId, DocumentMeta meta) {
        this(file, profile, bin, tracks, transitions, nextId, meta, List.of(), Subtitles.EMPTY);
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
        long subs = subtitles.lines().stream().mapToLong(Subtitles.Line::end).max().orElse(0);
        return Math.max(subs, tracks.stream().mapToLong(Track::end).max().orElse(0));
    }

    public Project withFile(Path newFile) {
        return new Project(newFile, profile, bin, tracks, transitions, nextId, meta, masterEffects, subtitles);
    }
}
