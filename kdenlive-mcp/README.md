# kdenlive-mcp

An MCP server that lets Claude (or any MCP client) edit videos as **Kdenlive projects**: import clips, cut and
arrange them, add titles, dissolves, fades and music, look at frames, and render an MP4. The result is a normal
`.kdenlive` file you can open and polish in Kdenlive.

* Headless: it reads and writes Kdenlive's project format (MLT XML, document version 1.1, Kdenlive 23.04+) and
  renders with `melt`, the engine Kdenlive itself uses. Kdenlive does not need to be running.
* Tested against **Kdenlive 26.08.3**: its own projects round-trip unchanged, and the projects we generate open
  in Kdenlive with no errors. When Kdenlive saves them again, the timeline is identical (see `docs/kdenlive-format.md`).
* Java 21 + Quarkus 3.40 + `quarkus-mcp-server-stdio`. Runs as a JVM app or as a single native executable.

## 1. Install melt, ffmpeg and ffprobe

The binaries ship with this repo, in `bundled-tools/`. They are taken unmodified from the official Kdenlive 26.08.3
release and trimmed to `melt`, `ffmpeg`, `ffprobe`, the MLT modules kdenlive-mcp uses, and the libraries those load:

| Archive | Platform | Size | Unpacked |
|---|---|---|---|
| `kdenlive-mcp-tools-linux-x64.tar.xz` | Linux x86_64 (from the AppImage) | 45 MB | 154 MB |
| `kdenlive-mcp-tools-macos-arm64.tar.xz` | macOS Apple Silicon (from the DMG, all MLT modules) | 45 MB | 192 MB |

```bash
scripts/install-tools.sh
```

This unpacks the archive for your machine into `~/.kdenlive-mcp/tools` and links `bin/{melt,ffmpeg,ffprobe}`; the
server looks there first. `scripts/install-tools.sh --download` instead fetches the full Kdenlive release from
download.kde.org and checks its SHA-256 (also for Intel Macs, which have no bundled archive). Licenses and sources of
the bundled binaries: `bundled-tools/NOTICE.txt`.

Other options. The server tries them in this order:
1. `MELT_PATH`, `FFPROBE_PATH`, `FFMPEG_PATH` (env) or `kdenlive.melt-path` etc. in `application.properties`
2. `~/.kdenlive-mcp/tools/bin` (from `install-tools.sh`; change the location with `KDENLIVE_MCP_TOOLS`)
3. macOS: `/Applications/kdenlive.app/Contents/MacOS/`, then Homebrew `/opt/homebrew/bin`
4. `/usr/local/bin`, `/usr/bin`, `PATH`

Call the `server_check` tool to see what was picked up and whether titles can render.
**Linux note:** distribution `melt` builds older than about MLT 7.30 (Ubuntu 24.04 ships 7.22) cannot render titles
without an X11 display. Use the script, or run the server under `xvfb-run`.

## 2. Build

```bash
./mvnw package                      # JVM: target/quarkus-app/quarkus-run.jar (needs Java 21+)
```

```bash
export GRAALVM_HOME=/path/to/graalvm-jdk-25
./mvnw package -Dnative             # native: target/kdenlive-mcp-0.1.0-SNAPSHOT-runner (~55 MB, starts in ~10 ms)
```

Native images are built for the OS you build on. Build the macOS binary on a Mac
(`brew install --cask graalvm-jdk` or a GraalVM CE 25 download), and the Linux binary on Linux.

## 3. Add it to Claude

**Claude Code**

```bash
claude mcp add kdenlive -- /path/to/kdenlive-mcp-0.1.0-SNAPSHOT-runner
```

With the JVM build:

```bash
claude mcp add kdenlive -- java -jar /path/to/kdenlive-mcp/target/quarkus-app/quarkus-run.jar
```

With explicit paths:

```bash
claude mcp add kdenlive -e MELT_PATH=/Applications/kdenlive.app/Contents/MacOS/melt -- /path/to/kdenlive-mcp-runner
```

**Claude Desktop**: edit `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or
`%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "kdenlive": {
      "command": "/Users/you/kdenlive-mcp/target/kdenlive-mcp-0.1.0-SNAPSHOT-runner",
      "env": {
        "WORK_DIR": "/Users/you/.kdenlive-mcp/work"
      }
    }
  }
}
```

For the JVM build use `"command": "java", "args": ["-jar", "/Users/you/kdenlive-mcp/target/quarkus-app/quarkus-run.jar"]`.

Then ask, for example: *"Make a 30-second promo from ~/Videos/demo.mp4 with three lower-thirds and a fade to black,
save it as ~/Videos/promo.kdenlive and render it."*

## 4. Tools

| Tool | Does |
|---|---|
| `server_check` | which melt/ffmpeg/ffprobe are used, missing MLT services, whether titles render |
| `project_new`, `project_open`, `project_save`, `project_summary` | one open project per server; autosaved after every edit |
| `media_import`, `media_list`, `media_contact_sheet` | add files to the bin, look inside a clip before cutting |
| `timeline_get` | compact JSON of tracks, items and transitions |
| `clip_insert`, `clip_move`, `clip_trim`, `clip_remove`, `clip_split` | editing; A/V clips keep their audio linked |
| `title_add` | text titles from the fixed presets in `title-presets.json` |
| `transition_add` | `dissolve`, `wipe` (same-track mixes, like Kdenlive's "Mix clips") and `fade_to_black` |
| `audio_set` | volume (dB) and fades |
| `undo` | step back (snapshot history) |
| `preview_frame`, `contact_sheet` | PNG of one timeline frame / a grid of frames with times |
| `render`, `render_status`, `render_cancel` | background render with progress, ETA, timeout and cancel |

Every tool, with an example call and answer, is in [`docs/tools.md`](docs/tools.md). All times are seconds, snapped to
the nearest frame. Errors say what is wrong and what would work, e.g.
`m1 (demo.mp4) is 185.600s long, out=190.000 is invalid (max 185.600)`.

## 5. Configuration

| Setting (env / property) | Default | Meaning |
|---|---|---|
| `MELT_PATH` / `kdenlive.melt-path` | auto | melt executable |
| `FFPROBE_PATH` / `kdenlive.ffprobe-path` | auto | ffprobe executable |
| `FFMPEG_PATH` / `kdenlive.ffmpeg-path` | auto | ffmpeg executable (contact sheets) |
| `KDENLIVE_MCP_TOOLS` | `~/.kdenlive-mcp/tools` | where `install-tools.sh` puts binaries |
| `WORK_DIR` / `kdenlive.work-dir` | `~/.kdenlive-mcp/work` | render snapshots, temporary frames |
| `TITLE_PRESETS` / `kdenlive.title-presets` | built-in | your own title presets file |
| `kdenlive.render-min-timeout` / `render-timeout-factor` | 600 s / 5× | render timeout = max(min, factor × duration) |

Logs: stderr and `~/.kdenlive-mcp/logs/kdenlive-mcp.log`; one `render-rN.log` per render. Nothing but MCP JSON-RPC
is written to stdout.

**Title presets** (`src/main/resources/title-presets.json`, or your own file via `TITLE_PRESETS`): each entry has
`id`, `font`, `weight`, `size` (px at 1080p), `color`, `outline`, `outlineColor`, `position`
(`top | center | lower_third | bottom`), `fadeIn`, `fadeOut` and an optional translucent `box` behind the text.

## 6. Tests

```bash
./mvnw verify
```

That runs 70+ tests: model and edit operations, round trips of the Kdenlive-written fixtures, the generator conformance
test, an end-to-end run through the tools (import → edit → contact sheet → render, checking the MP4) and a stdio smoke
test of the packaged server. Tests tagged `media` need ffmpeg and melt; skip them with `-Dtest.excludedGroups=media`.
On a headless Linux box, point them at a melt that can render titles:

```bash
MELT_PATH=~/.kdenlive-mcp/tools/bin/melt ./mvnw verify
```

## 7. Known limitations (v1)

* **No ripple editing.** Items never overlap; an insert or move into an occupied spot is refused, and the error gives the next free position.
* **One sequence per project.** Projects with several sequences are refused when opened.
* **Bin clips:** video/audio files and titles (still images are supported but only lightly tested). Color clips, slideshows, nested sequences and proxies are
  refused when opened. Compositions between tracks made in Kdenlive are kept and written back, but cannot be edited yet.
* **Effects:** every effect in Kdenlive's catalog can be added, set, keyframed and reordered on items, tracks and the
  master (see docs/tools.md). Effects made in Kdenlive are editable too. Complex parameter types (curves, splines,
  roto masks, gradients) take Kdenlive's raw string format. The Linux tools archive still has only the MLT modules of
  the first release, so fewer effects render there (macOS has all of them).
* **`clip_trim` keeps the item's start;** shortening the in point does not move the clip.
* **Dissolves need spare media around the cut,** like Kdenlive's own mixes. The error says how to trim to make room.
* **Kdenlive doesn't notice when the file changes.** Reopen the project there after Claude edits it, and don't edit the same project in both at once.
* **Variable-frame-rate media can drift by a frame** (`media_import` warns). Rotation metadata is shown but not corrected.
* **`media_contact_sheet` labels need an ffmpeg with `drawtext`.** The bundled ffmpeg has none, so the tiles are unlabelled and the caption lists the times in order. Timeline `contact_sheet` labels come from melt and always work.
* **macOS tools** are built on a Mac by `scripts/build-macos-tools.sh` from Kdenlive.app (unmodified binaries, valid
  signatures, every MLT module); verified on macOS arm64 with Kdenlive 26.08.3.
* **Single client.** One open project per server process; renders run one at a time.

## 8. Layout

```
src/main/java/dev/kdenlivemcp/
  model/      immutable timeline model (frames, fps-aware)       edit/     all editing rules and error messages
  session/    open project, undo, autosave                       backend/  EditorBackend + kdenlive/ (XML, melt)
  media/      ffprobe, source previews                           title/    title presets
  tools/      finding melt/ffmpeg/ffprobe                         mcp/      thin MCP tool classes
fixtures/     projects written by Kdenlive 26.08.3 (test oracles)
docs/         kdenlive-format.md, tools.md, PLAN.md
scripts/      install-tools.sh, build-macos-tools.sh, sync-kdenlive-assets.sh
src/main/resources/kdenlive-assets/   Kdenlive's effect/transition definitions (CC0), synced from ../data
```
