#!/usr/bin/env bash
# Builds bundled-tools/kdenlive-mcp-tools-linux-x64.tar.xz from the official Kdenlive AppImage (x86_64):
# melt, ffmpeg, ffprobe, EVERY MLT module the AppImage ships (so all of Kdenlive's effects and transitions render),
# frei0r and LADSPA plugins, the Qt offscreen platform, Kdenlive's HD lumas and LUTs, and the libraries they need
# from the AppImage (computed from the ELF DT_NEEDED entries; system libraries such as libc stay on the host).
# Runs on macOS or Linux; needs unsquashfs (brew install squashfs / apt install squashfs-tools) and python3.
#
#   scripts/build-linux-tools.sh [path/to/kdenlive-26.08.3-x86_64.AppImage]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${KDENLIVE_VERSION:-26.08.3}"
SHA256="${KDENLIVE_APPIMAGE_SHA256:-2c8322edfc86605e4c98a78a019310d9b4fc46bb8e94e6fe313dc7dd987e7671}"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/kdenlive-mcp"
APPIMAGE="${1:-$CACHE/kdenlive-$VERSION-x86_64.AppImage}"
NAME=kdenlive-mcp-tools-linux-x64
OUT="$HERE/bundled-tools/$NAME.tar.xz"
command -v unsquashfs >/dev/null || { echo "unsquashfs is required (squashfs-tools)" >&2; exit 1; }

if [ ! -f "$APPIMAGE" ]; then
  mkdir -p "$(dirname "$APPIMAGE")"
  curl -fL -o "$APPIMAGE" "https://download.kde.org/stable/kdenlive/${VERSION%.*}/linux/kdenlive-$VERSION-x86_64.AppImage"
fi
sum="$(if command -v sha256sum >/dev/null; then sha256sum "$APPIMAGE"; else shasum -a 256 "$APPIMAGE"; fi | cut -c1-64)"
[ "$sum" = "$SHA256" ] || { echo "sha256 mismatch for $APPIMAGE: $sum" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# the squashfs image starts right after the ELF runtime (end of its section header table)
offset="$(python3 -c "import struct,sys; d=open(sys.argv[1],'rb').read(64); \
print(struct.unpack_from('<Q',d,0x28)[0]+struct.unpack_from('<H',d,0x3A)[0]*struct.unpack_from('<H',d,0x3C)[0])" "$APPIMAGE")"
unsquashfs -q -no-xattrs -o "$offset" -d "$TMP/app" "$APPIMAGE" >/dev/null
A="$TMP/app/usr"
ROOT="$TMP/$NAME"
U="$ROOT/usr"
mkdir -p "$U" "$ROOT/bin"

python3 - "$A" "$U" <<'PY'
import os, shutil, struct, sys
A, U = os.path.realpath(sys.argv[1]), sys.argv[2]  # realpath: /var vs /private/var on macOS

def needed(path):
    """DT_NEEDED names of an ELF64 little-endian file (empty for anything else)."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return []
    phoff, = struct.unpack_from("<Q", data, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    loads, dyn = [], None
    for i in range(phnum):
        p_type, _, p_offset, p_vaddr, _, p_filesz = struct.unpack_from("<IIQQQQ", data, phoff + i * phentsize)
        if p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:
            dyn = (p_offset, p_filesz)
    if dyn is None:
        return []
    entries, strtab = [], None
    for off in range(dyn[0], dyn[0] + dyn[1], 16):
        tag, val = struct.unpack_from("<qQ", data, off)
        if tag == 0:
            break
        if tag == 1:
            entries.append(val)
        elif tag == 5:
            strtab = val
    if strtab is None:
        return []
    base = next((o + strtab - v for v, o, s in loads if v <= strtab < v + s), None)
    if base is None:
        return []
    out = []
    for e in entries:
        end = data.index(b"\0", base + e)
        out.append(data[base + e:end].decode())
    return out

libdirs = [os.path.join(A, "lib"), os.path.join(A, "lib", "x86_64-linux-gnu")]
def find(name):
    for d in libdirs:
        p = os.path.join(d, name)
        if os.path.lexists(p):
            return p
    return None

def copy(src):
    dst = os.path.join(U, os.path.relpath(src, A))
    if os.path.lexists(dst):
        return
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if os.path.islink(src):
        os.symlink(os.readlink(src), dst)
        target = os.path.realpath(src)
        if target.startswith(A):
            copy(target)
    else:
        shutil.copy2(src, dst)

roots = [os.path.join(A, "bin", b) for b in ("melt", "melt-7", "ffmpeg", "ffprobe") if os.path.lexists(os.path.join(A, "bin", b))]
for d in ("lib/mlt-7", "lib/frei0r-1", "lib/ladspa"):
    full = os.path.join(A, d)
    roots += [os.path.join(full, f) for f in sorted(os.listdir(full))]
roots.append(os.path.join(A, "plugins/platforms/libqoffscreen.so"))
for f in ("libqgif.so", "libqjpeg.so", "libqsvg.so"):
    roots.append(os.path.join(A, "plugins/imageformats", f))

seen, stack, system = set(), list(roots), set()
while stack:
    f = stack.pop()
    if f in seen or not os.path.lexists(f):
        continue
    seen.add(f)
    copy(f)
    real = os.path.realpath(f)
    if os.path.isfile(real):
        for n in needed(real):
            p = find(n)
            if p:
                stack.append(p)
            else:
                system.add(n)
print(f"copied {len(seen)} binaries/plugins/libraries; from the host system: {', '.join(sorted(system))}")
PY

cp "$A/bin/qt.conf" "$U/bin/qt.conf" 2>/dev/null || printf '[Paths]\nPrefix = ../\nPlugins = plugins\n' > "$U/bin/qt.conf"
mkdir -p "$U/share/kdenlive/lumas"
cp -R "$A/share/mlt-7" "$U/share/mlt-7"
[ -d "$A/share/movit" ] && cp -R "$A/share/movit" "$U/share/movit"
cp -R "$A/share/kdenlive/lumas/HD" "$U/share/kdenlive/lumas/HD"
[ -d "$A/share/kdenlive/luts" ] && cp -R "$A/share/kdenlive/luts" "$U/share/kdenlive/luts"

for exe in melt ffmpeg ffprobe; do
  target="$exe"
  [ "$exe" = melt ] && [ -e "$U/bin/melt-7" ] && target=melt-7
  cat > "$ROOT/bin/$exe" <<EOF
#!/usr/bin/env bash
# $exe from Kdenlive $VERSION (official AppImage), packaged for kdenlive-mcp by scripts/build-linux-tools.sh.
root="\$(cd "\$(dirname "\$(readlink -f "\${BASH_SOURCE[0]}")")/.." && pwd)/usr"
export LD_LIBRARY_PATH="\$root/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
export MLT_REPOSITORY="\$root/lib/mlt-7/" MLT_DATA="\$root/share/mlt-7/" MLT_ROOT_DIR="\$root/" MLT_APPDIR="\$root/"
export MLT_PROFILES_PATH="\$root/share/mlt-7/profiles/" MLT_PRESETS_PATH="\$root/share/mlt-7/presets/"
export QT_PLUGIN_PATH="\$root/plugins"
# only the offscreen Qt platform plugin is bundled (titles render without a display)
export QT_QPA_PLATFORM="\${QT_QPA_PLATFORM:-offscreen}"
export FREI0R_PATH="\${FREI0R_PATH:-\$root/lib/frei0r-1}" LADSPA_PATH="\${LADSPA_PATH:-\$root/lib/ladspa}"
[ -d /etc/fonts ] && export FONTCONFIG_PATH=/etc/fonts
export LC_ALL="\${LC_ALL:-C.UTF-8}"
exec "\$root/bin/$target" "\$@"
EOF
  chmod +x "$ROOT/bin/$exe"
done
cp "$HERE/bundled-tools/NOTICE.txt" "$ROOT/NOTICE.txt"

mkdir -p "$(dirname "$OUT")"
(cd "$TMP" && COPYFILE_DISABLE=1 tar --no-xattrs --no-mac-metadata --uid 0 --gid 0 --uname root --gname root -cf - "$NAME" 2>/dev/null \
  | xz -9 -T0 > "$OUT") || (cd "$TMP" && tar --owner=0 --group=0 -cf - "$NAME" | xz -9 -T0 > "$OUT")
echo "wrote $OUT ($(du -h "$OUT" | cut -f1); unpacked $(du -sh "$ROOT" | cut -f1))"
echo "check it on Linux: tar -xJf $OUT && $NAME/bin/melt -query filters | grep -c '^  - '"
