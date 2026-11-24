# The .kdenlive format (document version 1.1): what we must read and write

> **Status: DRAFT.** Built from Kdenlive's own source (`lamtanloc512/kdenlive`, version 27.03.70 dev:
> `dev-docs/fileformat.md`, `src/definitions.h`, `src/doc/kdenlivedoc.cpp`) and the test projects in
> `tests/dataset/`, the newest written by Kdenlive 24.01.95.
> Anything marked ❓ must be checked against the reference projects in `./fixtures/`
> (see `fixtures/README.md`) before we generate it.

## 1. Facts checked so far

| Fact | Source |
|---|---|
| Current document version is `1.1`, patch `1` (`kdenlive:docproperties.version` = `1.1`). Multiple sequences arrived in 23.04. | `kdenlivedoc.cpp:68-71` |
| On open, Kdenlive runs `DocumentValidator` and upgrades older documents, so a 1.04 file opens in 25.x. We still write 1.1 to avoid the upgrade path. | `documentvalidator.h` |
| `melt` plays a `.kdenlive` file directly. It ignores the `kdenlive:*` properties and plays the last tractor (the "project tractor"). | Tested here: `melt test-nesting.kdenlive -consumer avformat:out.mp4` gave a 5.0 s file with audio and video |
| Grabbing one frame works with `melt x.kdenlive in=F out=F -consumer avformat:f.png vcodec=png`. | Tested here |
| Numbers are written with `LC_NUMERIC="C"` (dot as the decimal separator). Times are written as clock strings `HH:MM:SS.mmm`. Kdenlive also accepts plain frame counts. | All dataset files |
| `kdenlive:clip_type` / `kdenlive:producer_type` use `ClipType::ProducerType`: 0 Unknown, 1 Audio, 2 Video, 3 AV, 4 Color, 5 Image, 6 Text (title), 11 TextTemplate, 12 QText, 17 Timeline (sequence). | `definitions.h:174` |
| Title clips are producers with `mlt_service=kdenlivetitle` and the title document in `xmldata`. `melt` renders them through its qt module. | `clipcontroller.cpp:323`, `melt -query producers` |

## 2. Document skeleton (what we generate, in this order)

```xml
<?xml version='1.0' encoding='utf-8'?>
<mlt LC_NUMERIC="C" producer="main_bin" version="7.x.0" root="/abs/project/dir">
  <profile description="HD 1080p 30 fps" width="1920" height="1080" progressive="1"
           sample_aspect_num="1" sample_aspect_den="1" display_aspect_num="16" display_aspect_den="9"
           frame_rate_num="30" frame_rate_den="1" colorspace="709"/>

  <!-- (A) One bin producer/chain per media clip. kdenlive:id = bin id (int, unique). -->
  <chain id="chain0" out="...">                       <!-- avformat clips are <chain> since 23.04 ❓ -->
    <property name="resource">relative/or/abs/path.mp4</property>
    <property name="mlt_service">avformat-novalidate</property>
    <property name="kdenlive:id">2</property>
    <property name="kdenlive:clip_type">0</property>   <!-- ❓ AV files show 0 in dataset; confirm -->
    <property name="kdenlive:folderid">-1</property>
    <property name="kdenlive:clipname">...</property>
    <property name="length">N</property>
    <property name="kdenlive:file_hash">md5</property> <!-- ❓ required, or recomputed on open? -->
  </chain>
  <producer id="producerN" ...>                      <!-- color / title (kdenlivetitle) / image clips -->

  <!-- (B) For each timeline track: 2 playlists (the 2nd holds the B clip of mixes) inside a track tractor. -->
  <playlist id="playlist0"> <property name="kdenlive:audio_track">1</property> ...entries/blanks... </playlist>
  <playlist id="playlist1"> <property name="kdenlive:audio_track">1</property> </playlist>
  <tractor id="tractor0" in="0">
    <property name="kdenlive:audio_track">1</property>   <!-- audio tracks only -->
    <property name="kdenlive:track_name">A1</property>    <!-- ❓ -->
    <property name="kdenlive:trackheight">67</property>
    <property name="kdenlive:timeline_active">1</property>
    <track hide="video" producer="playlist0"/>            <!-- audio: hide="video"; video: hide="audio" -->
    <track hide="video" producer="playlist1"/>
    <!-- same-track mixes (dissolve/wipe) go here as <transition> with kdenlive:mixcut -->
    <!-- internal audio filters on audio tracks: volume, panner, audiolevel (internal_added=237, disable=1) -->
  </tractor>

  <!-- (C) The sequence tractor. Its id is the sequence uuid. Track 0 is the black background producer. -->
  <producer id="producer_black" ...> mlt_service=color, resource=black, length=2147483647 </producer>
  <tractor id="{uuid}" in="0" out="LAST_FRAME">
    <property name="kdenlive:uuid">{uuid}</property>
    <property name="kdenlive:producer_type">17</property>
    <property name="kdenlive:id">BINID</property>
    <property name="kdenlive:clipname">Sequence 1</property>
    <property name="kdenlive:duration">HH:MM:SS.mmm</property>
    <property name="kdenlive:maxduration">FRAMES</property>
    <property name="kdenlive:sequenceproperties.documentuuid">{uuid}</property>
    <property name="kdenlive:sequenceproperties.hasAudio">1</property>
    <property name="kdenlive:sequenceproperties.hasVideo">1</property>
    <property name="kdenlive:sequenceproperties.tracksCount">N</property>
    <property name="kdenlive:sequenceproperties.groups">[ ...AVSplit groups json... ]</property>
    <property name="kdenlive:sequenceproperties.guides">[]</property>
    ... activeTrack, audioTarget, videoTarget, zoom, position, zonein/zoneout ...
    <track producer="producer_black"/>
    <track producer="tractor0"/>  <!-- audio tracks first (A2, A1), then video (V1, V2). ❓ order -->
    ...
    <!-- internal compositing: one per track, a_track=0 b_track=i -->
    <transition> mlt_service=mix, sum=1, always_active=1, internal_added=237 (audio tracks) </transition>
    <transition> mlt_service=qtblend, internal_added=237, always_active=1 (video tracks; 24.01 writes qtblend, 22.x wrote frei0r.cairoblend) ❓ 25.x </transition>
    <!-- user compositions between tracks would go here (not used in v1) -->
  </tractor>

  <!-- (D) The bin: project settings + one entry per bin clip, including the sequence. -->
  <playlist id="main_bin">
    <property name="kdenlive:docproperties.version">1.1</property>
    <property name="kdenlive:docproperties.kdenliveversion">25.08.x</property>  <!-- ❓ value we claim -->
    <property name="kdenlive:docproperties.profile">atsc_1080p_30</property>
    <property name="kdenlive:docproperties.documentid">EPOCH_MILLIS</property>
    <property name="kdenlive:docproperties.uuid">{uuid}</property>
    <property name="kdenlive:docproperties.activetimeline">{uuid}</property>
    <property name="kdenlive:docproperties.opensequences">{uuid}</property>
    <property name="kdenlive:sequenceFolder">-1</property>
    <property name="xml_retain">1</property>
    <entry producer="chain0" in="0" out="..."/>
    <entry producer="{uuid}" in="0" out="0"/>
  </playlist>

  <!-- (E) The project tractor, always last: what melt plays. -->
  <tractor id="tractorX" in="0" out="LAST_FRAME">
    <property name="kdenlive:projectTractor">1</property>
    <track producer="{uuid}" in="0" out="LAST_FRAME"/>
  </tractor>
</mlt>
```

## 3. Timeline clips

* A clip on the timeline is an `<entry producer="..." in="..." out="...">` in the track's **first** playlist,
  with `<property name="kdenlive:id">BINID</property>`. Gaps are `<blank length="..."/>`.
* `in`/`out` are source frames, and **`out` is inclusive** (a 5 s clip at 25 fps is `in=0 out=124`).
* ❓ Kdenlive ≥ 23.04 writes a separate copy of the producer per track type (video and audio)
  instead of pointing at the bin producer. Fixture 2 will show whether timeline entries reference
  the bin chain or per-track copies, and what id scheme those copies use (`chainN`, `producerN`, `..._video`?).
* An AV clip dropped on V1 creates a video part on V1 and an audio part on A1, linked by an `AVSplit`
  group in `sequenceproperties.groups`. Leaves are `"trackPos:framePos"`, where trackPos counts tracks
  from the bottom of the timeline: `{"type":"AVSplit","children":[{"type":"Leaf","leaf":"clip","data":"2:0"},{"type":"Leaf","leaf":"clip","data":"1:0"}]}` ❓ confirm indexing.

## 4. Same-track mix (dissolve / wipe)

From `test-mix.kdenlive`:

* Clip A stays in playlist 0. Clip B moves to playlist 1 of the **same track tractor**, and the two overlap by the mix duration.
* A `<transition in=… out=…>` inside the track tractor: `mlt_service=luma`, `kdenlive_id=luma`, `a_track=0`,
  `b_track=1`, `kdenlive:mixcut=<frames from mix start to the original cut>`, `softness`, `alpha_over=1`,
  `invert`, `reverse` (1 when A sits in the second playlist).
* Dissolve = `luma` without a `resource`. Wipe = `luma` with a luma file `resource` ❓ (path form in 25.x).
* Audio crossfade on audio tracks: `mlt_service=mix` with `kdenlive_id=mix` ❓.
* The overlap needs **handles**: media beyond A's out point and before B's in point. If a clip is used
  to its very end, the mix is impossible or shorter. We validate this and report how much handle is missing.

## 5. Effects we need (v1)

| Need | Kdenlive effect id | MLT service | Note |
|---|---|---|---|
| Clip volume in dB | `volume` (or `gain`) | `volume` | ❓ property names (`level` in dB?) |
| Audio fade in/out | `fadein` / `fadeout` | `volume` | `data/effects/fadein.xml` |
| Fade to black | `fade_to_black` | `brightness` | `data/effects/fade_to_black.xml` |
| Title fade | ❓ alpha fade or `fade_from_black` | ❓ | check fixture 3 |

Filters on a clip are `<filter>` children of the timeline `<entry>`, with `kdenlive_id` and
`in`/`out` relative to the clip ❓.

## 6. Title clip (kdenlivetitle)

```xml
<producer id="producerN">
  <property name="mlt_service">kdenlivetitle</property>
  <property name="kdenlive:clip_type">6</property>
  <property name="xmldata">&lt;kdenlivetitle duration="..." LC_NUMERIC="C" width="1920" height="1080" out="..."&gt;
     &lt;item type="QGraphicsTextItem" z-index="0"&gt;
       &lt;position x="..." y="..."&gt;&lt;transform&gt;1,0,0,0,1,0,0,0,1&lt;/transform&gt;&lt;/position&gt;
       &lt;content font="..." font-pixel-size="..." font-color="r,g,b,a" alignment="4" ...&gt;TEXT&lt;/content&gt;
     &lt;/item&gt;
     &lt;startviewport rect="0,0,1920,1080"/&gt;&lt;endviewport rect="0,0,1920,1080"/&gt;&lt;background color="0,0,0,0"/&gt;
  &lt;/kdenlivetitle&gt;</property>
</producer>
```
❓ Every attribute above (font weight, line spacing, shadow, typewriter...) has to come from fixture 3.
We emit exactly what Kdenlive 25.x writes, filled in from `title-presets.json`.

## 7. Round-trip rules (for the tests)

"Semantic diff = no change" means equal after this normalization:
1. Element ids are replaced by canonical ones (by order of appearance per element type); references follow.
2. Property order is ignored; whitespace-only text is ignored.
3. Times are compared as frames (`00:00:05.000` at 25 fps == `125`).
4. Volatile properties are ignored: `docproperties.position`, `scrollPos`, `zoom`, `documentid`? ❓, `root`.

## 8. Open questions for the fixtures

1. AV clip: `<chain>` vs `<producer>`; `clip_type` value; is `file_hash`/`file_size` required on load?
2. Timeline entries: bin producer reference vs per-track copies, and their id naming.
3. Track order in the sequence tractor, and `kdenlive:track_name`.
4. Internal compositing per video track: 24.01 writes `qtblend`. Confirm 25.x does too.
5. Exact title `xmldata` for 25.x and how a title fade is stored.
6. Crossfade on video *and* audio: two mixes? Where does the audio one live?
7. Music on A1: a plain audio clip with no video part, and no AVSplit group.
8. `kdenliveversion` value and any new `docproperties` 25.x requires.
