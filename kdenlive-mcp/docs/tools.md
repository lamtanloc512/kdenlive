# kdenlive-mcp tools

All times are **seconds** (decimals allowed), snapped to the nearest frame of the project. Ids are short and stable:
`m1` bin clips, `c4` timeline clips, `t11` titles, `x7` transitions. They survive saving, reopening and even
editing the project in Kdenlive. Every edit is saved to the `.kdenlive` file immediately and can be undone.

When a call is wrong, the tool returns an error (`isError: true`) whose text says what to change, e.g.
`V1 is occupied by c6 (testsrc.mp4) from 6.000–12.000; a 6.000s item fits at 12.000 or later (no ripple editing in v1: move or trim the blocking item first)`.

The responses below come from real runs (`demo-edit/out/session.txt`).

---

## Setup and project

### `server_check()`
The binaries in use, missing MLT services, and whether titles can render.
```
melt: /home/me/.kdenlive-mcp/tools/bin/melt [kdenlive-mcp tools] melt 7.43.0
ffprobe: ... ffprobe version 8.1.1
ffmpeg: ... ffmpeg version 8.1.1
mlt services: all required services present
title rendering: ok
work dir: /home/me/.kdenlive-mcp/work
log dir: /home/me/.kdenlive-mcp/logs
```

### `project_new(path, preset="1080p30", overwrite=false)`
`preset`: `1080p30 | 1080p60 | vertical_1080x1920_30 | 4k30`. Creates tracks V2, V1 (video) and A1, A2 (audio).
```
project_new(path="/Users/me/Videos/promo.kdenlive", preset="1080p30")
→ created /Users/me/Videos/promo.kdenlive (HD 1080p 30 fps, tracks V2 V1 A1 A2). Next: media_import.
```

### `project_open(path)`
Opens a Kdenlive 23.04+ project with one sequence and returns the same text as `project_summary`.

### `project_save(path?)`
Edits are autosaved. With `path` it saves a copy that becomes the open project ("save as").

### `project_summary()`
```
project: /home/user/kdenlive-mcp/demo-edit/halo-promo.kdenlive
format: 1920x1080 @ 30 fps (atsc_1080p_30), duration 27.000s
tracks (top to bottom):
  V2 video: [t11 "Halo" 0.400–2.800] [t12 "Ask in plain words" 3.000–5.200] ...
  V1 video: [c2 demo.mp4 0.000–5.400] [c3 demo.mp4 5.400–10.800] ...
  A1 audio: [c16 demo.mp4 0.000–27.000]
  A2 audio: empty
bin:
  m1 demo.mp4 185.600s 1280x826@30 video:h264 audio:aac 48kHz stereo
transitions:
  x7 dissolve c2→c3 0.600s
  x17 fade_to_black on c6, 1.000s
undo steps available: 18
```

## Media

### `media_import(paths[])`
```
media_import(paths=["/Users/me/Videos/demo.mp4"])
→ m1: demo.mp4 185.600s 1280x826@30 video:h264 audio:aac 48kHz stereo
```
It warns about variable frame rate, rotation metadata, and media whose fps differs from the project. Importing the same
file again returns its existing id.

### `media_list()`
The bin with ids, probe info and paths.

### `media_contact_sheet(clipId, start?, end?, count=12)`
Frames **from the source file**, so you can choose in/out points before cutting. Times are seconds into the file.
```
media_contact_sheet(clipId="m1", start=30, end=45, count=8)
→ m1 (demo.mp4) source times: [30.000, 32.143, 34.286, 36.429, 38.571, 40.714, 42.857, 45.000]
  + PNG (4 per row, each frame stamped with its source time)
```

## Timeline

### `timeline_get()`
Compact JSON:
```json
{"fps":30.0,"duration":29.6,"tracks":[
  {"id":"V2","kind":"video","items":[{"id":"t13","start":4.8,"end":8.6,"type":"title","text":"Ask in plain words","style":"lower_third"}]},
  {"id":"V1","kind":"video","items":[{"id":"c2","start":0.0,"end":4.2,"type":"clip","clip":"m1","name":"demo.mp4","in":0.3,"out":4.5}]},
  {"id":"A1","kind":"audio","items":[{"id":"c16","start":0.0,"end":29.6,"type":"clip","clip":"m1","name":"demo.mp4","in":0.0,"out":29.6,"volumeDb":-3.0,"fadeIn":0.5,"fadeOut":2.5}]}],
 "transitions":[{"id":"x8","type":"dissolve","itemA":"c2","itemB":"c3","start":3.9,"end":4.5},
                {"id":"x17","type":"fade_to_black","item":"c7","duration":1.5}]}
```

### `clip_insert(clipId, track, at, in?, out?, withAudio=true)`
`in`/`out` are seconds into the source. An A/V clip on a video track also puts its audio on the matching audio track
(V1→A1, V2→A2), linked.
```
clip_insert(clipId="m1", track="V1", at=0, in=0.3, out=4.5, withAudio=false)
→ inserted c2 on V1 0.000–4.200 (demo.mp4 in=0.300 out=4.500); audio not inserted (withAudio=false)

clip_insert(clipId="m1", track="V1", at=0)
→ inserted c4 on V1 0.000–6.000 (bars.mp4 in=0.000 out=6.000); audio part c5 on A1

clip_insert(clipId="m1", track="V1", at=21.6, in=140.3, out=190)
→ ERROR m1 (demo.mp4) is 185.600s long, out=190.000 is invalid (max 185.600)
```

### `clip_move(itemId, track?, at)`
```
clip_move(itemId="c4", at=2)
→ moved c4 (bars.mp4) to V1 2.000–8.000; linked c5 on A1 moved too
```
Dissolves that no longer fit are removed, and each removal is reported as a `note:` line.

### `clip_trim(itemId, in?, out?)`
Source-time trim. The item keeps its start; its end moves. For titles, `out` is the new duration.
```
clip_trim(itemId="c4", in=1, out=5)
→ trimmed c4 (bars.mp4) to in=1.000 out=5.000 (1.000–5.000 on timeline); linked c5 trimmed too
```

### `clip_remove(itemId)`
Removes the item, its linked part, and any transitions on them. The clip stays in the bin.

### `clip_split(itemId, at)`
`at` is timeline time, strictly inside the item. The left part keeps the id.
```
clip_split(itemId="c4", at=3)
→ split c4 (bars.mp4) at 3.000 into c4 (1.000–3.000) and c6 (3.000–7.000); linked part split into c5 and c7
```

### `title_add(text, at, duration, track="V2", style="lower_third")`
`style` is one of the presets in `title-presets.json`: `lower_third`, `big_center`, `top_caption`, `subtitle`, `headline_top`, `tiktok_box` (black bold text on white rounded boxes that hug each line, like TikTok captions).
Use `\n` for a line break. Put titles on V2 to overlay the video on V1.
```
title_add(text="Watch it think", at=10.2, duration=3.8, track="V2", style="lower_third")
→ added title t14 "Watch it think" (lower_third) on V2 10.200–14.000
```

### `transition_add(type, itemA, itemB?, duration=1.0)`
* `dissolve` / `wipe`: a same-track mix between items that touch (itemA ends where itemB starts). It needs spare
  source media around the cut, as in Kdenlive. Linked audio parts get a crossfade automatically.
* `fade_to_black`: fades the last `duration` seconds of itemA to black, and fades out its linked audio.
```
transition_add(type="dissolve", itemA="c2", itemB="c3", duration=0.6)
→ added dissolve x7 between c2 and c3 on V1, 0.600s (5.100–5.700)

transition_add(type="dissolve", itemA="c4", itemB="c5", duration=1)
→ ERROR dissolve of 1.000s between c4 and c5 needs 1.000s of spare media around the cut, but c4 (bars.mp4) has
  0.000s after its out point and c5 (testsrc.mp4) has 0.000s before its in point. Free media first:
  clip_trim(c5, in=1.000) keeps the clips touching.

transition_add(type="fade_to_black", itemA="c7", duration=1.5)
→ added fade_to_black x17 on c7 (demo.mp4), last 1.500s (28.100–29.600)
```

### `audio_set(itemId, volumeDb?, fadeIn?, fadeOut?)`
Omitted values stay as they are. On the video part of an A/V clip it applies to the linked audio part.
```
audio_set(itemId="c16", volumeDb=-3, fadeIn=0.5, fadeOut=2.5)
→ audio of c16: volume -3.0 dB, fade in 0.500s, fade out 2.500s
```

### `undo()`
```
→ undid audio_set c11; 7 more step(s) available
```

## Effects (Kdenlive's whole catalog)

The server ships Kdenlive's own effect definitions (`src/main/resources/kdenlive-assets`, synced from Kdenlive's
`data/` by `scripts/sync-kdenlive-assets.sh`), so every effect in Kdenlive's Effects panel can be added, set and
keyframed, and Kdenlive opens the result as if you had made it there. Kdenlive's `excluded_effects.txt` is honoured,
and an effect is offered only when the local melt has its MLT service (the bundled macOS tools have every module the
Kdenlive.app has: 292 effects on macOS arm64, the same set Kdenlive offers there).

Values use the units Kdenlive shows in its effect panel (e.g. Intensity 0..400 %, not MLT's 0..4). Keyframe times
are seconds from the start of the target; for items they are stored in source frames, so trimming or splitting a
clip keeps the animation on the same pictures.

Targets: an item (`c4`, `t7`), a track (`V1`, `A2`: applies to everything on it) or `master` (the whole timeline,
after the tracks are mixed). An audio effect on the video part of an A/V clip goes to its audio part.

### `effect_catalog(query?, kind?, category?, includeUnavailable=false)`
```
effect_catalog()                      → 292 effects available in this melt. Categories: Blur and Sharpen: 11 (...) ...
effect_catalog(query="blur")          → avfilter.gblur — Gaussian Blur: Apply Gaussian Blur filter ...
effect_catalog(query="eq", kind="audio")
```

### `effect_info(effect)`
Every settable parameter with type, range, default and choices; `*` marks keyframable ones.
```
effect_info(effect="avfilter.gblur")
→ av.sigma* (number) "Sigma" (0..600) default 10 — Set sigma, the standard deviation of Gaussian blur ...
```

### `effect_add(target, effect, params?, keyframes?, position?)`
`params`: fixed values `{name: value}`. `keyframes`: `[{t, ease?, <param>: value, ...}]`, `t` in seconds from the
target start or `"end"`. Eases: `linear` (default), `smooth`, `discrete`, `ease_in`, `ease_out`, `ease_in_out`, and
`sine|quad|cubic|quart|quint|expo|circ|back|elastic|bounce` + `_in|_out|_in_out`. Value types: numbers, `true/false`,
choices by value or label, colors `#RRGGBB[AA]`, rects `{x,y,w,h,opacity}` in pixels, points `{x,y}`.
```
effect_add(target="c4", effect="avfilter.gblur", keyframes=[{"t":0,"av.sigma":30},{"t":1.2,"av.sigma":0}])
→ added e11 Gaussian Blur (avfilter.gblur) to c4 (clip.mp4): av.sigma keyframed at 0.000s, 1.200s
effect_add(target="master", effect="vignette", params={"opacity": 0.6})
```

### `effect_set(effectId, params?, keyframes?, enabled?)` / `effect_move(effectId, index)` / `effect_remove(effectId)`
`params` makes a parameter constant, `keyframes` replaces the animation of the parameters it names, `enabled=false`
bypasses the effect. Effects run first to last; `effect_move` reorders them.

### `effect_list(target?, all=false)`
Current values (non-default ones unless `all`) and keyframes, per target. `timeline_get` shows the same compactly.

### `clip_transform(target, keyframes, clamp=true)`
Camera moves (Ken Burns, punch-in, push, spin, fade) as Kdenlive's Transform effect: `[{t, zoom, x?, y?, rotation?,
opacity?, ease?}]`. `zoom` 1 = normal; `x`/`y` = point of the picture to center on (0..1). With `clamp`, a zoomed
picture never shows black edges. Calling it again replaces the move.
```
clip_transform(target="c4", keyframes=[{"t":0,"zoom":1},{"t":"end","zoom":1.3,"x":0.7,"y":0.4,"ease":"ease_in_out"}])
→ camera move on c4 (Transform e10): 0.000s zoom 1 at (0.5, 0.5) → 5.967s zoom 1.3 at (0.7, 0.4) ease_in_out
```

## Compositions (transitions between tracks)

Kdenlive's Compositions panel: a track combined with a lower one (or the black background) over a time span. All
of Kdenlive's compositions are available (`composition_catalog`): wipe and dissolve with any of Kdenlive's wipe shapes,
slide, Composite and transform (`qtblend`: picture-in-picture, scale, opacity, blend modes), region, affine, Cairo
blend and the frei0r blend modes. Keyframe times are seconds from the composition start. Same-track wipes
(`transition_add(type="wipe")`) take a `shape` too.

### `composition_catalog(query?)` / `composition_info(type)`
`composition_info` lists parameters and, for wipes, every shape by Kdenlive's name (Iris Circle, Clock, Barn Door
Horizontal, Matrix Snake Vertical, Curtain, Spiral, ...).

### `composition_add(type, track, at, duration, withTrack?, params?, keyframes?)`
`track` is the upper track; `withTrack` the lower one (default: the next video track below, `background` = black).
Slides take `{"from": left|right|up|down|center, "to": ..., "fromOpacity": 0..100, "toOpacity": 0..100}`.
```
composition_add(type="wipe", track="V2", at=1, duration=2, params={"luma":"Iris Circle","softness":10})
→ added composition x6 Wipe (wipe) on V2 over V1, 1.000–3.000: luma=luma16.pgm; softness=10.0
composition_add(type="qtblend", track="V2", at=4, duration=2, keyframes=[{"t":0,"rect":{"x":1240,"y":60,"w":640,"h":360}},
    {"t":"end","rect":{"x":60,"y":660,"w":640,"h":360,"opacity":0.6},"ease":"ease_in_out"}])
composition_add(type="slide", track="V2", at=6, duration=2, params={"rect":{"from":"right","to":"center"}})
```

### `composition_set(compositionId, params?, keyframes?, at?, duration?, withTrack?)` / `composition_remove(compositionId)`

## Speed, color clips, subtitles, guides

### `clip_speed(itemId, speed, keepPitch=false)`
Kdenlive's Change Speed (timewarp): 0.5 = half speed, 2 = double. The clip keeps its start and the same source
range, so its length changes; the linked part follows and effect keyframes stay on the same pictures. In/out in
`timeline_get` and `clip_trim` stay in source seconds.
```
clip_speed(itemId="c5", speed=0.5, keepPitch=true)
→ speed of c5 (clipA.mp4) set to 50% (pitch kept): now 2.000–6.000 (4.000s, was 2.000s); linked c6 too
```

### `media_add_color(color, name?)`
A color clip in the bin (backgrounds, title cards); place it with `clip_insert(..., out=<seconds>)`.

### `subtitle_add(start, end, text)` / `subtitle_list()` / `subtitle_set(number, ...)` / `subtitle_remove(number?)`
Kdenlive's subtitle track: `<project>.kdenlive.ass` next to the project plus its internal subtitle filter, so
Kdenlive shows the lines on its Subtitles track and renders burn them in.

### `subtitle_import(path, offset=0, replace=false)` / `subtitle_style(font, size, color, outlineColor, outline, bold, position, marginV, box, backColor)`
Import .srt/.vtt/.ass (e.g. a speech-to-text transcript). The style is the Default ASS style.

### `marker_add(at, comment?, category=0, duration=0, clipId?)` / `marker_list(clipId?)` / `marker_remove(at?, clipId?)`
Timeline guides (Kdenlive's Guides) or, with `clipId`, markers on a bin clip. Categories 0..8 are Kdenlive's
colors (purple, blue, cyan, green, yellow, orange, dark orange, red-orange, red).

## Tracks

### `track_add(kind, name?)` / `track_set(track, muted?, hidden?, locked?, name?)` / `track_remove(track)`
Track ids are positional like Kdenlive's: new video tracks go on top (V3 covers V2 and V1), new audio tracks at the
bottom. Removing a track removes its items (and their linked parts) and renumbers the others.
```
track_add(kind="video", name="Overlays") → added video track V3 "Overlays" on top ...; tracks: V3, V2, V1, A1, A2
```

## Looking at the edit

### `preview_frame(t)`
PNG of the timeline frame at `t`, at most 960 px wide, with titles, mixes and fades as they will render.
```
preview_frame(t=17.5) → frame at 17.500s (frame 525) + PNG
```

### `contact_sheet(start?, end?, count=8)`
`count` timeline frames evenly spaced over `[start, end]`, 4 per row, each stamped with its time.
```
contact_sheet(count=12)
→ 12 frames from 0.000s to 29.567s: 0.000 2.700 5.367 8.067 10.767 13.433 16.133 18.800 21.500 24.200 26.867 29.567 + PNG
```

## Rendering

### `render(outputPath, preset="draft_540p")`
`draft_540p` (fast check), `final_1080p` (H.264 CRF 18), `vertical_1080p` (vertical projects only). Returns at once.
The render uses a snapshot, so later edits do not affect it. Only one render runs at a time.
```
render(outputPath="/Users/me/Videos/promo.mp4", preset="final_1080p")
→ render started: jobId=r1 -> /Users/me/Videos/promo.mp4. Poll render_status(jobId="r1").
```

### `render_status(jobId)`
```
→ running: rendering 43% (12.1s elapsed, eta 16s)
→ done: /Users/me/Videos/promo.mp4 (29.610s (timeline 29.600s), 1920x1080, video h264, audio aac) rendered in 41.4s
→ failed: melt exited with code 1 (log: ~/.kdenlive-mcp/logs/render-r1.log) + last log lines
```

### `render_cancel(jobId)`
Kills melt and deletes the partial file.
