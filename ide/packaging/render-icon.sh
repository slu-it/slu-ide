#!/usr/bin/env bash
# Renders thoreau.svg into the icons that are committed next to it: Thoreau.icns for the macOS app, thoreau-512.png
# for jpackage on Linux and the window icons in the ide resources. Run it after changing the SVG.
# Needs rsvg-convert (librsvg; ImageMagick's own SVG renderer drops gradients and strokes) and python3.
set -euo pipefail
cd "$(dirname "$0")"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

for size in 16 32 64 128 256 512 1024; do
  rsvg-convert --width "$size" --height "$size" thoreau.svg --output "$work/$size.png"
done

cp "$work/512.png" thoreau-512.png
resources=../src/main/resources/dev/sluide/ide/icon
mkdir -p "$resources"
for size in 16 32 64 128 256; do
  cp "$work/$size.png" "$resources/thoreau-$size.png"
done

# iconutil exists on macOS only, so the .icns is packed here: "icns" and the file length, then per entry its type,
# its length including this 8-byte header and the PNG.
python3 - "$work" Thoreau.icns <<'PYTHON'
import struct, sys
work, target = sys.argv[1:]
types = [("icp4", 16), ("icp5", 32), ("icp6", 64), ("ic07", 128), ("ic08", 256), ("ic09", 512), ("ic10", 1024),
         ("ic11", 32), ("ic12", 64), ("ic13", 256), ("ic14", 512)]
entries = b""
for kind, size in types:
    png = open(f"{work}/{size}.png", "rb").read()
    entries += kind.encode() + struct.pack(">I", len(png) + 8) + png
open(target, "wb").write(b"icns" + struct.pack(">I", len(entries) + 8) + entries)
PYTHON
