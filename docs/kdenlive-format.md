# The .kdenlive format we generate (document version 1.1, Kdenlive 26.08.3)

**Source of truth:** the fixtures in `fixtures/`, written by **Kdenlive 26.08.3** (official AppImage, MLT 7.43.0).
They were driven over D-Bus and xdotool in a headless X server, using clips cut from the user's demo recording:

| Fixture | Made by |
|---|---|
| `01-empty.kdenlive` | File → New (HD 1080p 30 fps, 2 video + 2 audio tracks) → Save As |
| `02-one-clip.kdenlive` | Import `demo_a.mp4`, insert at 0 on V1 (audio lands on A1) |
| `03-full.kdenlive` | `demo_a` 0–5.5 s and `demo_b` 5.5–11 s (in=0.5) on V1/A1 with a 1 s **mix** (U); title on V2 at 1.067 s for 3 s with "Fade in"; `music.m4a` on A2 at 0 s with Volume −6 dB and a 1 s Fade out |
| `04-extras.kdenlive` | 03 + **Fade to black** (3 s, "Fade to Black" ticked) on `demo_b` |
| `03-full.mp4` | `melt 03-full.kdenlive` with Kdenlive's bundled melt (reference render) |

Supporting sources: Kdenlive's `dev-docs/fileformat.md`, `src/definitions.h` and the `tests/dataset` projects
(in `lamtanloc512/kdenlive`).

## 1. Times and numbers

* `LC_NUMERIC="C"`. Times are written as `HH:MM:SS.mmm` clock strings, with milliseconds rounded at the project fps
  (30 fps: frame 179 = `00:00:05.967`). Kdenlive reads frame counts too. We write clock strings like Kdenlive does.
* Entry `in`/`out` are **source frames, out inclusive**. 6 s at 30 fps = `in=00:00:00.000 out=00:00:05.967`.
* Filters on entries: `in`/`out` are **source** frames of the entry (no attributes = whole clip).
* Mix transitions: `in`/`out` are **track** positions, and `out = in + duration` (in=5.000 out=6.000 for a 1 s mix).

## 2. Document order (what Kdenlive writes, and what we write)

```
<mlt LC_NUMERIC="C" producer="main_bin" root="<project dir>" version="7.x">
  <profile .../>
  <producer id="producer0">  black background: mlt_service=color resource=black length=2147483647
                             eof=continue aspect_ratio=1 mlt_image_format=rgba set.test_audio=0
                             kdenlive:playlistid=black_track
  for each track, BOTTOM-UP (A2, A1, V1, V2):
      <chain|producer> per-track copies of the media used on this track (see §4)
      <playlist id=playlistN>    items (blanks + entries)
      <playlist id=playlistN+1>  second layer: B clips of mixes
      <tractor id=tractorK>      the track: 2 <track> refs + same-track mix transitions + internal filters
  <tractor id="{seq-uuid}">       the sequence (§5)
  bin copies of every media clip (chainN), title producers are shared (§4)
  <playlist id="main_bin">        document properties + one entry per bin clip (§6)
  <tractor id=tractorX>           project tractor: kdenlive:projectTractor=1, one <track producer="{seq-uuid}" in out>
</mlt>
```

### Track tractor

```xml
<tractor id="tractor1" in="00:00:00.000" out="<last frame>">
  <property name="kdenlive:audio_track">1</property>   <!-- audio tracks only; also on both playlists -->
  <property name="kdenlive:trackheight">75</property>
  <property name="kdenlive:timeline_active">1</property>
  <property name="kdenlive:collapsed">0</property>
  <property name="kdenlive:thumbs_format"/>
  <property name="kdenlive:audio_rec"/>
  <track hide="video" producer="playlist2"/>          <!-- audio: hide="video"; video: hide="audio" -->
  <track hide="video" producer="playlist3"/>
  <transition .../>                                   <!-- mixes on this track -->
  <filter> volume  internal_added=237 disable=1 window=75 max_gain=20dB channel_mask=-1 </filter>   <!-- audio only -->
  <filter> panner  internal_added=237 disable=1 channel=-1 start=0.5 </filter>                       <!-- audio only -->
  <filter> audiolevel internal_added=237 disable=1 iec_scale=0 dbpeak=1 </filter>                    <!-- audio only -->
</tractor>
```
An empty track tractor has no `out` attribute. Locked tracks add `kdenlive:locked_track=1`; after an unlock
Kdenlive writes it empty.

## 3. Timeline entries

```xml
<playlist id="playlist2">
  <property name="kdenlive:audio_track">1</property>
  <blank length="00:00:05.000"/>
  <entry in="00:00:00.000" out="00:00:05.967" producer="chain2">
    <property name="kdenlive:id">5</property>            <!-- bin id -->
    <property name="kdenlive:activeeffect">0</property>  <!-- only when it has effects -->
    <filter .../>                                        <!-- clip effects (§7) -->
  </entry>
</playlist>
```

## 4. Media producers

* **Audio/video files are `<chain>`s** with `mlt_service=avformat-novalidate`. Kdenlive writes **one copy per
  track that uses the clip, plus one for the bin**. The track copies set `set.test_audio`/`set.test_image`:
  video track: `test_audio=1 test_image=0`; audio track: `test_audio=0 test_image=1`.
  The bin copy sets neither, and also has `kdenlive:monitorPosition=0`. Audio-only bin copies use `mlt_service=avformat`.
* Common properties: `length` (frames), `eof=pause`, `resource` (relative to `root`), `seekable=1`,
  `audio_index`, `video_index` (-1 if none), `astream`, `vstream`, `format=3` (AV), `mute_on_pause=0`,
  `kdenlive:id` (bin id), `kdenlive:folderid=-1`, `kdenlive:clip_type`, `kdenlive:control_uuid` (one uuid per bin clip,
  same on every copy), `kdenlive:file_size`, `kdenlive:file_hash` (md5 of the first and last 1 MB, recomputed by
  Kdenlive if missing), `xml=was here`.
* `kdenlive:clip_type`: **0 = A/V file**, 1 = audio only, 2 = video only *and* title files, 17 = sequence.
* `<chain out=…>` = length − 1.
* **Title clips** are one `<producer>` shared by the timeline entry and the bin:
  `mlt_service=kdenlivetitle`, `resource=<path to .kdenlivetitle>` (or `xmldata` when made in the title editor),
  `length`, `kdenlive:duration=HH:MM:SS:FF`, `kdenlive:clip_type=2`, `force_reload=0`, `seekable=1`, `aspect_ratio=1`.
  We write a `.kdenlivetitle` file per title next to the project (`<project>.titles/tN.kdenlivetitle`), using the
  same XML as Kdenlive's `data/titles/simple.kdenlivetitle`.

## 5. Sequence tractor

```xml
<tractor id="{uuid}" in="00:00:00.000" out="<last frame>">
  <property name="kdenlive:uuid">{uuid}</property>
  <property name="kdenlive:clipname">Sequence 1</property>
  <property name="kdenlive:sequenceproperties.hasAudio">1</property>
  <property name="kdenlive:sequenceproperties.hasVideo">1</property>
  <property name="kdenlive:sequenceproperties.activeTrack">2</property>
  <property name="kdenlive:sequenceproperties.tracksCount">4</property>
  <property name="kdenlive:sequenceproperties.documentuuid">{uuid}</property>
  <property name="kdenlive:duration">00:00:12.000</property>    <!-- clock string of total length -->
  <property name="kdenlive:maxduration">360</property>          <!-- frames -->
  <property name="kdenlive:producer_type">17</property>
  <property name="kdenlive:id">3</property>                     <!-- bin id of the sequence -->
  <property name="kdenlive:clip_type">0</property>
  <property name="kdenlive:folderid">2</property>               <!-- the "Sequences" folder -->
  <property name="kdenlive:sequenceproperties.audioTarget">1</property>
  <property name="kdenlive:sequenceproperties.videoTarget">2</property>
  <property name="kdenlive:sequenceproperties.tracks">4</property>
  ... disablepreview=0 position=0 scrollPos=0 verticalzoom=1 zonein=0 zoneout=75 zoom=8 ...
  <property name="kdenlive:sequenceproperties.groups">[ AVSplit groups ]</property>
  <property name="kdenlive:sequenceproperties.guides">[ ]</property>
  <track producer="producer0"/>     <!-- black -->
  <track producer="tractor0"/>      <!-- A2 -->
  <track producer="tractor1"/>      <!-- A1 -->
  <track producer="tractor2"/>      <!-- V1 -->
  <track producer="tractor3"/>      <!-- V2 -->
  <!-- internal compositing, one per track, a_track=0 b_track=i -->
  audio: mlt_service=mix kdenlive_id=mix internal_added=237 always_active=1 accepts_blanks=1 sum=1
  video: mlt_service=qtblend kdenlive_id=qtblend internal_added=237 always_active=1 compositing=0 distort=0 rotate_center=0
  <!-- master: volume + panner filters, internal_added=237, disable=1 -->
</tractor>
```
An empty sequence has `kdenlive:duration=00:00:00:01`, `maxduration=1` and tractor `out=00:05:00.000`.
The black producer's `out` is the timeline length + 5 minutes (`00:05:12.000` for a 12 s timeline).

**AVSplit groups** link the audio and video parts of one clip. The leaf data is `"<track>:<position>:-1"`.
`<track>` is the track index counted from the bottom **without** the black track (A2=0, A1=1, V1=2, V2=3).
`<position>` is the item's start frame **as written** (for a mix B clip, the extended start).

## 6. main_bin

Properties (all present in an empty project):
`kdenlive:folder.-1.2=Sequences`, `kdenlive:sequenceFolder=2`, and `kdenlive:docproperties.*`:
`audioChannels=2 binsort=0 documentid=<ms> enableTimelineZone=0 enableexternalproxy=0 enableproxy=0
externalproxyparams= generateimageproxy=0 generateproxy=0 guidesCategories=[…9 default categories…]
kdenliveversion=26.08.3 patchversion=1 profile=atsc_1080p_30 proxy*=… seekOffset=9000 sessionid={uuid}
uuid={seq uuid} version=1.1 opensequences={uuid} activetimeline={uuid}`. Also
`kdenlive:expandedFolders`, `kdenlive:binZoom=4`, `kdenlive:extraBins=project_bin:-1:0`, `kdenlive:documentnotes`,
`kdenlive:documentnotesversion=2`, and `xml_retain=1`. Kdenlive also stores the window `layout` here; we don't write it.

Entries: first the sequence (`in=0 out=0`), then each bin clip (`in=0 out=length-1`).

## 7. Mixes, effects, titles: exact XML

**Same-track mix.** Example: cut at 5.5 s, 1 s, centred, so `mixcut` = 15 frames before the cut.
* A stays in playlist 1 and is **extended** to `cut + (duration − mixcut)`.
* B goes to playlist 2 (`<blank>` up to its start), starting at `cut − mixcut`, with `in` reduced by `mixcut`.
* The transition sits inside the track tractor:

```xml
<!-- video track -->
<transition in="00:00:05.000" out="00:00:06.000">
  a_track=0 b_track=1 factory=loader resource="" mlt_service=luma kdenlive_id=luma kdenlive:mixcut=15
  softness=0 alpha_over=0 fix_background_alpha=1 invert=0 reverse=0
</transition>
<!-- audio track (created automatically for the linked audio parts) -->
<transition in="00:00:05.000" out="00:00:06.000">
  a_track=0 b_track=1 duck_threshold=0 duck_attenuation=-12 duck_level=0 duck_fade_in=1500 duck_fade_out=250
  prefix="" mlt_service=mix kdenlive_id=mix kdenlive:mixcut=15 start=-1 accepts_blanks=1 reverse=0
</transition>
```
A **wipe** is the same luma with `resource` set to a luma file. Kdenlive refuses a mix without enough spare media
("Not enough frames at clip start to apply the mix"), the same rule `transition_add` enforces.

**Volume** (`kdenlive_id=volume`): `<filter> window=75 max_gain=20dB level="00:00:00.000=-6" channel_mask=-1 mlt_service=volume kdenlive_id=volume kdenlive:collapsed=0`

**Audio fade out** (`fadeout`), on the last N frames: `<filter in=out-N out=out> window=75 max_gain=20dB channel_mask=-1 mlt_service=volume kdenlive_id=fadeout gain=1 end=0`.
**Audio fade in** (`fadein`, from Kdenlive's `data/effects/fadein.xml`): `<filter in=in out=in+N> mlt_service=volume kdenlive_id=fadein gain=0 end=1`.

**Video fade in (alpha)**, used for titles: `<filter out=N> start=1 level=1 mlt_service=brightness kdenlive_id=fade_from_black alpha="0=0;-1=1"`.

**Fade to black** (video): `<filter in=out-N out=out> start=1 level="0=1;-1=0" mlt_service=brightness kdenlive_id=fade_to_black alpha=1`.
Kdenlive does **not** fade the linked audio part; our `fade_to_black` adds a `fadeout` on the audio part as well.

## 8. Rendering with melt

* `melt project.kdenlive -consumer avformat:out.mp4 …` plays the project tractor.
* **Titles need a Qt platform.** Without a display, `kdenlivetitle` renders opaque black frames. Set
  `QT_QPA_PLATFORM=offscreen` when there is no `DISPLAY`/`WAYLAND_DISPLAY`. Verified with melt 7.43 (Kdenlive bundle)
  and melt 7.22 (Ubuntu).
* The bundled melt (AppImage/app bundle) needs its environment: `MLT_REPOSITORY`, `MLT_DATA`, `MLT_PROFILES_PATH`,
  `FREI0R_PATH`, `LADSPA_PATH`.

## 9. Round-trip rules (tests)

Two documents are "the same" after normalization:
1. element ids are renamed canonically (by element type and order of appearance), and references are rewritten;
2. property order is ignored;
3. volatile properties are dropped: `docproperties.layout`, `sessionid`, `documentid`, `browserurl`,
   `sequenceproperties.position|scrollPos|zoom|activeTrack|audioTarget|videoTarget`, `kdenlive:activeeffect`,
   `kdenlive:collapsed`, `trackheight`, `kdenlive:locked_track`, `binZoom`, `kdenlive:meta.*`, `kdenlive:kextractor`,
   `kdenlive:record_start_frame*`, `root`, `version`.
