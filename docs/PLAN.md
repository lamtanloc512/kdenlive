# kdenlive-mcp: Phase 0 report and plan

> **Status (2026-11-24):** Phases 0–1e are done. The format was learned from projects written by Kdenlive 26.08.3,
> and generated projects round-trip through Kdenlive unchanged. All tools are implemented, plus `server_check`,
> `render_cancel` and `media_contact_sheet`. The JVM and native builds work. `scripts/install-tools.sh` installs
> melt/ffmpeg/ffprobe from the official Kdenlive release. Not yet verified on macOS.
> See README.md for usage and the known limitations; the sections below are the original plan, kept for reference.

## 1. Versions and API (checked on Maven Central, 2026-11-24)

| Item | Choice | Note |
|---|---|---|
| Quarkus | **3.40.1.2** (stable) | 4.0.0 is only at CR1. `quarkus-mcp-server` 2.1.0 is built against 3.40.1.2. |
| MCP | `io.quarkiverse.mcp:quarkus-mcp-server-stdio` **2.1.0** | Latest release (2026-11-23). |
| Java | 21 | GraalVM CE / Mandrel 21 for native. |
| Tests | JUnit 5, `quarkus-junit5`, AssertJ | `quarkus-mcp-server-test` 2.1.0 exists, but its client targets HTTP. For stdio we spawn the runner and talk JSON-RPC ourselves (see §6). |

API facts, read from the 2.1.0 sources:
* `@Tool(name, description, annotations=@Tool.Annotations(readOnlyHint=…, destructiveHint=…))` on a CDI bean method.
  `@ToolArg(description, required, defaultValue)`. `Optional<T>` or a `defaultValue` makes an argument optional.
* Return `String`, a `Content`, a `List<Content>`, `ToolResponse`, or any POJO (encoded as JSON).
  `structuredContent=true` also returns structured JSON.
* **Images:** `new ImageContent(base64Png, "image/png")`, or `ToolResponse.success(new TextContent(caption), new ImageContent(...))`.
* **Errors the model can act on:** throw `ToolCallException("clip is 12.4s long, out=15 is invalid")`. It becomes an
  `isError: true` result, not a protocol error.
* **stdout hygiene:** `quarkus.mcp.server.stdio.null-system-out=true` is on by default (stray `System.out` goes nowhere).
  We also set `quarkus.log.console.stderr=true` and a file handler writing to `~/.kdenlive-mcp/logs/kdenlive-mcp.log`, and turn off the banner.

## 2. Tools on your machine

**I couldn't check your Mac.** This session runs in a Linux cloud container, and no computer of yours
is shared with it. Please run the one-liner at the end of `fixtures/README.md` and paste the output.

What I expect on macOS, which the resolver will try in this order:
1. `MELT_PATH` / `FFPROBE_PATH` / `FFMPEG_PATH` env vars, or `kdenlive.melt-path` etc. in `application.properties`
2. `/Applications/kdenlive.app/Contents/MacOS/{melt,ffprobe,ffmpeg}`. This is preferred for melt: its melt has
   the same MLT modules Kdenlive uses, including the `qt` module that renders `kdenlivetitle`.
3. `/opt/homebrew/bin/…`, `/usr/local/bin/…`, then `PATH`

⚠️ Homebrew's `mlt` may be built without the qt module, which means **no titles in renders**. At startup we run
`melt -query producers` and warn (and make `title_add` render a clear error) if `kdenlivetitle` is missing.

The bundled melt in `kdenlive.app` may need `MLT_REPOSITORY`, `MLT_DATA`, `MLT_PROFILES_PATH` and `FREI0R_PATH`
pointed at the bundle (`Contents/Frameworks/…` / `Contents/Resources/…`). The resolver sets them when it picks the
bundled binary. I'll confirm the exact layout from your `ls` output.

In this container (used for CI-style tests): melt 7.22.0 (Ubuntu, has `kdenlivetitle`, `luma`, `mix`, `qtblend`,
`volume`), ffmpeg/ffprobe 6.1.1, JDK 21.0.13, Maven, Docker. A GraalVM CE 21 download is reachable.

## 3. Format

See [`kdenlive-format.md`](kdenlive-format.md). Short version: we write a **document version 1.1** file with a
single sequence. That means bin producers, a track tractor with 2 playlists per track, a sequence tractor
(id = uuid), the `main_bin` playlist with docproperties, and the project tractor last. melt renders that file directly,
so **what we save is what we render**.
Eight details are marked ❓ until your fixtures arrive.

## 4. Package layout

```
kdenlive-mcp/
  pom.xml                          Quarkus 3.40.1.2, mcp-server-stdio 2.1.0, native profile
  src/main/java/dev/kdenlivemcp/
    model/                         pure Java 21 records, immutable, no XML/IO
      Project, Profile, Fps (rational), Time (frames <-> seconds), MediaClip, Track, TrackKind,
      TimelineItem (sealed: ClipItem | TitleItem), Transition (Mix | FadeToBlack), AudioSettings,
      TitleStyle, Ids
    edit/                          business logic: every operation is Project -> Project
      TimelineOps (insert/move/trim/split/remove), TransitionOps, TitleOps, AudioOps,
      Validation (all the "clip is 12.4s long" messages live here), EditException
    session/
      ProjectSession               one open project, undo stack (snapshots), autosave after each mutation
    backend/
      EditorBackend                interface: load, save, render, grabFrame, capabilities()
      RenderSpec, RenderJob, RenderStatus, FrameRequest
      kdenlive/
        KdenliveXmlBackend         implements EditorBackend
        KdenliveReader             DOM -> model (+ passthrough of unknown nodes)
        KdenliveWriter             model -> DOM, deterministic output
        TitleXml                   kdenlivetitle xmldata generator
        MltTime                    timecode parse/format (LC_NUMERIC=C)
        MeltRunner                 ProcessBuilder wrapper: async, timeout, kill, progress parsing
    media/
      FfprobeService               duration, fps, resolution, audio/video streams, rotation
      MediaInfo
      ImageTools                   ffmpeg-based scale + contact-sheet tiling + drawtext timestamps
    tools/                         binary discovery + module check (MELT_PATH etc.)
      ToolPaths, ToolCheck
    mcp/                           thin @Tool classes, which map args to ops and format results
      ProjectTools, MediaTools, TimelineTools, TitleTools, TransitionTools, AudioTools,
      PreviewTools, RenderTools, Formatters
  src/main/resources/
    application.properties
    title-presets.json             your editable list of title styles
    render-presets.json            draft_540p / final_1080p / vertical_1080p -> melt consumer args
  src/test/java/…                  see §6
  fixtures/                        your 3 reference projects + media
  docs/  kdenlive-format.md  tools.md  PLAN.md
```

**XML:** the JDK's DOM (`javax.xml.parsers`) plus our own mapper. No JAXB or Jackson-XML, so there's no reflection
to register for native. The DOM is supported in GraalVM native image (we add the `xml` service providers that
Quarkus already handles).

**Images:** no AWT. AWT on macOS native images is unreliable. melt does frame grabs and scaling
(`-consumer avformat:… width=960 height=…`), and ffmpeg does tiling and timestamps (`tile` / `xstack` + `drawtext`).

## 5. Domain model

```java
record Project(Path file, Profile profile, List<MediaClip> bin, List<Track> tracks,
               List<Transition> transitions, Passthrough extra)       // extra = unknown XML we keep
record Profile(String mltName, int width, int height, Fps fps, ...)   // presets map to MLT names:
    // 1080p30 -> atsc_1080p_30, 1080p60 -> atsc_1080p_60, vertical_1080x1920_30 -> vertical_hd_30, 4k30 -> uhd_2160p_30
record Fps(int num, int den)                                           // 30000/1001 works too
record MediaClip(String id /* "m1" */, int binId, Path path, String name, MediaInfo info)
record Track(String id /* "V1","A1" */, TrackKind kind, List<TimelineItem> items, boolean muted, boolean hidden)
sealed interface TimelineItem permits ClipItem, TitleItem { String id(); long start(); long length(); }
record ClipItem(String id /* "c7" */, String mediaId, long start, long in, long out,
                String linkId /* AV partner on the audio track, or null */, AudioSettings audio, List<Effect> passthroughEffects)
record TitleItem(String id /* "t3" */, String text, String styleId, long start, long length)
sealed interface Transition permits Mix, FadeToBlack
record Mix(String id, MixType type /* DISSOLVE, WIPE */, String itemA, String itemB, long duration)
record FadeToBlack(String id, String item, long duration)
```

* **Time:** the model stores **integer frames**. Tools accept and return **seconds**, snapped to the nearest frame,
  and the response states the snapped value ("at=1.333s snapped to 1.333s = frame 40"). This avoids float drift
  across split/trim chains.
* **Ids:** short and stable (`m1`, `c7`, `t3`, `x2`). They're stored in the file as `kdenlive:mcp_id` properties.
  Kdenlive keeps unknown properties, so ids survive a round-trip through the Kdenlive GUI. ❓ to be verified.
* **Passthrough:** anything we don't model (effects, compositions, guides, extra sequences, folders) is kept as
  opaque XML on its owner and written back unchanged. Projects you polish in Kdenlive therefore survive
  `project_open` → edit → save. Items with passthrough effects can still be moved and trimmed.
* **Undo:** the model is immutable, so a snapshot costs almost nothing. The session keeps `Deque<Project>`
  (cap 200), and `undo` pops it and autosaves.
* **Autosave:** every mutation writes `<project>.kdenlive` atomically (temp file + `ATOMIC_MOVE`).

## 6. MCP tools (v1)

Every tool takes and returns seconds. Every mutating tool replies with a one-line diff plus the affected items,
e.g. `inserted c4 on V1 0.000–6.000 (bars.mp4 in=0 out=6); audio part c5 on A1`.

| Tool | Args | Notes |
|---|---|---|
| `project_new` | `path, preset` | Creates V2, V1, A1, A2 (Kdenlive defaults ❓). Fails if the file exists unless `overwrite=true`. |
| `project_open` | `path` | Errors on unsupported versions name the version found. |
| `project_save` | `path?` | `path` = save as. |
| `project_summary` | – | Profile, duration, track list with item counts, bin, warnings (missing media, missing melt modules). |
| `media_import` | `paths[]` | Probes each file. Returns `m1: bars.mp4 6.000s 1920x1080@30 audio:yes`. |
| `media_list` | – | |
| `timeline_get` | – | Compact JSON `{fps, duration, tracks:[{id,kind,items:[{id,name,start,end,in,out,link}]}], transitions:[…]}` |
| `clip_insert` | `clipId, track, at, in?, out?, withAudio=true` | Puts an AV clip on a video track **and** its audio on the paired audio track (V1↔A1), grouped like Kdenlive does. Refuses overlaps (no ripple in v1) with a message naming the blocking item and the next free position. |
| `clip_move` | `itemId, track?, at` | Moves the linked partner too. |
| `clip_trim` | `itemId, in?, out?` | Source-time trim. Keeps the item's start. Validates against media duration. |
| `clip_remove` | `itemId` | Removes the partner and any transitions that reference it. |
| `clip_split` | `itemId, at` | `at` is timeline time. Returns both new ids. |
| `title_add` | `text, at, duration, track, style` | `style` must be one of the ids in `title-presets.json` (the error lists them). |
| `transition_add` | `type, itemA, itemB?, duration` | `dissolve`/`wipe` = same-track mix between two adjacent items (they must touch; the error says how much handle is missing). The audio parts get a matching crossfade. `fade_to_black` takes `itemA` only (end of item, video and audio). |
| `audio_set` | `itemId, volumeDb, fadeIn?, fadeOut?` | Works on audio items or the audio part of an AV item. |
| `undo` | – | Returns what was undone. |
| `preview_frame` | `t` | PNG `ImageContent`, at most 960 px wide, plus a caption with the timecode. |
| `contact_sheet` | `start, end, count=8` | One PNG grid (4 columns), each tile stamped with its time. |
| `render` | `outputPath, preset` | Returns `jobId` straight away. One job at a time by default; a second call is rejected with the running job's id. |
| `render_status` | `jobId` | `running 43% (eta 12s)` / `done → path, 12.03s, 1920x1080, audio:aac` / `failed: <last 20 log lines>`. |
| `render_cancel` | `jobId` | **Addition:** "killable" needs a tool. |

Rendering details:
* `MeltRunner` runs `melt <snapshot.kdenlive> -progress -consumer avformat:<out> <preset args>` through `ProcessBuilder`
  on a virtual thread. We render a **snapshot copy** of the project (`WORK_DIR/render-<job>.kdenlive`), so later
  edits can't corrupt a running render.
* Progress comes from melt's `Current Frame: N, percentage: P` on stderr. The timeout is `max(10 min, 5 × duration)`
  and can be configured. Cancel calls `destroy()`, then `destroyForcibly()` after 5 s, and deletes the partial output.
* Each job writes its own log to `~/.kdenlive-mcp/logs/render-<job>.log`. After a render we probe the output and
  record duration and streams in the status.
* `preview_frame` and `contact_sheet` are synchronous but bounded: 30 s timeout per melt call, and contact-sheet frames are grabbed in parallel.

## 7. Test strategy

**Unit (no external binaries):**
* `TimelineOps`: insert (overlap rejection, linked AV), move (track change, partner), trim (bounds), split
  (frame exactness, linked split), remove (cascade to transitions), and undo restoring the exact previous `Project`.
* `Validation` messages: golden strings, since the model reads them.
* `MltTime`: parse/format at 25, 30, 29.97 and 60 fps, plus the inclusive-out off-by-one cases.
* XML round-trip: for each `fixtures/*.kdenlive`, load → save → normalize both → assert equal (rules in format doc §7).
  We also round-trip the 8 projects in Kdenlive's `tests/dataset` (GPL-3, copied under `fixtures/kde-dataset/`
  with their license header) for wider coverage, including mixes, nesting and keyframes.
* Generator conformance: build fixture 03 through the model ops, write it, and compare it semantically with
  your `03-full.kdenlive`. This is the main "Kdenlive will accept this" test that doesn't need the GUI.

**Integration (`@Tag("media")`, skipped when melt or ffmpeg is missing):**
* Generate 3 clips with ffmpeg (smptebars + tone, testsrc2, sine), then run the full flow through the CDI tool beans:
  new → import → insert ×2 → title → dissolve → music → contact_sheet (assert a PNG with the expected size) →
  render `draft_540p` → poll status → assert the MP4 exists, its duration is within ±0.1 s of the timeline, and it has an audio stream.
* Test that render cancel and timeout kill the process.

**stdio smoke test:** start the packaged runner (JVM, and the native binary when present), send `initialize`,
`tools/list` and one `tools/call` over stdin, and assert that **every line on stdout parses as JSON-RPC**.

**Manual:** you open the generated project in Kdenlive 25.x and confirm it looks right (your review gate).

## 8. Phases and commits

1. **Phase 1a:** Maven skeleton, config, tool discovery, ffprobe, logging to stderr/file, stdio smoke test.
2. **Phase 1b:** model + edit ops + undo + unit tests.
3. **Phase 1c:** reader/writer from fixtures + round-trip tests + docs/kdenlive-format.md finalized.
4. **Phase 1d:** titles, mixes, audio, render jobs, preview/contact sheet, integration test.
   **→ STOP: first end-to-end render, for your review.**
5. **Phase 1e:** native image build, README, docs/tools.md, known limitations.

## 9. Known limitations (v1, expected)

* No ripple or insert-push edits. Overlapping placement is rejected rather than overwritten.
* One sequence: we edit the active one and pass the others through untouched.
* No keyframes and no effects beyond volume/fades/fade-to-black. Existing effects are preserved but not editable.
* Kdenlive doesn't notice when the file changes under it. Reopen the project in Kdenlive after Claude edits it.
* Proxies, image sequences and variable-frame-rate media are untested. VFR may drift (we warn on import).
* Rotation metadata is reported by `media_import`, but we don't auto-correct it.

## 10. Decisions I need from you

1. **Repo:** where should this live? (a) a new repo `kdenlive-mcp` that you create on GitHub, which I then attach
   (my recommendation, since a Java project doesn't belong inside the KDE C++ tree), or (b) a `kdenlive-mcp/`
   folder in your `lamtanloc512/kdenlive` fork.
2. **AV linking:** OK that `clip_insert` of an AV clip on V1 also places its audio on A1 by default, like Kdenlive does
   (`withAudio=false` to skip)?
3. **Dissolve as same-track mix** (what Kdenlive does when you press U), which needs adjacent clips with handles,
   rather than a composition across two tracks?
4. **Quarkus 3.40.1.2** (stable) rather than 4.0.0.CR1?
5. Your fixtures, plus the tool-path output (see `fixtures/README.md`).
