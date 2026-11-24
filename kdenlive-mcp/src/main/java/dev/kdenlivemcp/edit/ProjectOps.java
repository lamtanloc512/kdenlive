package dev.kdenlivemcp.edit;

import dev.kdenlivemcp.media.MediaInfo;
import dev.kdenlivemcp.model.DocumentMeta;
import dev.kdenlivemcp.model.Extras;
import dev.kdenlivemcp.model.MediaClip;
import dev.kdenlivemcp.model.Profile;
import dev.kdenlivemcp.model.Project;
import dev.kdenlivemcp.model.Track;
import dev.kdenlivemcp.model.TrackKind;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Creating projects and adding media to the bin. */
public final class ProjectOps {

    /** Kdenlive gives the "Sequences" folder id 2 and the sequence id 3; clips start at 4. */
    static final int FIRST_BIN_ID = 4;

    private ProjectOps() {
    }

    /** New empty project with Kdenlive's default tracks: V2, V1 (top to bottom), then A1, A2. */
    public static Project create(Path file, Profile profile) {
        List<Track> tracks = List.of(
                Track.empty("V2", TrackKind.VIDEO),
                Track.empty("V1", TrackKind.VIDEO),
                Track.empty("A1", TrackKind.AUDIO),
                Track.empty("A2", TrackKind.AUDIO));
        return new Project(file.toAbsolutePath().normalize(), profile, List.of(), tracks, List.of(), 1,
                DocumentMeta.fresh());
    }

    /** Adds probed files to the bin. A file already in the bin is not added twice. */
    public static EditResult importMedia(Project p, List<MediaInfo> infos) {
        Draft d = new Draft(p);
        List<String> ids = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        int binId = Math.max(FIRST_BIN_ID, d.bin.stream().mapToInt(MediaClip::binId).max().orElse(FIRST_BIN_ID - 1) + 1);
        for (MediaInfo info : infos) {
            Optional<MediaClip> existing = d.bin.stream().filter(m -> m.path().equals(info.path())).findFirst();
            if (existing.isPresent()) {
                ids.add(existing.get().id());
                lines.add(existing.get().id() + ": " + existing.get().name() + " (already in bin) " + info.describe());
                continue;
            }
            String id = d.newId("m");
            MediaClip clip = new MediaClip(id, binId++, info.path(), info.path().getFileName().toString(), info, Extras.NONE);
            d.bin.add(clip);
            ids.add(id);
            StringBuilder line = new StringBuilder(id + ": " + clip.name() + " " + info.describe());
            if (info.fps() != null && !info.stillImage() && !info.fps().equals(p.fps())) {
                line.append(" [").append(info.fps().label()).append(" fps media in a ").append(p.fps().label())
                        .append(" fps project: frames are resampled]");
            }
            info.warnings().forEach(w -> line.append(" [warning: ").append(w).append(']'));
            lines.add(line.toString());
        }
        return new EditResult(d.build(), String.join("\n", lines), d.notes, ids);
    }
}
