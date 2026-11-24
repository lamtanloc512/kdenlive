#!/usr/bin/env bash
# Builds bundled-tools/kdenlive-mcp-tools-macos-arm64.tar.xz from an official Kdenlive.app (arm64):
# melt, ffmpeg, ffprobe, EVERY MLT module Kdenlive ships (so all of Kdenlive's effects and transitions render),
# frei0r and LADSPA plugins, the Qt offscreen platform, Kdenlive's HD lumas (wipes) and LUTs, and the dependency
# closure of all of that. Files are copied unmodified; frameworks keep Resources/ and _CodeSignature/ so their
# signatures stay valid (dyld refuses to load them otherwise).
#
#   scripts/build-macos-tools.sh [/Applications/kdenlive.app]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="${1:-/Applications/kdenlive.app}"
NAME=kdenlive-mcp-tools-macos-arm64
OUT="$HERE/bundled-tools/$NAME.tar.xz"
A="$APP/Contents"
[ -x "$A/MacOS/melt" ] || { echo "no melt in $APP" >&2; exit 1; }
VERSION="$(/usr/libexec/PlistBuddy -c 'Print CFBundleVersion' "$A/Info.plist" 2>/dev/null || echo unknown)"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
ROOT="$TMP/$NAME"
C="$ROOT/Contents"
mkdir -p "$C/MacOS" "$ROOT/bin"

# --- binaries, plugins and their dependency closure ---------------------------------------------------
python3 - "$A" "$C" <<'PY'
import os, shutil, subprocess, sys
A, C = sys.argv[1], sys.argv[2]
roots = [f"{A}/MacOS/{b}" for b in ("melt", "ffmpeg", "ffprobe")]
for d in ("PlugIns/mlt", "PlugIns/frei0r-1", "PlugIns/ladspa", "PlugIns/imageformats"):
    for f in sorted(os.listdir(f"{A}/{d}")):
        if d.endswith("imageformats") and f not in ("libqgif.dylib", "libqjpeg.dylib", "libqsvg.dylib"):
            continue
        roots.append(f"{A}/{d}/{f}")
roots.append(f"{A}/PlugIns/platforms/libqoffscreen.dylib")

def deps(f):
    out = subprocess.run(["otool", "-L", f], capture_output=True, text=True).stdout.splitlines()[1:]
    res = []
    for line in out:
        p = line.strip().split(" (")[0]
        if p.startswith("@executable_path/../"):
            res.append(os.path.normpath(f"{A}/{p[len('@executable_path/../'):]}"))
        elif p.startswith("@rpath/"):
            res.append(f"{A}/Frameworks/{p[len('@rpath/'):]}")
        elif p.startswith("@loader_path/"):
            res.append(os.path.normpath(os.path.join(os.path.dirname(f), p[len("@loader_path/"):])))
    return res

def copy(src):
    rel = os.path.relpath(src, A)
    dst = os.path.join(C, rel)
    if os.path.lexists(dst):
        return
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if os.path.islink(src):
        os.symlink(os.readlink(src), dst)
        copy(os.path.realpath(src))
    else:
        shutil.copy2(src, dst)

seen, stack, frameworks = set(), list(roots), set()
while stack:
    f = stack.pop()
    if f in seen or not os.path.lexists(f):
        continue
    seen.add(f)
    if ".framework/" in f:
        # keep the whole version directory: binary + Resources/Info.plist + _CodeSignature
        head, tail = f.split(".framework/", 1)
        parts = tail.split("/")
        if len(parts) >= 2 and parts[0] == "Versions":
            frameworks.add(f"{head}.framework/Versions/{parts[1]}")
    copy(f)
    stack += deps(os.path.realpath(f))
for v in sorted(frameworks):
    for name in os.listdir(v):
        src = os.path.join(v, name)
        dst = os.path.join(C, os.path.relpath(src, A))
        if not os.path.lexists(dst):
            (shutil.copytree if os.path.isdir(src) and not os.path.islink(src) else shutil.copy2)(src, dst)
print(f"copied {len(seen)} binaries/plugins ({len(frameworks)} frameworks)")
PY

# --- data ------------------------------------------------------------------------------------------
mkdir -p "$C/Resources/lumas"
cp -R "$A/Resources/mlt" "$C/Resources/mlt"
cp -R "$A/Resources/lumas/HD" "$C/Resources/lumas/HD"
cp -R "$A/Resources/luts" "$C/Resources/luts"
[ -d "$A/Resources/ladspa" ] && cp -R "$A/Resources/ladspa" "$C/Resources/ladspa"

# --- wrappers ----------------------------------------------------------------------------------------
for exe in melt ffmpeg ffprobe; do
  cat > "$ROOT/bin/$exe" <<EOF
#!/usr/bin/env bash
# $exe from Kdenlive $VERSION for macOS arm64 (official DMG), packaged for kdenlive-mcp by scripts/build-macos-tools.sh.
src="\${BASH_SOURCE[0]}"
while [ -L "\$src" ]; do d="\$(cd "\$(dirname "\$src")" && pwd)"; src="\$(readlink "\$src")"; [[ \$src != /* ]] && src="\$d/\$src"; done
C="\$(cd "\$(dirname "\$src")/.." && pwd)/Contents"
export MLT_REPOSITORY="\$C/PlugIns/mlt/" MLT_DATA="\$C/Resources/mlt/"
export MLT_PROFILES_PATH="\$C/Resources/mlt/profiles/" MLT_PRESETS_PATH="\$C/Resources/mlt/presets/"
export QT_PLUGIN_PATH="\$C/PlugIns"
# only the offscreen Qt platform plugin is bundled (titles render without a display)
export QT_QPA_PLATFORM="\${QT_QPA_PLATFORM:-offscreen}"
export FREI0R_PATH="\${FREI0R_PATH:-\$C/PlugIns/frei0r-1}" LADSPA_PATH="\${LADSPA_PATH:-\$C/PlugIns/ladspa}"
exec "\$C/MacOS/$exe" "\$@"
EOF
  chmod +x "$ROOT/bin/$exe"
done
cp "$HERE/bundled-tools/NOTICE.txt" "$ROOT/NOTICE.txt"

# --- checks ------------------------------------------------------------------------------------------
bad=0
while IFS= read -r -d '' f; do
  if file -b "$f" | grep -q Mach-O && ! codesign -v "$f" 2>/dev/null; then echo "invalid signature: $f" >&2; bad=1; fi
done < <(find "$C" -type f -print0)
[ "$bad" = 0 ] || { echo "signature check failed" >&2; exit 1; }
filters="$("$ROOT/bin/melt" -query filters 2>/dev/null | grep -c '^  - ' || true)"
transitions="$("$ROOT/bin/melt" -query transitions 2>/dev/null | grep -c '^  - ' || true)"
if "$ROOT/bin/melt" -query filters 2>&1 | grep -q "failed to dlopen"; then
  "$ROOT/bin/melt" -query filters 2>&1 | grep -A2 "failed to dlopen" >&2
  exit 1
fi
echo "melt: $filters filters, $transitions transitions"

mkdir -p "$(dirname "$OUT")"
(cd "$TMP" && COPYFILE_DISABLE=1 tar --no-xattrs --no-mac-metadata --uid 0 --gid 0 --uname root --gname root -cf - "$NAME" \
  | xz -9 -T0 > "$OUT")
echo "wrote $OUT ($(du -h "$OUT" | cut -f1); unpacked $(du -sh "$ROOT" | cut -f1))"
