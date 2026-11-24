# Reference projects (please create these in Kdenlive)

These files are how we learn exactly what your Kdenlive writes. Please save them **from Kdenlive itself**
and don't edit them by hand. Use **Kdenlive 25.x** on your Mac and note the exact version (Help → About Kdenlive).

## Shared setup

1. Make a folder `fixtures/media/` and put three tiny clips in it. You can generate them with:

```bash
ffmpeg -f lavfi -i "smptebars=size=1920x1080:rate=30" -f lavfi -i "sine=frequency=440:sample_rate=48000" -t 6 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest fixtures/media/bars.mp4
```

```bash
ffmpeg -f lavfi -i "testsrc2=size=1920x1080:rate=30" -f lavfi -i "sine=frequency=660:sample_rate=48000" -t 6 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest fixtures/media/testsrc.mp4
```

```bash
ffmpeg -f lavfi -i "sine=frequency=220:sample_rate=48000" -t 12 -c:a aac fixtures/media/music.m4a
```

2. New project: Project → Project Settings → **HD 1080p 30 fps**. Keep the default tracks
   (Kdenlive's default is V1, V2, A1, A2; if yours differs, that's fine — just tell me).
3. Save each project into `fixtures/`, next to `media/`, so the paths are relative.

## The three projects

| File | Contents |
|---|---|
| `01-empty.kdenlive` | New project, nothing added. Save. |
| `02-one-clip.kdenlive` | Add `bars.mp4` to the bin and drag it onto **V1 at 0 s**, keeping its full length (its audio lands on A1). Save. |
| `03-full.kdenlive` | Put `bars.mp4` on V1 at 0 s and `testsrc.mp4` straight after it (at 6 s). Add a **same-track mix / dissolve** between them: select the cut and press `U` (Timeline → Mix Clips), duration 1 s. Add a **title clip** ("Hello", any font) on V2 at 1 s, 3 s long, with a **fade in** if you can. Put `music.m4a` on **A2** at 0 s (not A1, since the clips' own audio is on A1), lower its volume by 6 dB (Volume effect) and add an audio **fade out** over the last 1 s. Save. |

## Optional, but it saves guessing

| File | Contents |
|---|---|
| `04-extras.kdenlive` | Same as 02, plus a second clip with a **wipe** mix instead of a dissolve, and a **Fade to black** effect on the last clip. |

Finally, please export one render of `03-full.kdenlive` (any MP4 preset) as `fixtures/03-full.mp4`.
Then we can compare our own render with Kdenlive's.

## Tool paths (please paste the output of this)

```bash
for b in melt ffprobe ffmpeg; do echo "== $b"; which -a $b; done; ls /Applications/kdenlive.app/Contents/MacOS/ 2>/dev/null; /Applications/kdenlive.app/Contents/MacOS/melt -version 2>&1 | head -1; melt -version 2>&1 | head -1; ffprobe -version | head -1; /Applications/kdenlive.app/Contents/MacOS/melt -query producers 2>/dev/null | grep -c kdenlivetitle
```
