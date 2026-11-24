package dev.kdenlivemcp.mcp;

import dev.kdenlivemcp.backend.kdenlive.KdenliveXmlBackend;
import dev.kdenlivemcp.edit.AudioOps;
import dev.kdenlivemcp.edit.EditResult;
import dev.kdenlivemcp.edit.TimelineOps;
import dev.kdenlivemcp.edit.TitleOps;
import dev.kdenlivemcp.edit.TransitionOps;
import dev.kdenlivemcp.model.EditException;
import dev.kdenlivemcp.model.Fps;
import dev.kdenlivemcp.model.MixType;
import dev.kdenlivemcp.session.ProjectSession;
import dev.kdenlivemcp.title.TitlePresets;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

import java.util.Locale;

/** Timeline editing tools. Times are seconds, snapped to the nearest frame. */
public class TimelineTools {

    private final ProjectSession session;
    private final TitlePresets presets;
    private final KdenliveXmlBackend backend;

    public TimelineTools(ProjectSession session, TitlePresets presets, KdenliveXmlBackend backend) {
        this.session = session;
        this.presets = presets;
        this.backend = backend;
    }

    private Fps fps() {
        return session.current().fps();
    }

    @Tool(name = "timeline_get", description = """
            The timeline as compact JSON: tracks (top to bottom) with items {id, type, start, end, in, out, name, \
            link, volumeDb, fades} and transitions. Times in seconds. Example: timeline_get()""",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public String timelineGet() {
        return ToolSupport.call("timeline_get", () -> Formatters.timelineJson(session.current()));
    }

    @Tool(name = "clip_insert", description = """
            Place a bin clip on a track at a time. An audio+video clip on a video track also gets its audio part \
            on the matching audio track (V1->A1, V2->A2), linked so they move/trim/split together; withAudio=false \
            skips that. in/out pick a part of the source (seconds into the file). Items may not overlap (no ripple): \
            the error tells you the next free position.
            Example: clip_insert(clipId="m1", track="V1", at=0, in=2.5, out=8)""")
    public String clipInsert(
            @ToolArg(description = "Bin clip id from media_import, e.g. m1") String clipId,
            @ToolArg(description = "Track id: V1, V2 (video) or A1, A2 (audio)") String track,
            @ToolArg(description = "Timeline position in seconds") double at,
            @ToolArg(description = "Source in point in seconds (default 0)", required = false) Double in,
            @ToolArg(description = "Source out point in seconds (default: end of file)", required = false) Double out,
            @ToolArg(description = "Also place the audio of an A/V clip", defaultValue = "true") boolean withAudio) {
        return ToolSupport.call("clip_insert", () -> {
            Fps f = fps();
            long atF = ToolSupport.frames(f, "at", at);
            Long inF = ToolSupport.framesOrNull(f, "in", in);
            Long outF = ToolSupport.framesOrNull(f, "out", out);
            return session.apply("clip_insert " + clipId,
                    p -> TimelineOps.insert(p, clipId, track, atF, inF, outF, withAudio)).message();
        });
    }

    @Tool(name = "clip_move", description = """
            Move an item so it starts at 'at' (seconds), optionally to another track of the same kind. Its linked \
            audio/video part moves by the same amount. Dissolves that no longer fit are removed (reported).
            Example: clip_move(itemId="c4", at=12.5) or clip_move(itemId="c4", track="V2", at=3)""")
    public String clipMove(
            @ToolArg(description = "Timeline item id, e.g. c4") String itemId,
            @ToolArg(description = "Target track (default: stay on the same track)", required = false) String track,
            @ToolArg(description = "New start in seconds") double at) {
        return ToolSupport.call("clip_move", () -> {
            long atF = ToolSupport.frames(fps(), "at", at);
            return session.apply("clip_move " + itemId, p -> TimelineOps.move(p, itemId, track, atF)).message();
        });
    }

    @Tool(name = "clip_trim", description = """
            Change which part of the source an item plays: in/out are seconds INTO THE SOURCE FILE. The item keeps \
            its timeline start, so its end moves. For titles, out is the new duration. Linked parts follow.
            Example: clip_trim(itemId="c4", in=1.0, out=4.5)""")
    public String clipTrim(
            @ToolArg(description = "Timeline item id") String itemId,
            @ToolArg(description = "New source in point (seconds)", required = false) Double in,
            @ToolArg(description = "New source out point (seconds); for titles the duration", required = false) Double out) {
        return ToolSupport.call("clip_trim", () -> {
            Fps f = fps();
            Long inF = ToolSupport.framesOrNull(f, "in", in);
            Long outF = ToolSupport.framesOrNull(f, "out", out);
            return session.apply("clip_trim " + itemId, p -> TimelineOps.trim(p, itemId, inF, outF)).message();
        });
    }

    @Tool(name = "clip_remove", description = """
            Remove an item (and its linked audio/video part) from the timeline; transitions on it are removed too. \
            The clip stays in the bin. Example: clip_remove(itemId="c6")""")
    public String clipRemove(@ToolArg(description = "Timeline item id") String itemId) {
        return ToolSupport.call("clip_remove",
                () -> session.apply("clip_remove " + itemId, p -> TimelineOps.remove(p, itemId)).message());
    }

    @Tool(name = "clip_split", description = """
            Cut an item in two at timeline time 'at' (seconds, strictly inside the item). The left part keeps the \
            id, the right part gets a new id (returned). Linked parts are split too.
            Example: clip_split(itemId="c4", at=3.2)""")
    public String clipSplit(
            @ToolArg(description = "Timeline item id") String itemId,
            @ToolArg(description = "Timeline position in seconds") double at) {
        return ToolSupport.call("clip_split", () -> {
            long atF = ToolSupport.frames(fps(), "at", at);
            return session.apply("clip_split " + itemId, p -> TimelineOps.split(p, itemId, atF)).message();
        });
    }

    @Tool(name = "title_add", description = """
            Add a text title on a video track (use V2 to overlay video on V1). style is one of the fixed presets \
            from title-presets.json (font, size, color, position, fades): lower_third, big_center, top_caption, \
            subtitle (call project_summary or try a wrong name to see the current list). Use \\n for line breaks.
            Example: title_add(text="Halo — think harder", at=1, duration=3, track="V2", style="lower_third")""")
    public String titleAdd(
            @ToolArg(description = "Text to show") String text,
            @ToolArg(description = "Start time in seconds") double at,
            @ToolArg(description = "Duration in seconds") double duration,
            @ToolArg(description = "Video track", defaultValue = "V2") String track,
            @ToolArg(description = "Preset id", defaultValue = "lower_third") String style) {
        return ToolSupport.call("title_add", () -> {
            Fps f = fps();
            long atF = ToolSupport.frames(f, "at", at);
            long lenF = ToolSupport.positive(f, "duration", duration);
            String t = text == null ? null : text.replace("\\n", "\n");
            EditResult r = session.apply("title_add", p -> TitleOps.add(p, t, atF, lenF, track, style, presets));
            String msg = r.message();
            var problem = backend.titleRenderingProblem();
            if (problem.isPresent()) {
                msg += "\nwarning: " + problem.get();
            }
            return msg;
        });
    }

    @Tool(name = "transition_add", description = """
            Add a transition. dissolve / wipe: a same-track mix between two items that touch (itemA ends where \
            itemB starts), like Kdenlive's 'Mix clips'. It needs spare source media around the cut (the error says \
            how much is missing and how to trim). The linked audio parts get a crossfade automatically. On audio \
            tracks dissolve = crossfade. fade_to_black: fades the end of itemA to black (and its audio out).
            Examples: transition_add(type="dissolve", itemA="c4", itemB="c6", duration=1.0); \
            transition_add(type="fade_to_black", itemA="c8", duration=1.5)""")
    public String transitionAdd(
            @ToolArg(description = "dissolve | wipe | fade_to_black") String type,
            @ToolArg(description = "First item (or the faded item)") String itemA,
            @ToolArg(description = "Second item, for dissolve/wipe", required = false) String itemB,
            @ToolArg(description = "Duration in seconds", defaultValue = "1.0") double duration) {
        return ToolSupport.call("transition_add", () -> {
            long len = ToolSupport.positive(fps(), "duration", duration);
            String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
            return switch (t) {
                case "dissolve", "crossfade" -> session.apply("dissolve",
                        p -> TransitionOps.addMix(p, MixType.DISSOLVE, itemA, itemB, len)).message();
                case "wipe" -> session.apply("wipe", p -> TransitionOps.addMix(p, MixType.WIPE, itemA, itemB, len))
                        .message();
                case "fade_to_black" -> session.apply("fade_to_black",
                        p -> TransitionOps.addFadeToBlack(p, itemA, len)).message();
                default -> throw new EditException("unknown transition type '" + type
                        + "'; use dissolve, wipe or fade_to_black");
            };
        });
    }

    @Tool(name = "audio_set", description = """
            Set volume (dB) and/or fade in/out (seconds) on an item with audio. On a video item of an A/V clip it \
            applies to the linked audio part. Omitted values stay as they are. -6 dB is about half as loud.
            Example: audio_set(itemId="c10", volumeDb=-12, fadeIn=0.5, fadeOut=2)""")
    public String audioSet(
            @ToolArg(description = "Timeline item id") String itemId,
            @ToolArg(description = "Volume in dB (-60 to +24, 0 = original)", required = false) Double volumeDb,
            @ToolArg(description = "Fade-in length in seconds", required = false) Double fadeIn,
            @ToolArg(description = "Fade-out length in seconds", required = false) Double fadeOut) {
        return ToolSupport.call("audio_set", () -> {
            Fps f = fps();
            Long fi = ToolSupport.framesOrNull(f, "fadeIn", fadeIn);
            Long fo = ToolSupport.framesOrNull(f, "fadeOut", fadeOut);
            return session.apply("audio_set " + itemId, p -> AudioOps.set(p, itemId, volumeDb, fi, fo)).message();
        });
    }

    @Tool(name = "undo", description = "Undo the last edit (the project file is saved again). Example: undo()")
    public String undo() {
        return ToolSupport.call("undo", () -> "undid " + session.undo() + "; " + session.undoDepth() + " more step(s) available");
    }
}
